package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
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
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
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
import org.junit.jupiter.api.BeforeEach;
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
 * Unit tests for {@link OneShotRunner} — the one-shot run-and-map seam: it runs a prompt
 * through the agent loop and maps the terminal {@link LoopOutcome} (or any failure thrown
 * along the way) to an {@link ExitCode} honouring the exit-code contract's precedence
 * ({@code 06-formal/cli-exit-codes.md} § 2).
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link OneShotRunner}. For the
 * outcome-mapping tests (CT-EX-6, the surfaced-reason mappings) it is driven by a
 * <em>real</em> {@link AgentLoop} (with its real {@link ModelClient}, {@link ToolRegistry},
 * {@link PermissionGate}, {@link EventLog}) over a hand-rolled scripted
 * {@link BedrockRuntimeClient} double — the only external dependency, so the run is exercised
 * end-to-end with no live AWS call. For the exception-mapping branches (exit 4/3/1/130) the
 * loop seam is a small substitute that throws the typed exception the contract names; that
 * substitute is the external boundary, not the SUT.
 *
 * <p><b>Oracles.</b> Expected exit codes trace to the exit-code contract, never to
 * {@link OneShotRunner}'s code:
 * <ul>
 *   <li><b>CT-EX-6 / exit-code contract {@code 0}:</b> a completed one-shot ({@code end_turn})
 *       → exit {@code 0}.</li>
 *   <li><b>CT-EX-2 / AC-8.9 / exit-code contract {@code 4}:</b> a
 *       {@link CredentialResolutionException} → exit {@code 4}.</li>
 *   <li><b>§ 3.2 / exit-code contract {@code 4}:</b> a {@link ModelBackendException} →
 *       exit {@code 4}.</li>
 *   <li><b>CT-EX-3 / AC-10.2 / exit-code contract {@code 3}:</b> a
 *       {@link UserAbortedException} (blocking denial) → exit {@code 3}.</li>
 *   <li><b>CT-EX-4 / §02 §4 / exit-code contract {@code 130}:</b> an
 *       {@link InterruptedRunException} (SIGINT) → exit {@code 130}, and by precedence it
 *       wins over other codes.</li>
 *   <li><b>AC-13.4 / exit-code contract {@code 1}:</b> a {@link PersistenceException} (event
 *       could not be persisted) → exit {@code 1}; any other unhandled fault → {@code 1}.</li>
 *   <li><b>cli-exit-codes surfaced-reason mapping:</b>
 *       {@code model_context_window_exceeded} → {@code 5}; guardrail / content-filtered /
 *       malformed → {@code 1}.</li>
 * </ul>
 */
class OneShotRunnerTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String TS = "2026-06-22T09:00:00Z";

    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;
    private PrintStream out;
    private PrintStream err;

    @BeforeEach
    void captureStreams() {
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    // --- Scripted external Bedrock dependency (the only test double on the happy path) ---

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
                () -> TS, BudgetGuard.NONE, MODEL_ID, null);
    }

    // --- CT-EX-6 : a completed one-shot exits 0 -------------------------------------

    @Test
    @DisplayName("CT-EX-6: a successful one-shot (end_turn) exits 0 and prints the final answer")
    void completedOutcomeExitsZero() {
        // Oracle: CT-EX-6 / exit-code contract "0 success" — "clean ... successful one-shot
        // → exit 0" (US-6). A real loop driven by a scripted end_turn turn completes; the
        // runner must return 0. Expected 0 traces to the contract, not the runner's code.
        AgentLoop loop = loopOver(new ScriptedBedrockClient().then(textTurn("Done.", "end_turn")),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, denyingApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("do the thing");

        assertEquals(0, exitCode, "CT-EX-6: a completed one-shot exits 0 (exit-code contract: 0)");
        assertTrue(stdout().contains("Done."),
                "the final assistant answer is written to stdout (04-apis § 1.6: CLI owns output)");
        assertTrue(stderr().isEmpty(),
                "a clean exit writes no error line (G2 only fires on non-zero exits); was: " + stderr());
    }

    @Test
    @DisplayName("CT-EX-6: a one-shot whose tools auto-approve (Class R) completes and exits 0")
    void readOnlyToolCompletesExitsZero() {
        // Oracle: CT-EX-6 — a one-shot that runs a Class-R (auto-approved, never prompts)
        // tool then ends its turn exits 0. Proves the non-interactive approver is NOT
        // consulted for an auto-approved op (so no spurious abort), and the run completes.
        ToolHandler reader = new ToolHandler() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "reader";
            }

            @Override
            public Document inputSchema() {
                return minimalSchema();
            }

            @Override
            public OperationClass operationClass() {
                return OperationClass.READ;
            }

            @Override
            public Object handle(Map<String, Object> input) {
                return "contents";
            }
        };
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock readBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                        b -> b.toolUseId("tu_r").name("read_file")
                                .input(Document.mapBuilder().putString("path", "x").build()));
        ConverseResponse readTurn = ConverseResponse.builder()
                .output(ConverseOutput.builder().message(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(List.of(readBlock))
                        .build()).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(10).outputTokens(2).totalTokens(12))
                .build();
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient().then(readTurn).then(textTurn("Read it.", "end_turn")),
                ToolRegistry.of(List.of(reader)),
                PermissionMode.ASK_EVERY_TIME, new NonInteractiveApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("read x");

        assertEquals(0, exitCode,
                "CT-EX-6: an auto-approved (Class R) tool path completes and exits 0");
    }

    // --- CT-EX-3 : a blocking denial exits 3 ----------------------------------------

    @Test
    @DisplayName("CT-EX-3: a blocking required-action denial (NonInteractiveApprover) exits 3")
    void blockingDenialExitsThree() {
        // Oracle: CT-EX-3 / AC-10.2 / exit-code contract "3 user-aborted — denial of a gated
        // op the task cannot proceed without". A one-shot run with a side-effecting tool in an
        // asking mode reaches the NonInteractiveApprover (which cannot prompt) and aborts. The
        // runner must map the UserAbortedException to exit 3. End-to-end via a real loop +
        // scripted Bedrock + the production NonInteractiveApprover.
        RecordingTool tool = new RecordingTool();
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient().then(toolUseTurn("tu_x", "run_command", "ls")),
                ToolRegistry.of(List.of(tool)),
                PermissionMode.ASK_EVERY_TIME, new NonInteractiveApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("list files");

        assertEquals(3, exitCode,
                "CT-EX-3: a blocking required-action denial exits 3 (AC-10.2, exit-code contract)");
        assertFalse(tool.ran.get(),
                "AC-10.2/INV-8: the denied operation is not executed");
        assertFalse(stderr().isBlank(),
                "G2: a non-zero exit prints a stderr line naming the cause; was: " + stderr());
    }

    @Test
    @DisplayName("exit-code contract 3: a UserAbortedException from the loop maps to exit 3")
    void userAbortedExceptionMapsToThree() {
        // Oracle: exit-code contract "3 user-aborted" — a UserAbortedException surfaced from
        // the run maps to exit 3. Drives the mapping branch directly with a throwing loop seam
        // (the external boundary), so the SUT is the runner's mapping, not the loop.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new UserAbortedException("requires approval: run_command: rm -rf /");
                }, out, err);

        int exitCode = runner.run("delete everything");

        assertEquals(3, exitCode, "exit-code contract: a blocking denial maps to exit 3");
        assertTrue(stderr().contains("rm -rf /"),
                "G2: the stderr line names the blocking operation; was: " + stderr());
    }

    // --- exit 4 : model-backend (credentials / Converse failure) --------------------

    @Test
    @DisplayName("CT-EX-2: no usable SigV4 credentials maps to exit 4 naming the paths attempted")
    void credentialResolutionFailureMapsToFour() {
        // Oracle: CT-EX-2 / AC-8.9 / exit-code contract "4 model-backend — no usable SigV4
        // credentials". A CredentialResolutionException (whose message names the paths)
        // surfaced from the run maps to exit 4, and the stderr line names the paths (G2).
        String message = "no usable SigV4 credentials for Bedrock; paths attempted: "
                + "profile 'awsBedRockProfile' and the default chain";
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new CredentialResolutionException(message);
                }, out, err);

        int exitCode = runner.run("hello");

        assertEquals(4, exitCode,
                "CT-EX-2: no usable credentials maps to exit 4 (model-backend, AC-8.9)");
        assertTrue(stderr().contains("paths attempted"),
                "G2: the stderr line names the paths attempted; was: " + stderr());
    }

    @Test
    @DisplayName("§ 3.2: an unrecoverable Bedrock failure (ModelBackendException) maps to exit 4")
    void modelBackendFailureMapsToFour() {
        // Oracle: § 3.2 / exit-code contract "4 model-backend — Bedrock could not be used".
        // A ModelBackendException surfaced from the run maps to exit 4.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new ModelBackendException("Bedrock Converse call failed",
                            new RuntimeException("503 ServiceUnavailable"));
                }, out, err);

        int exitCode = runner.run("hello");

        assertEquals(4, exitCode, "§ 3.2: an unrecoverable Bedrock failure maps to exit 4");
    }

    // --- exit 130 : interrupted (SIGINT), and precedence ----------------------------

    @Test
    @DisplayName("CT-EX-4: an interrupt (InterruptedRunException) maps to exit 130")
    void interruptMapsTo130() {
        // Oracle: CT-EX-4 / §02 §4 / exit-code contract "130 interrupted (SIGINT)". The task's
        // testable contract: a SIGINT during a run maps to exit 130 (assert the mapping logic,
        // not OS signal delivery). The InterruptedRunException is the clean SIGINT seam.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new InterruptedRunException("interrupted by SIGINT");
                }, out, err);

        int exitCode = runner.run("long task");

        assertEquals(130, exitCode,
                "CT-EX-4: a SIGINT maps to exit 130 (128 + SIGINT 2, exit-code contract)");
    }

    @Test
    @DisplayName("cli-exit-codes § 2: 130 (SIGINT) wins precedence over a backend failure")
    void interruptPrecedenceWinsOverBackend() {
        // Oracle: cli-exit-codes § 2 precedence — "130 SIGINT always wins". When the run is
        // interrupted, the interrupted code takes priority; even if a backend failure were
        // also in flight, the interrupt seam is thrown and 130 is returned. The runner catches
        // InterruptedRunException first, so an interrupt is never masked by a 4.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    // Model an interrupt arriving as the run was failing at the backend: the
                    // interrupt seam is what surfaces, and 130 must win over 4.
                    throw new InterruptedRunException("interrupted while calling Bedrock");
                }, out, err);

        int exitCode = runner.run("task");

        assertEquals(130, exitCode,
                "cli-exit-codes § 2: 130 SIGINT always wins (precedence over 4 model-backend)");
    }

    @Test
    @DisplayName("CT-EX-4: an interrupt re-sets the thread's interrupt flag (resumable signal)")
    void interruptPreservesInterruptStatus() {
        // Oracle: §02 §4 — Ctrl-C interrupts the current step; the session remains resumable.
        // Mapping an interrupt to 130 must not silently clear the thread's interrupt status
        // (Effective Java Item 70 / the interrupt-handling contract): the runner re-asserts it
        // so a caller up the stack can still observe the interrupt.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new InterruptedRunException("interrupted");
                }, out, err);

        int exitCode = runner.run("task");

        assertEquals(130, exitCode, "the interrupt maps to 130");
        assertTrue(Thread.interrupted(),
                "the interrupt status is re-asserted (and cleared here for test isolation)");
    }

    // --- exit 1 : internal (persistence failure, catch-all) -------------------------

    @Test
    @DisplayName("AC-13.4: an event that cannot be persisted maps to exit 1")
    void persistenceFailureMapsToOne() {
        // Oracle: AC-13.4 / § 3.2 / exit-code contract "1 internal — event could not be
        // persisted". A PersistenceException surfaced from the run maps to exit 1 (don't
        // pretend the event logged).
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new PersistenceException("failed to persist event seq=3");
                }, out, err);

        int exitCode = runner.run("hello");

        assertEquals(1, exitCode, "AC-13.4: an un-persistable event maps to exit 1 (internal)");
    }

    @Test
    @DisplayName("§ 3.2: an unexpected unhandled fault maps to the exit-1 catch-all")
    void unexpectedFaultMapsToOne() {
        // Oracle: § 3.2 / exit-code contract "1 internal — unexpected/unhandled error not
        // otherwise classified". A runtime fault that is none of the classified types maps to
        // exit 1.
        OneShotRunner runner = new OneShotRunner(
                prompt -> {
                    throw new IllegalStateException("something unexpected");
                }, out, err);

        int exitCode = runner.run("hello");

        assertEquals(1, exitCode, "§ 3.2: an unhandled internal error maps to exit 1 (catch-all)");
    }

    // --- surfaced edge-reason mapping (cli-exit-codes) ------------------------------

    @Test
    @DisplayName("cli-exit-codes 5: model_context_window_exceeded (no compaction) maps to exit 5")
    void contextWindowExceededMapsToFive() {
        // Oracle: dep API note + exit-code contract "5 context-exhausted — context limit hit
        // and compaction could not recover". At M0 there is no compaction, so a surfaced
        // model_context_window_exceeded maps toward 5. Driven by a real loop whose scripted
        // turn surfaces that reason.
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient().then(textTurn("", "model_context_window_exceeded")),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, denyingApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("huge prompt");

        assertEquals(5, exitCode,
                "cli-exit-codes: model_context_window_exceeded with no compaction maps to 5");
    }

    @Test
    @DisplayName("cli-exit-codes 1: a surfaced guardrail_intervened maps to the exit-1 catch-all")
    void guardrailSurfacedMapsToOne() {
        // Oracle: dep API note — "guardrail/content_filtered/malformed are surfaced conditions
        // — map to the catch-all 1 INTERNAL unless you can justify another code". The contract
        // pins no other code for a surfaced guardrail, so it maps to 1.
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient().then(textTurn("", "guardrail_intervened")),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, denyingApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("blocked prompt");

        assertEquals(1, exitCode,
                "cli-exit-codes: a surfaced guardrail_intervened maps to the exit-1 catch-all");
    }

    @Test
    @DisplayName("cli-exit-codes 1: a surfaced malformed_tool_use maps to the exit-1 catch-all")
    void malformedSurfacedMapsToOne() {
        // Oracle: dep API note — malformed_* surfaced reasons map to the catch-all 1 INTERNAL
        // (the contract pins no other code). Driven by a real loop surfacing the reason.
        AgentLoop loop = loopOver(
                new ScriptedBedrockClient().then(textTurn("", "malformed_tool_use")),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, denyingApprover());
        OneShotRunner runner = new OneShotRunner(loop::run, out, err);

        int exitCode = runner.run("prompt");

        assertEquals(1, exitCode,
                "cli-exit-codes: a surfaced malformed_tool_use maps to the exit-1 catch-all");
    }

    // --- construction + input validation --------------------------------------------

    @Test
    @DisplayName("the runner requires its loop and streams (composition contract)")
    void constructorRejectsNull() {
        assertThrows(NullPointerException.class, () -> new OneShotRunner(null, out, err));
        assertThrows(NullPointerException.class,
                () -> new OneShotRunner(prompt -> LoopOutcome.completed("x"), null, err));
        assertThrows(NullPointerException.class,
                () -> new OneShotRunner(prompt -> LoopOutcome.completed("x"), out, null));
    }

    @Test
    @DisplayName("run rejects a null or blank prompt (a bad invocation the caller must reject)")
    void runRejectsBlankPrompt() {
        OneShotRunner runner = new OneShotRunner(prompt -> LoopOutcome.completed("x"), out, err);

        assertThrows(NullPointerException.class, () -> runner.run(null));
        assertThrows(IllegalArgumentException.class, () -> runner.run("   "));
    }

    @Test
    @DisplayName("exit-code contract 0: a completed outcome with no text still exits 0")
    void completedWithEmptyTextExitsZero() {
        // Oracle: exit-code contract "0 success" — completion is the success signal regardless
        // of whether the final turn carried text (LoopOutcome.completed allows empty text).
        OneShotRunner runner = new OneShotRunner(prompt -> LoopOutcome.completed(""), out, err);

        assertEquals(0, runner.run("hi"),
                "a completed outcome exits 0 even when the final text is empty");
    }

    @Test
    @DisplayName("StopReason coverage: every surfaced reason maps to a documented non-zero code")
    void surfacedReasonsMapToNonZero() {
        // Oracle: cli-exit-codes — a surfaced outcome is a non-zero terminal condition at M0
        // (no compaction, no repair-retry). Every surfaced StopReason except the completion
        // reasons must yield a non-zero code (5 for context-window-exceeded, 1 otherwise).
        for (StopReason reason : StopReason.values()) {
            if (reason == StopReason.END_TURN || reason == StopReason.STOP_SEQUENCE
                    || reason == StopReason.TOOL_USE) {
                continue; // these are not "surfaced" reasons the loop emits as SURFACED
            }
            OneShotRunner runner = new OneShotRunner(
                    prompt -> LoopOutcome.surfaced(reason), out, err);
            int code = runner.run("p");
            assertTrue(code != 0,
                    "a surfaced " + reason + " must be a non-zero terminal condition; was " + code);
        }
    }

    private static Approver denyingApprover() {
        return req -> PermissionDecisionOutcome.DENY;
    }
}
