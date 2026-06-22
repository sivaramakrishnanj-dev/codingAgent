package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.model.converse.ModelBackendException;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.model.credentials.CredentialResolutionException;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PersistenceException;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Unit tests for {@link ReplRunner} — the interactive read-eval loop (04-apis § 1.1/§ 1.4).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link ReplRunner}. Its collaborators are
 * the injected terminal boundary (the line source, the captured {@code out}/{@code err}
 * streams, the interrupt flag) and the agent-loop turn seam ({@link ReplRunner.ReplLoop}). For
 * the render/end-to-end test the loop is a <em>real</em> {@link AgentLoop} over a hand-rolled
 * scripted {@link BedrockRuntimeClient} double — the only external dependency, so the turn is
 * exercised with no live AWS call. For the branch tests the loop seam is a small substitute that
 * returns a {@link LoopOutcome} or throws the typed exception the contract names; that substitute
 * is the boundary, not the SUT.
 *
 * <p><b>Oracles.</b> Expected behaviour traces to the cited spec symbols, never to the runner's
 * code:
 * <ul>
 *   <li><b>04-apis § 1.4 / CT-EX-6 / exit-code contract {@code 0}:</b> {@code /exit} ends the
 *       session cleanly → exit {@code 0}.</li>
 *   <li><b>exit-code contract {@code 0} ("interactive session exited cleanly ... EOF") / G3:</b>
 *       end-of-input (Ctrl-D) → exit {@code 0}.</li>
 *   <li><b>CT-EX-4 / 02-architecture § 4 / exit-code contract {@code 130}:</b> a SIGINT during
 *       the session → exit {@code 130}; the session remains resumable.</li>
 *   <li><b>CT-EX-5 / cli-exit-codes § 2:</b> a SIGINT in flight with a backend failure → exit
 *       {@code 130} (not {@code 4}); {@code 130} always wins.</li>
 *   <li><b>04-apis § 1.4:</b> {@code /mode} and {@code /permission} show the current setting; an
 *       unrecognized {@code /command} is reported, not silently ignored.</li>
 *   <li><b>AC-10.1 (inline approval):</b> a prompt turn whose tool requires approval reaches the
 *       interactive approver, which presents the exact operation before the decision.</li>
 * </ul>
 */
class ReplRunnerTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String TS = "2026-06-22T09:00:00Z";

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @AfterEach
    void clearInterruptFlag() {
        // A 130-path test re-asserts the thread's interrupt status; clear it so it does not
        // leak into the next test on the shared runner thread (test isolation).
        Thread.interrupted();
    }

    private String stdout() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** A line source that replays typed lines, then end-of-input (null) forever. */
    private static Supplier<String> lines(String... lines) {
        Deque<String> queue = new ArrayDeque<>(List.of(lines));
        return () -> queue.isEmpty() ? null : queue.removeFirst();
    }

    private static final BooleanSupplier NEVER_INTERRUPTED = () -> false;

    private ReplRunner runner(ReplRunner.ReplLoop loop, Supplier<String> lineSource,
            BooleanSupplier interrupted) {
        return new ReplRunner(loop, lineSource, interrupted, PermissionMode.ASK_EVERY_TIME,
                out, err);
    }

    // --- /exit and EOF : clean exit 0 -----------------------------------------------

    @Test
    @DisplayName("04-apis § 1.4 / CT-EX-6: /exit ends the session cleanly with exit 0")
    void slashExitExitsZero() {
        // Oracle: 04-apis § 1.4 "/exit ... end session cleanly (exit 0)"; CT-EX-6 "clean /exit
        // ... → exit 0". The loop seam must never be called for a slash-command.
        AtomicInteger turns = new AtomicInteger(0);
        ReplRunner.ReplLoop loop = prompt -> {
            turns.incrementAndGet();
            return LoopOutcome.completed("unused");
        };
        ReplRunner repl = runner(loop, lines("/exit"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "CT-EX-6: a clean /exit exits 0 (exit-code contract: 0)");
        assertEquals(0, turns.get(), "a slash-command never runs an agent-loop turn");
    }

    @Test
    @DisplayName("exit-code contract 0 / G3: end-of-input (Ctrl-D / EOF) ends the session with exit 0")
    void endOfInputExitsZero() {
        // Oracle: exit-code contract "0 success — interactive session exited cleanly (/exit,
        // EOF)"; G3 "Interactive mode returns 0 on clean /exit". The line source returns null
        // immediately (an empty stdin / closed pipe), which is a clean end-of-session.
        ReplRunner repl = runner(prompt -> LoopOutcome.completed("x"), () -> null,
                NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode,
                "exit-code contract: end-of-input exits the interactive session cleanly (0)");
    }

    @Test
    @DisplayName("04-apis § 1.1: blank input lines are skipped, then EOF exits 0")
    void blankLinesAreSkipped() {
        // Oracle: 04-apis § 1.1 — the REPL is a prompt loop; an empty line is not a prompt, so it
        // is re-prompted (no turn run) and the session ends cleanly at EOF.
        AtomicInteger turns = new AtomicInteger(0);
        ReplRunner repl = runner(prompt -> {
            turns.incrementAndGet();
            return LoopOutcome.completed("x");
        }, lines("", "   "), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "a session of only blank lines then EOF exits 0");
        assertEquals(0, turns.get(), "blank lines do not run an agent-loop turn");
    }

    // --- a prompt turn runs the loop and renders the answer -------------------------

    @Test
    @DisplayName("04-apis § 1.1: a prompt line runs an agent-loop turn and the answer is rendered")
    void promptRunsTurnAndRendersAnswer() {
        // Oracle: 04-apis § 1.1 "enters a prompt loop"; § 1.6 "streamed assistant text ... the
        // CLI layer owns user-facing output". A typed prompt drives one loop turn whose final
        // text is written to stdout; the session then continues (next line) to a clean EOF exit.
        List<String> prompts = new ArrayList<>();
        ReplRunner.ReplLoop loop = prompt -> {
            prompts.add(prompt);
            return LoopOutcome.completed("The answer is 42.");
        };
        ReplRunner repl = runner(loop, lines("what is the answer?"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "a normal turn then EOF exits 0");
        assertEquals(List.of("what is the answer?"), prompts,
                "the typed line is run as the turn's prompt");
        assertTrue(stdout().contains("The answer is 42."),
                "04-apis § 1.6: the assistant's final text is written to stdout; was: " + stdout());
    }

    @Test
    @DisplayName("04-apis § 1.1: multiple prompts run as successive turns in one session")
    void multiplePromptsRunSuccessiveTurns() {
        // Oracle: 04-apis § 1.1 — interactive mode is "the default; multi-turn work": the loop
        // accepts more than one prompt in a session, running each as its own turn, until EOF.
        List<String> prompts = new ArrayList<>();
        ReplRunner.ReplLoop loop = prompt -> {
            prompts.add(prompt);
            return LoopOutcome.completed("ok:" + prompt);
        };
        ReplRunner repl = runner(loop, lines("first", "second"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "a multi-prompt session ends cleanly at EOF");
        assertEquals(List.of("first", "second"), prompts,
                "each typed line runs as its own turn (multi-turn session)");
    }

    @Test
    @DisplayName("a turn that surfaces an edge stop reason reports it and keeps the session alive")
    void surfacedTurnReportsAndContinues() {
        // Oracle: 04-apis § 1.1 (prompt loop) — a surfaced edge condition is not a fatal exit in
        // interactive mode (no compaction/repair at M0): it is reported and the developer can try
        // again. The next line is EOF, which exits 0 — proving the session did not die on the
        // surfaced turn.
        Deque<LoopOutcome> outcomes = new ArrayDeque<>(List.of(
                LoopOutcome.surfaced(StopReason.MAX_TOKENS),
                LoopOutcome.completed("recovered")));
        ReplRunner.ReplLoop loop = prompt -> outcomes.removeFirst();
        ReplRunner repl = runner(loop, lines("too long", "shorter"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode,
                "a surfaced turn does not end the interactive session; a later clean EOF exits 0");
        assertTrue(stderr().contains(StopReason.MAX_TOKENS.toString()),
                "the surfaced reason is reported to the developer; was: " + stderr());
        assertTrue(stdout().contains("recovered"),
                "the session stayed alive and ran the next prompt; was: " + stdout());
    }

    // --- slash-commands : /mode, /permission, unrecognized --------------------------

    @Test
    @DisplayName("04-apis § 1.4: /mode shows the current permission mode")
    void slashModeShowsCurrentMode() {
        // Oracle: 04-apis § 1.4 "/mode ... show ... current settings" (US-8/9). The configured
        // mode is reported; the session continues to a clean EOF exit.
        ReplRunner repl = new ReplRunner(prompt -> LoopOutcome.completed("x"),
                lines("/mode"), NEVER_INTERRUPTED, PermissionMode.READ_ONLY, out, err);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "after showing the mode the session ends cleanly at EOF");
        assertTrue(stdout().contains(PermissionMode.READ_ONLY.toString()),
                "04-apis § 1.4: /mode shows the current permission mode; was: " + stdout());
    }

    @Test
    @DisplayName("04-apis § 1.4: /permission shows the current permission mode")
    void slashPermissionShowsCurrentMode() {
        // Oracle: 04-apis § 1.4 "/permission ... show ... current settings" (US-8/9).
        ReplRunner repl = new ReplRunner(prompt -> LoopOutcome.completed("x"),
                lines("/permission"), NEVER_INTERRUPTED, PermissionMode.ASK_ONCE_THEN_REMEMBER,
                out, err);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "after showing the permission mode the session ends at EOF");
        assertTrue(stdout().contains(PermissionMode.ASK_ONCE_THEN_REMEMBER.toString()),
                "04-apis § 1.4: /permission shows the current mode; was: " + stdout());
    }

    @Test
    @DisplayName("04-apis § 1.4 scope note: an unrecognized slash-command is reported, not ignored")
    void unrecognizedCommandIsReported() {
        // Oracle: T-1.1 scope note on 04-apis § 1.4 — "An unrecognized slash-command should be
        // reported, not silently ignored." A later-milestone command (/compact is M2) is NOT in
        // T-1.1 scope, so it must be reported as unknown and NOT run as a prompt.
        AtomicInteger turns = new AtomicInteger(0);
        ReplRunner repl = runner(prompt -> {
            turns.incrementAndGet();
            return LoopOutcome.completed("x");
        }, lines("/compact"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "after reporting the unknown command the session ends at EOF");
        assertEquals(0, turns.get(),
                "an unrecognized slash-command is NOT run as a prompt (no agent-loop turn)");
        assertTrue(stdout().contains("/compact"),
                "the unrecognized command is named in the report (not silently ignored); was: "
                        + stdout());
    }

    // --- SIGINT : exit 130, precedence, resumability --------------------------------

    @Test
    @DisplayName("CT-EX-4 / 02-architecture § 4: a SIGINT before a read ends the session with 130")
    void sigintBeforeReadExits130() {
        // Oracle: CT-EX-4 / exit-code contract "130 interrupted (SIGINT)". The interrupt flag is
        // already set when the loop polls before reading the next line — a Ctrl-C arrived — so the
        // session ends with 130. (The loop seam must not be consulted.)
        AtomicInteger turns = new AtomicInteger(0);
        ReplRunner repl = runner(prompt -> {
            turns.incrementAndGet();
            return LoopOutcome.completed("x");
        }, lines("ignored"), () -> true);

        int exitCode = repl.run();

        assertEquals(130, exitCode,
                "CT-EX-4: a SIGINT ends the interactive session with exit 130 (cli-exit-codes 130)");
        assertEquals(0, turns.get(), "no turn runs once the interrupt is observed");
    }

    @Test
    @DisplayName("CT-SM-4: a SIGINT during a step (InterruptedRunException) ends the session with 130")
    void interruptedRunExceptionDuringStepExits130() {
        // Oracle: CT-SM-4 / CT-EX-4 — "SIGINT ... cancels in-flight work, flushes, exits 130". A
        // step interrupted mid-flight surfaces InterruptedRunException (the modelled SIGINT seam
        // OneShotRunner also maps); the REPL maps it to 130, reconciling the real handler with the
        // existing seam.
        ReplRunner repl = runner(prompt -> {
            throw new InterruptedRunException("interrupted while calling Bedrock");
        }, lines("long task"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(130, exitCode,
                "CT-SM-4: a SIGINT during a step ends the session with 130 (cancels in-flight work)");
    }

    @Test
    @DisplayName("CT-EX-5 / cli-exit-codes § 2: a SIGINT during a backend failure exits 130, not 4")
    void interruptPrecedenceOverBackend() {
        // Oracle: CT-EX-5 / cli-exit-codes § 2 "130 SIGINT always wins". When a Ctrl-C arrives as a
        // step was failing at the backend, the interrupt seam is what surfaces and 130 wins over 4
        // — the runner maps the InterruptedRunException before any backend mapping.
        ReplRunner repl = runner(prompt -> {
            throw new InterruptedRunException("interrupted while a 503 was in flight");
        }, lines("task"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(130, exitCode,
                "CT-EX-5: SIGINT during a model-backend failure exits 130 (precedence over 4)");
    }

    @Test
    @DisplayName("CT-EX-4: a SIGINT observed after a completed step still ends the session with 130")
    void sigintAfterStepExits130() {
        // Oracle: CT-EX-4 / 02-architecture § 4 — Ctrl-C interrupts the current step. Model a SIGINT
        // arriving while a step ran to completion: the flag flips during the turn, and the runner
        // observes it after rendering and ends with 130 (the next read is never reached).
        AtomicBoolean flag = new AtomicBoolean(false);
        ReplRunner.ReplLoop loop = prompt -> {
            flag.set(true); // SIGINT arrives during this step
            return LoopOutcome.completed("partial answer");
        };
        ReplRunner repl = runner(loop, lines("a prompt", "never reached"), flag::get);

        int exitCode = repl.run();

        assertEquals(130, exitCode,
                "CT-EX-4: a SIGINT observed after the step ends the session with 130");
    }

    @Test
    @DisplayName("CT-EX-4: mapping a SIGINT to 130 re-asserts the thread's interrupt flag (resumable)")
    void sigintReassertsInterruptStatus() {
        // Oracle: 02-architecture § 4 — the session remains resumable; Effective Java Item 70 —
        // do not swallow the interrupt. Mapping a SIGINT to 130 re-asserts the thread interrupt
        // status so a caller up the stack can still observe it.
        ReplRunner repl = runner(prompt -> {
            throw new InterruptedRunException("interrupted");
        }, lines("task"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(130, exitCode, "the SIGINT maps to 130");
        assertTrue(Thread.interrupted(),
                "the interrupt status is re-asserted (and cleared here for test isolation)");
    }

    // --- non-fatal turn failures keep the session alive; fatal persistence ends it ---

    @Test
    @DisplayName("a model-backend failure on a turn is reported but the session continues")
    void backendFailureOnTurnContinuesSession() {
        // Oracle: 04-apis § 1.1 (interactive is a persistent prompt loop) + AC-10.2 spirit (the
        // agent chooses a next step) — unlike the one-shot run-to-exit path, a backend error on a
        // single interactive turn is reported and the session stays alive so the developer can
        // retry. Proven by the next prompt running and a clean EOF exit (0, not 4).
        Deque<ReplStep> steps = new ArrayDeque<>(List.of(
                ReplStep.throwing(new ModelBackendException("503 ServiceUnavailable",
                        new RuntimeException("retries exhausted"))),
                ReplStep.completing("recovered")));
        ReplRunner.ReplLoop loop = prompt -> steps.removeFirst().apply(prompt);
        ReplRunner repl = runner(loop, lines("flaky", "retry"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(0, exitCode,
                "a backend failure on a turn does not end the interactive session (0 at EOF, not 4)");
        assertTrue(stderr().contains("503"),
                "G2: the failure is reported to the developer; was: " + stderr());
        assertTrue(stdout().contains("recovered"),
                "the session stayed alive to run the next prompt; was: " + stdout());
    }

    @Test
    @DisplayName("a credential failure on a turn is reported but the session continues")
    void credentialFailureOnTurnContinuesSession() {
        // Oracle: 04-apis § 1.1 — the interactive session is persistent. A credential error on a
        // turn is reported and the session stays alive so the developer can fix credentials and
        // retry; it does not terminate the REPL.
        Deque<ReplStep> steps = new ArrayDeque<>(List.of(
                ReplStep.throwing(new CredentialResolutionException("no usable SigV4 credentials")),
                ReplStep.completing("now works")));
        ReplRunner.ReplLoop loop = prompt -> steps.removeFirst().apply(prompt);
        ReplRunner repl = runner(loop, lines("go", "go again"), NEVER_INTERRUPTED);

        assertEquals(0, repl.run(),
                "a credential failure on a turn does not end the session (0 at EOF)");
        assertTrue(stderr().contains("SigV4"),
                "G2: the credential failure is reported; was: " + stderr());
    }

    @Test
    @DisplayName("a blocking denial (UserAbortedException) is reported but the session continues")
    void userAbortOnTurnContinuesSession() {
        // Oracle: AC-10.2 — when an operation is denied the agent "shall ... choose a next step
        // (alternative or stop) without that side effect". In interactive mode the developer
        // chooses the next step: the turn is abandoned, reported, and the session stays alive.
        Deque<ReplStep> steps = new ArrayDeque<>(List.of(
                ReplStep.throwing(new UserAbortedException("denied: run_command: rm -rf /")),
                ReplStep.completing("did something safe")));
        ReplRunner.ReplLoop loop = prompt -> steps.removeFirst().apply(prompt);
        ReplRunner repl = runner(loop, lines("dangerous", "safe"), NEVER_INTERRUPTED);

        assertEquals(0, repl.run(),
                "AC-10.2: a blocking denial abandons the turn but the session continues (0 at EOF)");
        assertTrue(stderr().contains("rm -rf /"),
                "G2: the denied operation is named; was: " + stderr());
    }

    @Test
    @DisplayName("an unexpected fault on a turn is reported but the session continues")
    void unexpectedFaultOnTurnContinuesSession() {
        // Oracle: 04-apis § 1.1 — the interactive session is resilient to a single bad turn. An
        // unexpected runtime fault is reported; the session keeps reading prompts.
        Deque<ReplStep> steps = new ArrayDeque<>(List.of(
                ReplStep.throwing(new IllegalStateException("boom")),
                ReplStep.completing("fine now")));
        ReplRunner.ReplLoop loop = prompt -> steps.removeFirst().apply(prompt);
        ReplRunner repl = runner(loop, lines("trigger", "ok"), NEVER_INTERRUPTED);

        assertEquals(0, repl.run(),
                "an unexpected fault on a turn does not end the session (0 at EOF)");
        assertTrue(stdout().contains("fine now"), "the session stayed alive; was: " + stdout());
    }

    @Test
    @DisplayName("AC-13.4: a fatal persistence failure ends the session with exit 1")
    void persistenceFailureEndsSessionWithOne() {
        // Oracle: AC-13.4 / exit-code contract "1 internal — event could not be persisted". If the
        // event log can no longer be durably written, continuing would silently drop events, so the
        // interactive session ends with exit 1 rather than run blind. (Contrast: a backend error,
        // which the session survives.)
        ReplRunner repl = runner(prompt -> {
            throw new PersistenceException("failed to persist event seq=3");
        }, lines("write the file"), NEVER_INTERRUPTED);

        int exitCode = repl.run();

        assertEquals(1, exitCode,
                "AC-13.4: a fatal persistence failure ends the interactive session with exit 1");
        assertFalse(stderr().isBlank(),
                "G2: the persistence failure is reported; was: " + stderr());
    }

    // --- AC-10.1 end-to-end : the inline approval prompt is shown -------------------

    @Test
    @DisplayName("AC-10.1: a prompt turn whose tool needs approval shows the exact operation inline")
    void inlineApprovalShowsExactOperation() {
        // Oracle: AC-10.1 — "present the exact operation ... before executing". End-to-end via a
        // REAL AgentLoop over a scripted Bedrock double: the model asks to run a side-effecting
        // command in an asking mode, so the gate consults the InteractiveApprover, which must
        // present the exact command before the (denied) decision. The shared input source feeds
        // both the REPL prompt and the approver's answer (one terminal). The tool must NOT run
        // (the answer is "no" → DENY).
        RecordingTool tool = new RecordingTool();
        // Lines: turn prompt, then the approver's "no" answer, then EOF (clean exit 0).
        Supplier<String> input = lines("run the build", "no");
        InteractiveApprover approver = new InteractiveApprover(input, out);
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient()
                        .then(toolUseTurn("tu_1", "run_command", "mvn -q clean verify"))
                        .then(textTurn("I won't run it then.", "end_turn")),
                ToolRegistry.of(List.of(tool)), PermissionMode.ASK_EVERY_TIME, approver);
        ReplRunner repl = new ReplRunner(loop::run, input, NEVER_INTERRUPTED,
                PermissionMode.ASK_EVERY_TIME, out, err);

        int exitCode = repl.run();

        assertEquals(0, exitCode, "the session ends cleanly at EOF after the turn (0)");
        assertTrue(stdout().contains("mvn -q clean verify"),
                "AC-10.1: the exact command operation is presented inline before executing; was: "
                        + stdout());
        assertFalse(tool.ran.get(),
                "AC-10.2: the denied operation is not executed");
    }

    // --- construction ---------------------------------------------------------------

    @Test
    @DisplayName("the runner requires its loop, line source, interrupt signal, mode, and streams")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                null, lines(), NEVER_INTERRUPTED, PermissionMode.ASK_EVERY_TIME, out, err));
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                prompt -> LoopOutcome.completed("x"), null, NEVER_INTERRUPTED,
                PermissionMode.ASK_EVERY_TIME, out, err));
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                prompt -> LoopOutcome.completed("x"), lines(), null,
                PermissionMode.ASK_EVERY_TIME, out, err));
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                prompt -> LoopOutcome.completed("x"), lines(), NEVER_INTERRUPTED, null, out, err));
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                prompt -> LoopOutcome.completed("x"), lines(), NEVER_INTERRUPTED,
                PermissionMode.ASK_EVERY_TIME, null, err));
        assertThrows(NullPointerException.class, () -> new ReplRunner(
                prompt -> LoopOutcome.completed("x"), lines(), NEVER_INTERRUPTED,
                PermissionMode.ASK_EVERY_TIME, out, null));
    }

    // --- helpers: a scriptable step seam and the scripted Bedrock double -------------

    /** A one-shot step the substitute loop applies: either complete with text, or throw. */
    private interface ReplStep {
        LoopOutcome apply(String prompt);

        static ReplStep completing(String text) {
            return prompt -> LoopOutcome.completed(text);
        }

        static ReplStep throwing(RuntimeException toThrow) {
            return prompt -> {
                throw toThrow;
            };
        }
    }

    private static final class ScriptedBedrockClient implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> script = new ArrayDeque<>();
        private final List<ConverseRequest> requests = new ArrayList<>();

        ScriptedBedrockClient then(ConverseResponse response) {
            script.addLast(response);
            return this;
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            if (script.isEmpty()) {
                throw new IllegalStateException("scripted model exhausted after " + requests.size());
            }
            return script.removeFirst();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op for the in-test double
        }
    }

    private static ConverseResponse textTurn(String text, String stopReason) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason(stopReason)
                .usage(u -> u.inputTokens(50).outputTokens(10).totalTokens(60))
                .build();
    }

    private static ConverseResponse toolUseTurn(String toolUseId, String tool, String command) {
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock toolUseBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                        b -> b.toolUseId(toolUseId).name(tool)
                                .input(Document.mapBuilder().putString("command", command).build()));
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(toolUseBlock))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(80).outputTokens(20).totalTokens(100))
                .build();
    }

    private static Document minimalSchema() {
        return Document.mapBuilder().putString("type", "object").build();
    }

    /** A real side-effecting tool that records whether it ran (a controllable collaborator). */
    private static final class RecordingTool implements ToolHandler {
        private final AtomicBoolean ran = new AtomicBoolean(false);

        @Override
        public String name() {
            return "run_command";
        }

        @Override
        public String description() {
            return "test runner";
        }

        @Override
        public Document inputSchema() {
            return minimalSchema();
        }

        @Override
        public OperationClass operationClass() {
            return OperationClass.SIDE_EFFECTING;
        }

        @Override
        public Object handle(Map<String, Object> input) {
            ran.set(true);
            return "ran";
        }
    }

    private static AgentLoop loopOver(BedrockRuntimeClient bedrock, ToolRegistry tools,
            PermissionMode mode, Approver approver) {
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(mode, GrantStore.forSession("test"), approver);
        return new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, BudgetGuard.NONE, new OutputDisposer(16384), MODEL_ID, null);
    }
}
