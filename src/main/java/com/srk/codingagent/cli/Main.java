package com.srk.codingagent.cli;

import com.srk.codingagent.config.ConfigException;
import com.srk.codingagent.config.ConfigLocations;
import com.srk.codingagent.config.ConfigResolver;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.model.credentials.CredentialResolutionException;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OutcomeRecorder;
import com.srk.codingagent.persistence.SessionLineage;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.workflow.BrownfieldDriver;
import com.srk.codingagent.workflow.BrownfieldRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Signal;

/**
 * Command-line entry point for the coding agent.
 *
 * <p>The CLI resolves its configuration on startup and dispatches by invocation shape
 * (04-apis § 1.1). A one-shot invocation ({@code codingagent -p "<prompt>"}) runs the
 * agent's tool-use cycle to {@code end_turn} then exits with the agent-process exit code
 * (US-6); the interactive REPL ({@code codingagent} with no {@code -p}) enters a read-eval
 * loop (driven by {@link ReplRunner}) that runs each developer line as an agent-loop turn or
 * an in-REPL slash-command and exits cleanly on {@code /exit} / EOF (T-1.1). Configuration is
 * layered by precedence (flags &gt; project &gt; global &gt; defaults) and validated
 * <em>before</em> any model call (ADR-0009): a malformed or unknown configuration value, or a
 * bad CLI argument, makes the process exit {@link ExitCode#USAGE_CONFIG} ({@code 2}) with a
 * stderr line naming the offending key/argument (AC-8.5, AC-6.4, cli-exit-codes {@code 2}).
 *
 * <p><b>SIGINT (02-architecture § 4, cli-exit-codes {@code 130}).</b> The interactive path
 * installs an OS signal handler for {@code INT} (Ctrl-C) that interrupts the in-flight REPL
 * step and ends the session with exit {@code 130}; the session remains resumable because the
 * event log is flushed per event (NFR-LOG-DURABILITY). Installing the OS signal handler is the
 * one non-portable production-only step, so it lives here in the bootstrap shell (excluded from
 * the coverage gate); the testable interrupt-to-{@code 130} mapping lives in {@link ReplRunner}
 * and is reconciled with the {@link InterruptedRunException} seam {@link OneShotRunner} maps.
 *
 * <p><b>Exit-code precedence (cli-exit-codes § 2).</b> The launcher realizes the contract's
 * ordering: {@code 2} usage/config is decided here at startup, before any model or tool
 * work; the model-backend ({@code 4}), context-exhausted ({@code 5}), user-aborted
 * ({@code 3}), internal ({@code 1}), interrupted ({@code 130}), and success ({@code 0})
 * codes are decided by {@link OneShotRunner} once the run begins. Every non-zero exit prints
 * one human-readable stderr line naming the cause (guarantee G2).
 *
 * <p>The launch logic lives in {@link #run(String[])}, which returns a process exit code
 * instead of calling {@link System#exit(int)} directly, so it is unit-testable without
 * terminating the test JVM (the agent-loop construction is the one production-only seam,
 * isolated in {@link AgentLoopFactory}). {@link #main(String[])} is the thin shell that
 * maps the returned code onto the process exit status.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    /**
     * Process exit code for a successful, clean launch ({@code 0}).
     *
     * <p>Pinned by the CLI exit contract ({@code 0} = success); equal to
     * {@link ExitCode#OK}. Retained as a named constant for the success path and
     * the launch tests.
     */
    static final int SUCCESS_EXIT_CODE = ExitCode.OK.code();

    /**
     * The session lineage / id used for the one-shot run's event log. Repo-key derivation
     * from a git remote and stable session ids are a later session task; at M0 a one-shot
     * uses a fixed boundary-captured lineage so the gate's grant store and the session log
     * are wired without pulling that derivation forward.
     */
    private static final String ONE_SHOT_LINEAGE = "one-shot";

    private Main() {
        // Utility entry point; not instantiable.
    }

    /**
     * Launches the agent CLI: parses the invocation, resolves configuration, and runs the
     * selected shape, returning the process exit code.
     *
     * <p>On a bad invocation (unknown flag, missing prompt value) or a configuration error,
     * the method prints a stderr line naming the offending argument/key and returns
     * {@link ExitCode#USAGE_CONFIG} ({@code 2}) before any model call (fail-fast, ADR-0009).
     * For a one-shot prompt it builds the agent loop and delegates the run-and-map to
     * {@link OneShotRunner}. For {@code --help} / {@code --version} it prints the requested
     * information and returns {@code 0}. For the {@code resume} / {@code sessions}
     * subcommands it delegates to {@link ResumeCommand} (pure persistence + replay — no
     * config or model call). For the interactive REPL shape (no {@code -p}) it builds the
     * agent loop with an interactive approver, installs the SIGINT handler, and delegates
     * the read-eval loop to {@link ReplRunner}, returning the session's exit code (clean
     * {@code 0} on {@code /exit} / EOF, {@code 130} on Ctrl-C).
     *
     * @param args command-line arguments; may be {@code null} (treated as no arguments).
     * @return the process exit code per the exit-code contract.
     */
    public static int run(String[] args) {
        LOGGER.info("codingagent CLI starting ({} argument(s))", args == null ? 0 : args.length);
        CliArguments parsed;
        try {
            parsed = CliArguments.parse(args);
        } catch (UsageException e) {
            // Bad CLI args → exit 2 (cli-exit-codes 2, § 3.2). G2: name the offending arg.
            LOGGER.error("usage error for argument '{}': {}", e.offendingArgument(), e.getMessage());
            System.err.println("codingagent: usage error: " + e.getMessage());
            return ExitCode.USAGE_CONFIG.code();
        }

        if (parsed.kind() == CliArguments.Kind.INFO) {
            return printInfo(parsed.infoFlag().orElseThrow());
        }

        // Session subcommands (resume / sessions) are pure persistence + replay: they list
        // and reconstruct from the on-disk session store and need no config resolution or
        // model call (04-apis § 1.2). They run before the config gate so listing/resume work
        // even when no model is reachable.
        if (parsed.kind() == CliArguments.Kind.SESSIONS) {
            return resumeCommand().list();
        }
        if (parsed.kind() == CliArguments.Kind.RESUME) {
            ResumeCommand command = resumeCommand();
            return parsed.sessionId().map(command::resume).orElseGet(command::list);
        }

        ResolvedConfig config;
        try {
            config = resolveConfig();
        } catch (ConfigException e) {
            // Fail-fast (ADR-0009): a bad/unknown config value must stop the run before any
            // model call. G2: the stderr line names the offending key.
            LOGGER.error("configuration error for key '{}': {}", e.key(), e.getMessage());
            System.err.println("codingagent: configuration error: " + e.getMessage());
            return ExitCode.USAGE_CONFIG.code();
        }
        LOGGER.info("codingagent CLI started with model '{}' in mode {}",
                config.modelId(), config.permissionMode());

        if (parsed.kind() == CliArguments.Kind.ONE_SHOT) {
            return runOneShot(config, parsed.prompt().orElseThrow());
        }
        // Interactive REPL shape (no -p): enter the read-eval loop (04-apis § 1.1).
        return runInteractive(config);
    }

    /**
     * Builds the {@link ResumeCommand} for the session subcommands over the user-home
     * session store, scoped to the same repository key the rest of the system writes
     * sessions under today ({@link #ONE_SHOT_LINEAGE}). Real git-remote repo-key derivation
     * (03-data-model § 2.1) is a later session task; scoping the listing to the key the
     * one-shot/REPL paths write to is what makes {@code resume}/{@code sessions} list the
     * sessions that actually exist on disk rather than an empty unrelated key.
     */
    private static ResumeCommand resumeCommand() {
        return new ResumeCommand(
                SessionStore.forUserHome(),
                new SessionReplay(),
                new SessionLineage(),
                ONE_SHOT_LINEAGE,
                System.out);
    }

    /**
     * Wires the production agent loop and runs the one-shot prompt, mapping the outcome to
     * an exit code. The agent-loop construction (credentials, Bedrock client, tools, gate,
     * log) is the one production-only seam; the run-and-map logic it delegates to lives in
     * the unit-tested {@link OneShotRunner}.
     */
    private static int runOneShot(ResolvedConfig config, String prompt) {
        Path workspaceRoot = Path.of("").toAbsolutePath();
        SessionStore sessions = SessionStore.forUserHome();
        try (EventLog log = sessions.openLog(ONE_SHOT_LINEAGE, ONE_SHOT_LINEAGE)) {
            AgentLoop loop = new AgentLoopFactory().create(
                    config, workspaceRoot, ONE_SHOT_LINEAGE, log, new NonInteractiveApprover());
            // T-1.6: route the one-shot through the brownfield workflow driver (ADR-0012,
            // v1 brownfield-only) so the model explores->changes (the loop carries the
            // brownfield playbook) then the driver verifies the change via the configured
            // test command (AC-5.3). The runner maps the brownfield outcome to a LoopOutcome
            // so OneShotRunner's exit-code mapping is unchanged.
            // T-2.6 (US-16): record an OUTCOME signal when the run concludes — success derived from
            // the verification command's exit status (AC-16.2) and appended to the session log
            // (AC-16.1/AC-16.3). The brownfield run is free-form (not task-numbered) at M2, so the
            // outcome's taskRef defaults to the session lineage id (ONE_SHOT_LINEAGE). The boundary
            // clock matches the one the loop uses (ADR-0005). The recorder's derivation + append is
            // a tested unit (OutcomeRecorder); this composition root only wires it.
            OutcomeRecorder outcomeRecorder = new OutcomeRecorder(
                    log, () -> Instant.now().toString(), ONE_SHOT_LINEAGE);
            BrownfieldRunner brownfield = new BrownfieldRunner(BrownfieldDriver.overConfig(
                    loop::run, new CommandExecutor(workspaceRoot), config), outcomeRecorder);
            return new OneShotRunner(brownfield::run, System.out, System.err).run(prompt);
        }
    }

    /**
     * Wires the production agent loop with an interactive approver and runs the REPL session,
     * returning the session's exit code. The composition (credentials, Bedrock client, tools,
     * gate, log) is the production-only seam; the read-eval loop it delegates to lives in the
     * unit-tested {@link ReplRunner}, and the interactive approval prompt in
     * {@link InteractiveApprover}.
     *
     * <p>A SIGINT handler is installed for the duration of the session: on Ctrl-C it sets the
     * shared interrupt flag {@link ReplRunner} polls and interrupts this thread so an in-flight
     * step is cancelled and the session ends with exit {@code 130} (02-architecture § 4). The
     * session log is held open across turns and closed on exit; per-event flushing keeps the
     * session resumable (NFR-LOG-DURABILITY).
     */
    private static int runInteractive(ResolvedConfig config) {
        Path workspaceRoot = Path.of("").toAbsolutePath();
        SessionStore sessions = SessionStore.forUserHome();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread session = Thread.currentThread();
        installSigintHandler(interrupted, session);

        try (EventLog log = sessions.openLog(ONE_SHOT_LINEAGE, ONE_SHOT_LINEAGE)) {
            Supplier<String> answerSource = lineSupplier(reader);
            Approver approver = new InteractiveApprover(answerSource, System.out);
            AgentLoop loop = new AgentLoopFactory().create(
                    config, workspaceRoot, ONE_SHOT_LINEAGE, log, approver);
            // T-1.6: each REPL turn is a brownfield understand->change->verify cycle (ADR-0012,
            // v1 brownfield-only). The loop carries the brownfield playbook; the driver verifies
            // each completed change via the configured test command (AC-5.3). The runner maps the
            // brownfield outcome to a LoopOutcome so ReplRunner's per-turn handling is unchanged.
            // T-2.6 (US-16): each concluded turn records an OUTCOME signal to the same session log,
            // success derived from the verification exit status (AC-16.2/AC-16.1/AC-16.3). The
            // free-form brownfield turn's taskRef defaults to the session lineage id; the boundary
            // clock matches the loop's (ADR-0005). The derivation is the tested OutcomeRecorder unit.
            OutcomeRecorder outcomeRecorder = new OutcomeRecorder(
                    log, () -> Instant.now().toString(), ONE_SHOT_LINEAGE);
            BrownfieldRunner brownfield = new BrownfieldRunner(BrownfieldDriver.overConfig(
                    loop::run, new CommandExecutor(workspaceRoot), config), outcomeRecorder);
            ReplRunner runner = new ReplRunner(brownfield::run, answerSource, interrupted::get,
                    config.permissionMode(), System.out, System.err);
            return runner.run();
        } catch (CredentialResolutionException noCredentials) {
            // No usable SigV4 credentials means no usable Bedrock — the REPL cannot start
            // (model-backend, AC-8.9 → exit 4). Reported once with the paths attempted (G2).
            LOGGER.error("cannot start interactive session (no usable credentials): {}",
                    noCredentials.getMessage(), noCredentials);
            System.err.println("codingagent: " + noCredentials.getMessage());
            return ExitCode.MODEL_BACKEND.code();
        }
    }

    /**
     * Adapts a line reader to the {@link Supplier} the REPL and approver read from: each call
     * returns the next line, or {@code null} at end-of-input (Ctrl-D / EOF). An I/O error on
     * the input stream is treated as end-of-input so the session ends cleanly rather than
     * crashing.
     */
    private static Supplier<String> lineSupplier(BufferedReader reader) {
        return () -> {
            try {
                return reader.readLine();
            } catch (IOException e) {
                LOGGER.warn("input stream read failed; treating as end-of-input", e);
                return null;
            }
        };
    }

    /**
     * Installs the OS SIGINT (Ctrl-C) handler for the interactive session: it flags the
     * interrupt the REPL polls and interrupts the session thread so an in-flight step is
     * cancelled (02-architecture § 4; cli-exit-codes {@code 130}). Using {@code sun.misc.Signal}
     * (the {@code jdk.unsupported} module, available without compiler flags) is the portable-
     * enough way to install a process signal handler on the standard JDK; it lives in this
     * coverage-excluded bootstrap shell so the testable interrupt-to-{@code 130} logic in
     * {@link ReplRunner} stays free of the non-portable dependency.
     */
    private static void installSigintHandler(AtomicBoolean interrupted, Thread session) {
        try {
            Signal.handle(new Signal("INT"), signal -> {
                interrupted.set(true);
                session.interrupt();
            });
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            // A platform that does not allow handling INT (or already reserves it): the REPL
            // still works; only the in-step Ctrl-C-to-130 cancellation is unavailable.
            LOGGER.warn("could not install SIGINT handler; Ctrl-C cancellation unavailable", e);
        }
    }

    private static int printInfo(String infoFlag) {
        if (CliArguments.VERSION.equals(infoFlag)) {
            System.out.println("codingagent (development build)");
        } else {
            System.out.println("usage: codingagent -p \"<prompt>\" [options]");
        }
        return SUCCESS_EXIT_CODE;
    }

    private static ResolvedConfig resolveConfig() {
        ConfigLocations locations = ConfigLocations.forUserHome();
        return new ConfigResolver().resolve(
                locations.globalConfig(),
                locations.projectConfigForUnkeyedRepo(),
                Map.of());
    }

    /**
     * Process entry point. Delegates to {@link #run(String[])} and terminates the
     * JVM with the returned exit code.
     *
     * @param args command-line arguments forwarded to {@link #run(String[])}.
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }
}
