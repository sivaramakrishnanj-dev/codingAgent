package com.srk.codingagent.cli;

import com.srk.codingagent.config.ConfigException;
import com.srk.codingagent.config.ConfigLocations;
import com.srk.codingagent.config.ConfigResolver;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.model.credentials.CredentialResolutionException;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OutcomeRecorder;
import com.srk.codingagent.persistence.RepoKeyResolver;
import com.srk.codingagent.persistence.SessionLineage;
import com.srk.codingagent.persistence.SessionMode;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.tool.CommandExecutor;
import com.srk.codingagent.tool.GreenfieldArtifactStore;
import com.srk.codingagent.workflow.ArtifactApprovalGate;
import com.srk.codingagent.workflow.BrownfieldDriver;
import com.srk.codingagent.workflow.BrownfieldRunner;
import com.srk.codingagent.workflow.GreenfieldDriver;
import com.srk.codingagent.workflow.GreenfieldRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * The session id used for a run's event log. The run's lineage / repo key is now the real
     * AC-7.3 repo key (resolved by {@link #repoKey()} &mdash; git remote URL else normalized absolute
     * path, ADR-0005), brought forward by DCR-3 to replace the fixed {@code "one-shot"} M0 placeholder
     * that made distinct runs over different target projects collide. The session id within that key
     * stays a fixed boundary-captured value at v1 (stable session ids are a later task); the repo key
     * is what scopes a resumable greenfield session (AC-7.6) to its target project.
     */
    private static final String SESSION_ID = "one-shot";

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
            return runOneShot(config, parsed.prompt().orElseThrow(), parsed.mode(),
                    parsed.attachPath().orElse(null));
        }
        // Interactive REPL shape (no -p): enter the read-eval loop (04-apis § 1.1).
        return runInteractive(config, parsed.mode());
    }

    /**
     * Builds the {@link ResumeCommand} for the session subcommands over the user-home
     * session store, scoped to the same repository key the run paths write sessions under
     * &mdash; the real AC-7.3 repo key for the current target project ({@link #repoKey()},
     * DCR-3): git remote URL else normalized absolute path (03-data-model § 2.1). Scoping the
     * listing to the key the one-shot/REPL paths write to is what makes {@code resume}/
     * {@code sessions} list the sessions that actually exist on disk for this project rather
     * than an empty unrelated key (and no longer the fixed {@code "one-shot"} placeholder that
     * pooled every project's sessions under one key).
     */
    private static ResumeCommand resumeCommand() {
        return new ResumeCommand(
                SessionStore.forUserHome(),
                new SessionReplay(),
                new SessionLineage(),
                repoKey(),
                System.out);
    }

    /**
     * Wires the production agent loop and runs the one-shot prompt, mapping the outcome to
     * an exit code. The agent-loop construction (credentials, Bedrock client, tools, gate,
     * log) is the one production-only seam; the run-and-map logic it delegates to lives in
     * the unit-tested {@link OneShotRunner}.
     *
     * <p><b>Mode selects the workflow driver (ADR-0012, T-3.1).</b> {@link SessionMode#GREENFIELD}
     * (from {@code --mode greenfield}) routes the one-shot through the greenfield phase state
     * machine; the default {@link SessionMode#BROWNFIELD} routes it through the brownfield
     * understand&rarr;change&rarr;verify driver. Both produce the {@code LoopOutcome run(String)}
     * seam {@link OneShotRunner} maps to an exit code, so the exit-code mapping is mode-agnostic.
     */
    private static int runOneShot(
            ResolvedConfig config, String prompt, SessionMode mode, String attachPath) {
        Path workspaceRoot = Path.of("").toAbsolutePath();
        // DCR-3 / AC-7.3: scope the session log + grant store + resumable greenfield phase-state to
        // the real repo key for this target project (git remote URL else normalized abs path),
        // replacing the fixed "one-shot" placeholder.
        String lineage = repoKey();
        List<ContentBlock> attachments = resolveOneShotAttachment(config, attachPath);
        SessionStore sessions = SessionStore.forUserHome();
        try (EventLog log = sessions.openLog(lineage, SESSION_ID)) {
            OneShotRunner.OneShotLoop runLoop = mode == SessionMode.GREENFIELD
                    ? oneShotGreenfield(config, workspaceRoot, lineage, log, sessions)
                    : oneShotBrownfield(config, workspaceRoot, lineage, log, sessions, attachments);
            return new OneShotRunner(runLoop, System.out, System.err).run(prompt);
        }
    }

    /**
     * Resolves a one-shot {@code --attach <path>} into the admitted multimodal block(s) for the
     * opening turn (T-4.2, § 2.3). The {@link AttachmentResolver} infers the format, sanitizes a
     * document name (INV-18), and applies the capability gate (INV-19): an admitted attachment is
     * returned for the turn; a declined one (unsupported file type, or a model that does not
     * accept that input) prints the decline message to stderr and is not sent — the run still
     * proceeds (graceful degradation). No {@code --attach} yields an empty list.
     */
    private static List<ContentBlock> resolveOneShotAttachment(
            ResolvedConfig config, String attachPath) {
        if (attachPath == null) {
            return List.of();
        }
        Attachment attachment = new AttachmentResolver(
                new AgentLoopFactory().capabilityProfile(config)).resolve(attachPath);
        if (attachment instanceof Attachment.Attached attached) {
            return List.of(attached.block());
        }
        // INV-19 / unsupported type: declined with a message, not sent (graceful degradation).
        System.err.println("codingagent: " + ((Attachment.Declined) attachment).message());
        return List.of();
    }

    /**
     * Composes the one-shot brownfield run path (the default mode): the production agent loop, the
     * brownfield workflow driver (ADR-0012), and the outcome recorder, returned as the
     * {@code LoopOutcome run(String)} seam.
     *
     * <p>T-1.6: the one-shot runs through the brownfield driver so the model explores&rarr;changes
     * (the loop carries the brownfield playbook) then the driver verifies the change via the
     * configured test command (AC-5.3). T-2.6 (US-16): an OUTCOME signal is recorded when the run
     * concludes &mdash; success derived from the verification command's exit status (AC-16.2) and
     * appended to the session log (AC-16.1/AC-16.3). The boundary clock matches the loop's (ADR-0005).
     *
     * <p>T-4.2: any admitted {@code --attach} multimodal block(s) join the opening user turn — the
     * brownfield loop seam is bound to {@link AgentLoop#run(String, List)} carrying them, so the
     * {@code ConverseWireMapper} renders the attachment into the first request (§ 2.3 multimodal
     * input). An empty {@code attachments} list is the no-{@code --attach} case (the prior behaviour).
     */
    private static OneShotRunner.OneShotLoop oneShotBrownfield(ResolvedConfig config,
            Path workspaceRoot, String lineage, EventLog log, SessionStore sessions,
            List<ContentBlock> attachments) {
        AgentLoop loop = new AgentLoopFactory().create(
                config, workspaceRoot, lineage, log, new NonInteractiveApprover(),
                sessions);
        OutcomeRecorder outcomeRecorder = new OutcomeRecorder(
                log, () -> Instant.now().toString(), lineage);
        BrownfieldRunner brownfield = new BrownfieldRunner(BrownfieldDriver.overConfig(
                prompt -> loop.run(prompt, attachments), new CommandExecutor(workspaceRoot), config),
                outcomeRecorder);
        return brownfield::run;
    }

    /**
     * Composes the one-shot greenfield run path ({@code --mode greenfield}, ADR-0012, T-3.1): the
     * greenfield phase state machine driven over phase-scoped agent loops (the pre-approval phases'
     * loops withhold the Class-X source tools, AC-1.4), returned as the {@code LoopOutcome
     * run(String)} seam.
     *
     * <p><b>No-terminal approval gate (one-shot).</b> A one-shot run has no developer terminal to
     * approve a phase advance, so the approval decision denies every advance &mdash; the session
     * shapes the requirements (AC-1.1) and stops at the first approval gate awaiting the developer's
     * approval (ADR-0012, AC-2.3) without writing source (AC-1.4), the safe non-interactive stance
     * (mirroring {@link NonInteractiveApprover}).
     *
     * <p><b>The timestamped, traceability-enforcing gate (T-3.2).</b> The driver is built via
     * {@link AgentLoopFactory#createGreenfieldDriver}, which wires the {@code ArtifactApprovalGate}
     * over the deny-all decision: were a phase approved, the gate would record the approval timestamp
     * into that phase's artifact (AC-1.5) and enforce task-breakdown traceability (AC-2.5). On the
     * one-shot path the decision never approves, so no timestamp is recorded and no source is written
     * &mdash; the safe stance. The artifact-authoring write path itself ({@code write_artifact},
     * confined to the target repo's design-doc directory) is offered to the model in the pre-approval
     * phases by the phase-scoped registry (AC-1.2/AC-2.1), distinct from the withheld source-write
     * tools (AC-1.4).
     */
    private static OneShotRunner.OneShotLoop oneShotGreenfield(ResolvedConfig config,
            Path workspaceRoot, String lineage, EventLog log, SessionStore sessions) {
        // DCR-2: a one-shot run has no terminal to supply refining turns, so the developer-turn
        // source is empty (end-of-input): the requirements phase runs its single opening turn and
        // pauses awaiting approval (AC-2.3) without writing source (AC-1.4). The approval decision is
        // likewise deny-all (no terminal to approve), the safe non-interactive stance. DCR-3/AC-7.6:
        // the driver re-derives the resumable phase-state from the target repo's stamped artifacts
        // (the factory wires the resume probe over the workspaceRoot store), keyed to the project by
        // the real AC-7.3 repo key (lineage).
        GreenfieldDriver driver = new AgentLoopFactory().createGreenfieldDriver(
                config, workspaceRoot, lineage, log, new NonInteractiveApprover(),
                sessions, completedPhase -> false, phase -> null);
        GreenfieldRunner greenfield = new GreenfieldRunner(driver);
        return greenfield::run;
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
    private static int runInteractive(ResolvedConfig config, SessionMode mode) {
        Path workspaceRoot = Path.of("").toAbsolutePath();
        // DCR-3 / AC-7.3: scope the REPL session to the real repo key for this target project.
        String lineage = repoKey();
        SessionStore sessions = SessionStore.forUserHome();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Thread session = Thread.currentThread();
        installSigintHandler(interrupted, session);

        try (EventLog log = sessions.openLog(lineage, SESSION_ID)) {
            Supplier<String> answerSource = lineSupplier(reader);
            Approver approver = new InteractiveApprover(answerSource, System.out);
            // ADR-0012, T-3.1: mode selects the workflow driver for each REPL turn — the greenfield
            // phase state machine or the brownfield understand->change->verify cycle. Both produce
            // the LoopOutcome run(String) seam ReplRunner drives, so the per-turn handling is
            // mode-agnostic.
            ReplRunner.ReplLoop turnLoop = mode == SessionMode.GREENFIELD
                    ? interactiveGreenfield(
                            config, workspaceRoot, lineage, log, approver, sessions, answerSource)
                    : interactiveBrownfield(config, workspaceRoot, lineage, log, approver, sessions);
            // T-4.2: the REPL's /attach pipeline gates against the active model's capability
            // profile (INV-19) — resolved via the same factory the loop's budget guard uses.
            AttachmentResolver attachmentResolver = new AttachmentResolver(
                    new AgentLoopFactory().capabilityProfile(config));
            ReplRunner runner = new ReplRunner(turnLoop, answerSource, interrupted::get,
                    config.permissionMode(), attachmentResolver, System.out, System.err);
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
     * Composes an interactive REPL turn's brownfield run path (the default mode): the production
     * agent loop, the brownfield workflow driver (ADR-0012), and the outcome recorder, returned as
     * the {@code LoopOutcome run(String)} seam {@link ReplRunner} drives per turn.
     *
     * <p>T-1.6: each REPL turn is a brownfield understand&rarr;change&rarr;verify cycle &mdash; the
     * loop carries the brownfield playbook and the driver verifies each completed change via the
     * configured test command (AC-5.3). T-2.6 (US-16): each concluded turn records an OUTCOME signal
     * to the same session log (AC-16.1/AC-16.2/AC-16.3). The boundary clock matches the loop's
     * (ADR-0005).
     */
    private static ReplRunner.ReplLoop interactiveBrownfield(ResolvedConfig config,
            Path workspaceRoot, String lineage, EventLog log, Approver approver,
            SessionStore sessions) {
        AgentLoop loop = new AgentLoopFactory().create(
                config, workspaceRoot, lineage, log, approver, sessions);
        OutcomeRecorder outcomeRecorder = new OutcomeRecorder(
                log, () -> Instant.now().toString(), lineage);
        BrownfieldRunner brownfield = new BrownfieldRunner(BrownfieldDriver.overConfig(
                loop::run, new CommandExecutor(workspaceRoot), config), outcomeRecorder);
        return brownfield::run;
    }

    /**
     * Composes an interactive REPL turn's greenfield run path ({@code --mode greenfield}, ADR-0012,
     * T-3.1): the greenfield phase state machine over phase-scoped agent loops (the pre-approval
     * phases withhold the Class-X source tools, AC-1.4), returned as the {@code LoopOutcome
     * run(String)} seam {@link ReplRunner} drives per turn.
     *
     * <p><b>Timestamped approval gate (T-3.2).</b> The driver is built via
     * {@link AgentLoopFactory#createGreenfieldDriver}, which wires the {@code ArtifactApprovalGate}:
     * when the developer confirms a phase, the gate records the approval timestamp into that phase's
     * artifact (AC-1.5) and, before admitting the session into implementation, enforces that every
     * task in the task-breakdown artifact traces to a requirement (AC-2.5). The artifact-authoring
     * write path ({@code write_artifact}, confined to the target repo's design-doc directory) is
     * offered to the model in the pre-approval phases (AC-1.2/AC-2.1), distinct from the withheld
     * source-write tools (AC-1.4).
     *
     * <p><b>The per-phase approval decision the gate wraps (T-3.1-RD-D6).</b> The REPL has a live
     * developer terminal, so the decision the gate wraps is an {@link InteractiveGreenfieldApproval}:
     * at each completed phase it presents that phase's deliverable (the requirements/design/tasks
     * artifact, AC-1.5 present-before-confirm) and reads the developer's yes/no from the SAME
     * {@code answerSource} the {@link InteractiveApprover} reads, then approves on an affirmative and
     * declines (fail-closed) on anything else — a blank line, an unrecognized answer, or EOF/Ctrl-D
     * (AC-1.4, mirroring {@link NonInteractiveApprover}). On approval the gate records the AC-1.5
     * timestamp and (at the tasks gate) enforces traceability, and the driver advances toward
     * implementation (AC-2.3); on a decline the session pauses awaiting approval without writing
     * source (AC-1.4). The load-bearing present-then-decide logic lives in the coverage-counted
     * {@link InteractiveGreenfieldApproval}; this excluded launcher only threads the REPL's input
     * stream, its output stream, and the target-repo artifact store into it.
     *
     * @param answerSource the REPL's developer-input supplier (the same one the {@link InteractiveApprover}
     *                     and the read-eval loop read) the per-phase y/N is collected from.
     */
    private static ReplRunner.ReplLoop interactiveGreenfield(ResolvedConfig config,
            Path workspaceRoot, String lineage, EventLog log, Approver approver,
            SessionStore sessions, Supplier<String> answerSource) {
        ArtifactApprovalGate.ApprovalDecision decision = new InteractiveGreenfieldApproval(
                answerSource, System.out, new GreenfieldArtifactStore(workspaceRoot));
        // DCR-2 multi-turn dialogue: the per-turn developer-input source is the SAME REPL supplier
        // the approval decision and the read-eval loop read — the developer types each refining turn
        // at the same prompt. A non-approve answer keeps the phase conversation going (the driver
        // reads the next refining turn here); end-of-input pauses the phase awaiting approval.
        // DCR-3/AC-7.6: the driver (built by the factory) re-derives the resumable phase-state from
        // the target repo's stamped artifacts at the start of each REPL turn, keyed to the project by
        // the real AC-7.3 repo key (lineage) — so a transient mid-phase failure on a prior turn is
        // re-entered (retry-in-place) rather than restarting at requirements.
        GreenfieldDriver.DeveloperTurnSource turnSource = phase -> answerSource.get();
        GreenfieldDriver driver = new AgentLoopFactory().createGreenfieldDriver(
                config, workspaceRoot, lineage, log, approver, sessions, decision,
                turnSource);
        GreenfieldRunner greenfield = new GreenfieldRunner(driver);
        return greenfield::run;
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
     * Resolves the real AC-7.3 repo key for the current target project (DCR-3, ADR-0005): the git
     * remote URL when the working directory has one, else its normalized absolute path. This is the
     * lineage / repo key every run path scopes its session log, grant store, and resumable greenfield
     * phase-state to &mdash; replacing the fixed {@code "one-shot"} M0 placeholder so distinct runs
     * over distinct target projects no longer collide under one key. The key-derivation logic lives
     * in the coverage-counted {@link RepoKeyResolver}; this excluded bootstrap only supplies the
     * git-remote subprocess lookup.
     */
    private static String repoKey() {
        Path workspaceRoot = Path.of("").toAbsolutePath();
        return new RepoKeyResolver(Main::gitRemoteUrl).resolve(workspaceRoot);
    }

    /**
     * The production git-remote-URL lookup (the AC-7.3 external dependency): runs
     * {@code git -C <dir> remote get-url origin} in the target working directory and returns the
     * remote URL when the directory is a git repo with an {@code origin} remote, else
     * {@link Optional#empty()} so {@link RepoKeyResolver} falls back to the normalized absolute path.
     * Living in this coverage-excluded shell keeps the subprocess out of the coverage-counted
     * {@link RepoKeyResolver} keying logic.
     */
    private static Optional<String> gitRemoteUrl(Path workspaceRoot) {
        try {
            Process process = new ProcessBuilder(
                    "git", "-C", workspaceRoot.toString(), "remote", "get-url", "origin")
                    .redirectErrorStream(false)
                    .start();
            String url;
            try (BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                url = stdout.readLine();
            }
            int exit = process.waitFor();
            if (exit == 0 && url != null && !url.isBlank()) {
                return Optional.of(url.strip());
            }
            return Optional.empty();
        } catch (IOException e) {
            LOGGER.warn("could not look up git remote for {}; falling back to path key (AC-7.3)",
                    workspaceRoot, e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("interrupted looking up git remote for {}; falling back to path key (AC-7.3)",
                    workspaceRoot, e);
            return Optional.empty();
        }
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
