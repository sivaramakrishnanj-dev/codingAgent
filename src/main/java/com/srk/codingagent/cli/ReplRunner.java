package com.srk.codingagent.cli;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.model.converse.ModelBackendException;
import com.srk.codingagent.model.credentials.CredentialResolutionException;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.StopReason;
import java.io.PrintStream;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the interactive REPL ({@code codingagent} with no {@code -p}, 04-apis § 1.1): a
 * read-eval loop that reads a developer line, runs it as either an in-REPL slash-command or a
 * prompt turn through the agent loop, streams the assistant's answer back, and repeats until
 * the session ends. This is the testable seam {@link Main} delegates the interactive path to —
 * the loop, streams, and interrupt signal are all injected, so the loop's logic is exercised
 * with a real flow over a scripted Bedrock double (no live AWS) and without a live terminal,
 * mirroring {@link OneShotRunner}'s injected-seams discipline.
 *
 * <p><b>Slash-commands (04-apis § 1.4; T-1.1 scope = {@code /exit}, {@code /mode},
 * {@code /permission}).</b>
 * <ul>
 *   <li>{@code /exit} — ends the session cleanly; the runner returns {@link ExitCode#OK}
 *       ({@code 0}) (exit-code contract {@code 0}: "interactive session exited cleanly").</li>
 *   <li>{@code /mode} — shows the current permission mode (US-8/9). Adjusting the mode
 *       mid-session is a later seam: the gate is built with a fixed mode at factory time, so
 *       v1's {@code /mode} is show-only.</li>
 *   <li>{@code /permission} — shows the current permission mode (US-8/9), the authorization
 *       posture inline approvals are evaluated under; show-only for the same reason.</li>
 *   <li>{@code /attach <path>} — resolves a multimodal attachment (T-4.2, § 2.3 multimodal
 *       input) through the injected {@link AttachmentResolver}: it infers the format from the
 *       extension, sanitizes a document name (INV-18), and applies the capability gate (INV-19).
 *       A supported, capability-admitted attachment is reported as attached; an unsupported file
 *       type, or a model that does not accept that input, is <em>declined with a message, not
 *       sent</em> (INV-19 graceful degradation). A {@code /attach} with no path is reported as a
 *       usage error. The loop continues either way.</li>
 *   <li>any other {@code /command} — reported as unrecognized (04-apis § 1.4's later commands
 *       {@code /compact} / {@code /remember} / {@code /model} map to M2/M4), never silently
 *       ignored. The loop continues.</li>
 * </ul>
 *
 * <p><b>End-of-session (exit-code contract {@code 0}, guarantee G3).</b> Both {@code /exit}
 * and end-of-input (Ctrl-D / EOF — the line source returns {@code null}) end the session
 * cleanly with exit {@code 0}.
 *
 * <p><b>SIGINT → {@code 130} (02-architecture § 4, CT-EX-4/5, CT-SM-4).</b> A Ctrl-C during a
 * step interrupts the current step and ends the session with {@link ExitCode#INTERRUPTED}
 * ({@code 130}); by the precedence rule (cli-exit-codes § 2: "{@code 130} SIGINT always wins")
 * this beats a model-backend or any other failure that may also be in flight. The interrupt is
 * observed two ways, reconciled to one path: the injected {@code interrupted} flag (set by the
 * real OS signal handler {@link Main} installs, which also interrupts this thread to cancel the
 * in-flight step) and an {@link InterruptedRunException} thrown out of an interrupted step.
 * Because the event log is flushed per event (NFR-LOG-DURABILITY), the session remains
 * resumable across the interrupt with at most the in-flight event lost.
 *
 * <p><b>A failed turn does not kill the REPL.</b> Unlike the one-shot path (where a surfaced
 * edge condition or a backend error is the run's terminal exit code), the interactive loop
 * reports a turn's failure and keeps the session alive so the developer can try another prompt
 * — except a SIGINT, which ends the session ({@code 130}), and a fatal persistence failure
 * ({@link PersistenceException}: the log can no longer be durably written), which ends the
 * session with {@link ExitCode#INTERNAL} ({@code 1}) because continuing would silently drop
 * events (AC-13.4).
 *
 * <p>Single-threaded by construction (the C2 invariant — one in-flight model call per
 * conversation): the runner drives one {@code loop.run(prompt)} turn at a time, never
 * concurrently. The runner never calls {@link System#exit(int)}; it returns the code so
 * {@link Main} can return it.
 */
public final class ReplRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReplRunner.class);

    /** Prefix on every user-facing line the CLI writes (matches the one-shot runner). */
    private static final String CLI_PREFIX = "codingagent: ";

    /** The prompt marker shown before each developer line. */
    private static final String PROMPT_MARKER = "> ";

    /** The {@code /attach} slash-command prefix (T-4.2, § 2.3 multimodal input). */
    private static final String ATTACH_COMMAND = "/attach";

    private final ReplLoop loop;
    private final Supplier<String> lineSource;
    private final BooleanSupplier interrupted;
    private final PermissionMode permissionMode;
    private final AttachmentResolver attachmentResolver;
    private final PrintStream out;
    private final PrintStream err;

    /**
     * Creates a REPL runner over an agent-loop seam, an input line source, an interrupt
     * signal, the attachment resolver, and the user-facing streams.
     *
     * @param loop               the agent-loop turn seam
     *                           ({@link com.srk.codingagent.loop.AgentLoop#run(String)} shape);
     *                           must not be {@code null}.
     * @param lineSource         the source of developer input lines; each call returns the next
     *                           line, or {@code null} at end-of-input (Ctrl-D / EOF). Must not be
     *                           {@code null}.
     * @param interrupted        the interrupt signal: returns {@code true} once a SIGINT has been
     *                           observed (set by {@link Main}'s OS signal handler). Polled before
     *                           reading each line and after each step so a Ctrl-C ends the session
     *                           with exit {@code 130}. Must not be {@code null}.
     * @param permissionMode     the current authorization mode {@code /mode} / {@code /permission}
     *                           report (US-8/9); must not be {@code null}.
     * @param attachmentResolver the C1 attachment pipeline {@code /attach <path>} resolves through
     *                           (format inference, INV-18 sanitization, INV-19 capability gate);
     *                           must not be {@code null}.
     * @param out                the stream assistant output and command responses are written to
     *                           (the REPL owns user-facing output, 04-apis § 1.6); must not be
     *                           {@code null}.
     * @param err                the stream a turn's error line is written to (G2); must not be
     *                           {@code null}.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public ReplRunner(ReplLoop loop, Supplier<String> lineSource, BooleanSupplier interrupted,
            PermissionMode permissionMode, AttachmentResolver attachmentResolver,
            PrintStream out, PrintStream err) {
        this.loop = Objects.requireNonNull(loop, "loop");
        this.lineSource = Objects.requireNonNull(lineSource, "lineSource");
        this.interrupted = Objects.requireNonNull(interrupted, "interrupted");
        this.permissionMode = Objects.requireNonNull(permissionMode, "permissionMode");
        this.attachmentResolver = Objects.requireNonNull(attachmentResolver, "attachmentResolver");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    /**
     * Runs the interactive loop to its terminal condition and returns the process exit code.
     *
     * <p>Never throws and never calls {@link System#exit(int)}: every terminal condition is
     * mapped to a code so {@link Main} can return it. The loop ends on {@code /exit} or EOF
     * (exit {@code 0}), on a SIGINT (exit {@code 130}), or on a fatal persistence failure
     * (exit {@code 1}); otherwise it keeps reading prompts.
     *
     * @return the agent-process exit code for the session.
     */
    public int run() {
        out.println("codingagent interactive session. Type a prompt, or /exit to quit.");
        while (true) {
            if (interrupted.getAsBoolean()) {
                // SIGINT observed before the next read: end the session (cli-exit-codes 130).
                return interrupt();
            }
            out.print(PROMPT_MARKER);
            String line = lineSource.get();
            if (line == null) {
                // EOF / Ctrl-D: a clean end-of-session (exit-code contract 0, G3).
                LOGGER.info("Interactive session ended by end-of-input (exit 0)");
                out.println();
                return ExitCode.OK.code();
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("/")) {
                Integer terminal = handleCommand(trimmed);
                if (terminal != null) {
                    return terminal;
                }
                continue;
            }
            Integer terminal = runTurn(trimmed);
            if (terminal != null) {
                return terminal;
            }
        }
    }

    /**
     * Handles an in-REPL slash-command. Returns a terminal exit code for {@code /exit},
     * otherwise {@code null} (the loop continues after showing the command's response).
     */
    private Integer handleCommand(String command) {
        // /attach carries a path argument, so it is matched by prefix before the exact-match
        // switch (the others are bare words).
        if (command.equals(ATTACH_COMMAND) || command.startsWith(ATTACH_COMMAND + " ")) {
            handleAttach(command);
            return null;
        }
        switch (command) {
            case "/exit" -> {
                LOGGER.info("Interactive session ended by /exit (exit 0)");
                out.println("Goodbye.");
                return ExitCode.OK.code();
            }
            case "/mode" -> out.println("mode: " + permissionMode);
            case "/permission" -> out.println("permission mode: " + permissionMode);
            default -> {
                // Unrecognized slash-command: report, never silently ignore (04-apis § 1.4).
                LOGGER.info("Unrecognized in-REPL command: {}", command);
                out.println(CLI_PREFIX + "unknown command: " + command);
            }
        }
        return null;
    }

    /**
     * Handles {@code /attach <path>} (T-4.2, § 2.3 multimodal input): resolves the path through
     * the {@link AttachmentResolver} (format inference + INV-18 sanitization + INV-19 capability
     * gate) and reports the outcome. An admitted attachment is confirmed; a declined attachment
     * (unsupported file type, or a model that does not accept that input, INV-19) reports the
     * decline reason and is not sent. A missing path is a usage error. The loop continues either
     * way (a slash-command never ends the session except {@code /exit}).
     */
    private void handleAttach(String command) {
        String path = command.length() > ATTACH_COMMAND.length()
                ? command.substring(ATTACH_COMMAND.length()).strip()
                : "";
        if (path.isEmpty()) {
            LOGGER.info("/attach given with no path");
            out.println(CLI_PREFIX + "usage: /attach <path>");
            return;
        }
        Attachment attachment = attachmentResolver.resolve(path);
        if (attachment instanceof Attachment.Declined declined) {
            // INV-19 (or unsupported type): declined with a message, not sent.
            LOGGER.info("Attachment declined: {}", declined.message());
            out.println(CLI_PREFIX + declined.message());
            return;
        }
        LOGGER.info("Attachment accepted for the next turn: {}", path);
        out.println(CLI_PREFIX + "attached: " + path);
    }

    /**
     * Runs one prompt as an agent-loop turn, streaming the result. Returns a terminal exit
     * code only when the turn ends the session (a SIGINT → {@code 130}, or a fatal
     * persistence failure → {@code 1}); otherwise reports the outcome and returns {@code null}
     * so the loop keeps the session alive for the next prompt.
     */
    private Integer runTurn(String prompt) {
        try {
            LoopOutcome outcome = loop.run(prompt);
            renderOutcome(outcome);
            // A SIGINT may have arrived during the step (the handler interrupts this thread);
            // observe it after the turn so the session ends with 130 (cli-exit-codes 130).
            if (interrupted.getAsBoolean()) {
                return interrupt();
            }
            return null;
        } catch (InterruptedRunException sigint) {
            // The in-flight step was cancelled by a SIGINT (cli-exit-codes § 2: 130 wins).
            return interrupt(sigint);
        } catch (PersistenceException persistence) {
            // The event log can no longer be durably written; continuing would silently drop
            // events (AC-13.4). End the session with exit 1 rather than run blind.
            LOGGER.error("Interactive session ending: event could not be persisted", persistence);
            err.println(CLI_PREFIX + persistence.getMessage());
            return ExitCode.INTERNAL.code();
        } catch (CredentialResolutionException | ModelBackendException backend) {
            // A model-backend failure is reported, but the session stays alive: the developer
            // can fix credentials / retry. Unlike one-shot, the REPL does not exit on it.
            LOGGER.warn("Turn failed (model backend); session continues", backend);
            err.println(CLI_PREFIX + backend.getMessage());
            return null;
        } catch (UserAbortedException aborted) {
            // A denial blocked this operation (AC-10.2); the turn is abandoned but the session
            // continues so the developer can choose a different next step.
            LOGGER.info("Turn aborted by a blocking denial; session continues: {}",
                    aborted.getMessage());
            err.println(CLI_PREFIX + aborted.getMessage());
            return null;
        } catch (RuntimeException unexpected) {
            // An unexpected fault on a single turn is reported; the session stays alive.
            LOGGER.error("Turn failed unexpectedly; session continues", unexpected);
            err.println(CLI_PREFIX + "turn failed: " + unexpected.getMessage());
            return null;
        }
    }

    /** Renders a completed turn's final text, or reports a surfaced edge condition. */
    private void renderOutcome(LoopOutcome outcome) {
        if (outcome.completed()) {
            outcome.finalTextIfPresent().ifPresent(out::println);
            return;
        }
        // A surfaced edge reason is not fatal to the session (no compaction/repair at M0):
        // report it and let the developer try another prompt.
        StopReason reason = outcome.stopReason();
        LOGGER.info("Turn surfaced an edge stop reason without completing: {}", reason);
        err.println(CLI_PREFIX + "turn did not complete: " + reason);
    }

    /** Ends the session on an observed interrupt flag, returning exit {@code 130}. */
    private int interrupt() {
        Thread.currentThread().interrupt();
        LOGGER.info("Interactive session interrupted by SIGINT; exiting {} (session resumable)",
                ExitCode.INTERRUPTED.code());
        err.println(CLI_PREFIX + "interrupted");
        return ExitCode.INTERRUPTED.code();
    }

    /** Ends the session on a caught interrupt, returning exit {@code 130}. */
    private int interrupt(InterruptedRunException sigint) {
        Thread.currentThread().interrupt();
        LOGGER.info("Interactive session interrupted by SIGINT ({}); exiting {} (session resumable)",
                sigint.getMessage(), ExitCode.INTERRUPTED.code());
        err.println(CLI_PREFIX + "interrupted");
        return ExitCode.INTERRUPTED.code();
    }

    /**
     * The agent-loop turn seam: the {@link com.srk.codingagent.loop.AgentLoop#run(String)}
     * shape, isolated so the REPL's read-eval logic is testable with a real loop over a
     * scripted Bedrock double, or with a loop substitute that throws to exercise a branch.
     */
    @FunctionalInterface
    public interface ReplLoop {

        /**
         * Runs the agent loop for one prompt to its terminal {@link LoopOutcome}.
         *
         * @param prompt the developer's prompt for this turn; non-blank.
         * @return the terminal outcome; never {@code null}.
         */
        LoopOutcome run(String prompt);
    }
}
