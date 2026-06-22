package com.srk.codingagent.cli;

import com.srk.codingagent.config.ConfigException;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.model.converse.ModelBackendException;
import com.srk.codingagent.model.credentials.CredentialResolutionException;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.StopReason;
import java.io.PrintStream;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a one-shot ({@code -p}) prompt through the agent loop and maps the terminal
 * {@link LoopOutcome} — or any failure thrown along the way — onto the agent-process
 * {@link ExitCode}, honouring the exit-code contract's precedence
 * ({@code 06-formal/cli-exit-codes.md} § 2).
 *
 * <p>This is the testable seam {@link Main} delegates the one-shot path to. The agent
 * loop is injected as a {@link OneShotLoop} so the run-and-map logic is exercised with a
 * real loop driven by a scripted Bedrock double (no live AWS call), and the
 * exception-mapping branches are exercised with a loop that throws — the system under
 * test (the run orchestration and the exit-code mapping) is never mocked, only Bedrock is.
 *
 * <p><b>Precedence (cli-exit-codes § 2, first match wins).</b> The run distinguishes the
 * terminal conditions in this order:
 * <ol>
 *   <li><b>{@code 130} interrupted</b> — a SIGINT (modelled by {@link InterruptedRunException})
 *       always wins.</li>
 *   <li><b>{@code 4} model-backend</b> — {@link CredentialResolutionException} (no usable
 *       SigV4 credentials, AC-8.9) or {@link ModelBackendException} (Converse failed
 *       unrecoverably, § 3.2).</li>
 *   <li><b>{@code 5} context-exhausted</b> — a surfaced
 *       {@link StopReason#MODEL_CONTEXT_WINDOW_EXCEEDED} with no compaction available at
 *       M0 (compaction is T-2.1/T-2.2).</li>
 *   <li><b>{@code 3} user-aborted</b> — a blocking denial during the run
 *       ({@link UserAbortedException}, AC-10.2).</li>
 *   <li><b>{@code 1} internal</b> — an event could not be persisted
 *       ({@link PersistenceException}, AC-13.4), or any other unhandled fault (catch-all).</li>
 *   <li><b>{@code 0} success</b> — a completed outcome ({@code end_turn}), CT-EX-6.</li>
 * </ol>
 * The startup-ordered {@code 2} usage/config code is handled in {@link Main} <em>before</em>
 * this runner is reached (config resolves and fails fast before any model call, ADR-0009),
 * which is exactly the contract's placement of {@code 2} ahead of {@code 4} but behind the
 * model-call boundary.
 *
 * <p>Every non-zero exit prints one human-readable stderr line naming the cause (guarantee
 * G2). The runner owns this user-facing output; the library modules it drives never write
 * to stdout/stderr themselves (NFR-LOG library/CLI split, 04-apis § 1.6).
 */
public final class OneShotRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(OneShotRunner.class);

    /** Prefix on every user-facing line the CLI writes (matches the config-error line). */
    private static final String CLI_PREFIX = "codingagent: ";

    private final OneShotLoop loop;
    private final PrintStream out;
    private final PrintStream err;

    /**
     * Creates a runner over an agent loop, writing the final answer and any error line to
     * the given streams.
     *
     * @param loop the agent-loop run seam; must not be {@code null}.
     * @param out  the stream the final assistant answer is written to (the result of a
     *             successful one-shot); must not be {@code null}.
     * @param err  the stream a non-zero exit's cause line is written to (G2); must not be
     *             {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public OneShotRunner(OneShotLoop loop, PrintStream out, PrintStream err) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Runs the prompt to its terminal condition and returns the process exit code.
     *
     * <p>Never throws and never calls {@link System#exit(int)}: every terminal condition,
     * including an unexpected fault, is mapped to a code so {@link Main} can return it and
     * the caller can branch (G3). The exception-to-code mapping follows the precedence in
     * the class Javadoc.
     *
     * @param prompt the one-shot prompt to run; must not be {@code null} or blank.
     * @return the agent-process exit code for the run.
     * @throws NullPointerException     if {@code prompt} is {@code null}.
     * @throws IllegalArgumentException if {@code prompt} is blank (a bad invocation the
     *                                  caller should have rejected as a usage error).
     */
    public int run(String prompt) {
        if (Objects.requireNonNull(prompt, "prompt").isBlank()) {
            throw new IllegalArgumentException("prompt must be non-blank");
        }
        try {
            LoopOutcome outcome = loop.run(prompt);
            return mapOutcome(outcome);
        } catch (InterruptedRunException interrupted) {
            // Precedence: 130 SIGINT always wins (cli-exit-codes § 2).
            Thread.currentThread().interrupt();
            return fail(ExitCode.INTERRUPTED, "interrupted", interrupted);
        } catch (CredentialResolutionException noCredentials) {
            // exit 4: no usable SigV4 credentials (AC-8.9); the message names the paths.
            return fail(ExitCode.MODEL_BACKEND, noCredentials.getMessage(), noCredentials);
        } catch (ModelBackendException backend) {
            // exit 4: Bedrock could not be used (§ 3.2 — validation/auth/retries exhausted).
            return fail(ExitCode.MODEL_BACKEND, backend.getMessage(), backend);
        } catch (UserAbortedException aborted) {
            // exit 3: a blocking required-action denial (AC-10.2, CT-EX-3).
            return fail(ExitCode.USER_ABORTED, aborted.getMessage(), aborted);
        } catch (PersistenceException persistence) {
            // exit 1: an event could not be persisted (AC-13.4) — don't pretend it logged.
            return fail(ExitCode.INTERNAL, persistence.getMessage(), persistence);
        } catch (RuntimeException unexpected) {
            // exit 1: unhandled internal error (catch-all, § 3.2).
            return fail(ExitCode.INTERNAL, "internal error: " + unexpected.getMessage(), unexpected);
        }
    }

    /** Maps a (non-thrown) terminal outcome to a code per the contract precedence. */
    private int mapOutcome(LoopOutcome outcome) {
        if (outcome.completed()) {
            // CT-EX-6 / exit 0: a successful one-shot returns the final answer and exits 0.
            outcome.finalTextIfPresent().ifPresent(out::println);
            LOGGER.info("One-shot run completed (end_turn); exiting {}", ExitCode.OK.code());
            return ExitCode.OK.code();
        }
        return mapSurfaced(outcome.stopReason());
    }

    /**
     * Maps a surfaced edge stop reason to an exit code (cli-exit-codes). At M0 there is no
     * compaction and no bounded repair-retry, so a surfaced reason is a non-zero terminal
     * condition: {@code model_context_window_exceeded} maps toward {@code 5}
     * context-exhausted (the compaction path could not recover — it does not exist yet);
     * every other surfaced reason (max_tokens, guardrail_intervened, content_filtered,
     * malformed_*) maps to the {@code 1} internal catch-all, since the contract pins no
     * other code for them.
     */
    private int mapSurfaced(StopReason stopReason) {
        if (stopReason == StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED) {
            return fail(ExitCode.CONTEXT_EXHAUSTED,
                    "context window exceeded and compaction is unavailable", null);
        }
        return fail(ExitCode.INTERNAL,
                "run surfaced without completing: stopReason=" + stopReason, null);
    }

    /** Logs once, prints the G2 stderr cause line, and returns the code. */
    private int fail(ExitCode code, String cause, Throwable thrown) {
        if (thrown != null) {
            LOGGER.error("One-shot run failed ({}): {}", code, cause, thrown);
        } else {
            LOGGER.error("One-shot run failed ({}): {}", code, cause);
        }
        err.println(CLI_PREFIX + cause);
        return code.code();
    }

    /**
     * The agent-loop run seam: the {@link com.srk.codingagent.loop.AgentLoop#run(String)}
     * shape, isolated so the one-shot run-and-map logic is testable with a real loop over a
     * scripted Bedrock double, or with a loop substitute that throws to exercise an
     * exit-code branch.
     */
    @FunctionalInterface
    public interface OneShotLoop {

        /**
         * Runs the agent loop from the prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the one-shot prompt; non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }
}
