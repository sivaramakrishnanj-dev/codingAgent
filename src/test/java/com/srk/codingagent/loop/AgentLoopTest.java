package com.srk.codingagent.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.context.OutputRetrieval;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolRegistry;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.Type;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Unit tests for {@link AgentLoop} — the agent loop (component C2, ADR-0001), the
 * stopReason dispatch (tool_use &harr; end_turn) with the permission gate inline and
 * log-before-act.
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link AgentLoop} composed with a
 * real {@link ModelClient} (and its real {@code ConverseWireMapper}), a real
 * {@link ToolRegistry} of small real test tools, a real {@link PermissionGate} with a stub
 * {@link Approver}, and a real {@link EventLog} writing to a {@link StringWriter}. Nothing
 * the loop's logic owns is mocked. The single external dependency — the
 * {@link BedrockRuntimeClient} — is a hand-rolled in-test double that replays a scripted
 * sequence of {@link ConverseResponse}s (turn-1 tool_use &rarr; turn-2 end_turn, deny path,
 * edge reasons), so the loop is driven by scripted model turns with no live AWS call (the
 * task's mocked-Bedrock directive; ADR-0001 — no live calls in unit tests).
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, not to {@link AgentLoop}'s code:
 * <ul>
 *   <li><b>CT-SM-1 (state machine A, T2&rarr;T10):</b> {@code stopReason: tool_use} drives
 *       gate &rarr; exec &rarr; append-result &rarr; re-call until {@code end_turn}.</li>
 *   <li><b>CT-INV-2 (INV-2, log-before-act):</b> each side-effecting step's event is
 *       appended (and flushed) before the loop acts on its consequence. The authoritative
 *       per-cycle event order is the contract fixture {@code session-tool-use-cycle.jsonl}:
 *       {@code USER_MESSAGE, MODEL_RESPONSE, MODEL_USAGE, TOOL_USE, PERMISSION_DECISION,
 *       TOOL_RESULT, MODEL_RESPONSE, MODEL_USAGE}.</li>
 *   <li><b>CT-SM-2 (state machine A, T8):</b> a gate deny appends
 *       {@code PERMISSION_DECISION(deny)} + {@code TOOL_RESULT(denied)} and the loop
 *       continues, with no handler run (INV-8 gate-before-side-effect).</li>
 *   <li><b>§ 3.1 / state machine A T3/T4/T5:</b> {@code end_turn} returns the final text;
 *       {@code stop_sequence} is treated as {@code end_turn}; edge reasons are surfaced.</li>
 * </ul>
 */
class AgentLoopTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String TS = "2026-06-17T09:00:00Z";

    /**
     * The default-cap disposer (NFR-OUTPUT-MAX-INLINE = 16384). The small outputs these
     * loop tests produce ("file contents", "ran", ...) are far under the cap, so disposal is
     * a no-op pass-through for them — the existing event-order and re-call assertions are
     * unaffected. Output-disposal behaviour itself is exercised in {@code OutputDisposerTest}
     * and the disposal-wiring tests below.
     */
    private static final OutputDisposer DISPOSER = new OutputDisposer(16384);

    /** A minimal valid JSON-Schema input document for a test tool (independent of the tool package). */
    private static Document minimalSchema() {
        return Document.mapBuilder().putString("type", "object").build();
    }

    // --- Scripted external Bedrock dependency (the only test double) -----------------

    /**
     * A {@link BedrockRuntimeClient} that replays a scripted queue of responses, one per
     * {@code converse} call, capturing each request. The SUT is {@link AgentLoop} via a
     * real {@link ModelClient}; this only stubs the external Bedrock dependency.
     */
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
                throw new IllegalStateException("scripted model exhausted after " + requests.size() + " calls");
            }
            return script.removeFirst();
        }

        int callCount() {
            return requests.size();
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

    private static ConverseResponse toolUseTurn(String text, String toolUseId, String tool,
            Map<String, String> input) {
        Document.MapBuilder in = Document.mapBuilder();
        input.forEach(in::putString);
        // The SDK Message builder's content(...) overloads SET (not append) the block list,
        // so a text+toolUse turn (matching the contract fixture's TOOL_USE turn) is passed as
        // one content(List) call.
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock textBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText(text);
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock toolUseBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                        b -> b.toolUseId(toolUseId).name(tool).input(in.build()));
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(textBlock, toolUseBlock))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(100).outputTokens(20).totalTokens(120))
                .build();
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

    // --- A small real tool (a controllable collaborator, not a mock of the SUT) -------

    /** A real read-class tool that records whether it ran; stands in for a registered tool. */
    private static final class RecordingTool implements ToolHandler {
        private final String name;
        private final OperationClass operationClass;
        private final AtomicBoolean ran = new AtomicBoolean(false);
        private final Object result;

        RecordingTool(String name, OperationClass operationClass, Object result) {
            this.name = name;
            this.operationClass = operationClass;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name + " test tool";
        }

        @Override
        public Document inputSchema() {
            return minimalSchema();
        }

        @Override
        public OperationClass operationClass() {
            return operationClass;
        }

        @Override
        public Object handle(Map<String, Object> input) {
            ran.set(true);
            return result;
        }
    }

    // --- Fixtures -------------------------------------------------------------------

    private static AgentLoop loopWith(BedrockRuntimeClient bedrock, ToolRegistry tools,
            PermissionMode mode, Approver approver, EventLog log) {
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(mode, GrantStore.forSession("root"), approver);
        return new AgentLoop(
                modelClient, tools, gate, log, () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null);
    }

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    private static Approver alwaysDeny() {
        return req -> PermissionDecisionOutcome.DENY;
    }

    private static List<String> typesIn(StringWriter sink) {
        List<String> types = new ArrayList<>();
        for (String line : sink.toString().lines().toList()) {
            int i = line.indexOf("\"type\":\"");
            if (i >= 0) {
                int start = i + "\"type\":\"".length();
                types.add(line.substring(start, line.indexOf('"', start)));
            }
        }
        return types;
    }

    // --- CT-SM-1 : the full tool-use cycle drives to end_turn ------------------------

    @Test
    @DisplayName("CT-SM-1: tool_use drives gate->exec->append-result->re-call until end_turn")
    void toolUseCycleDrivesToEndTurn() {
        // Oracle: CT-SM-1 / state machine A T2->T10 — "stopReason: tool_use drives
        // gate->exec->append-result->re-call until end_turn". Turn 1 returns tool_use; the
        // loop must gate, run the tool, append its result, re-call; turn 2 returns end_turn,
        // and the loop returns the final text. Expected end-state traces to the scripted
        // turns + the state-machine contract, not to AgentLoop internals.
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "file contents");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("I'll read it.", "tu_01", "read_file", Map.of("path", "x.txt")))
                .then(textTurn("Here is the file.", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("Read x.txt");

        assertEquals(2, bedrock.callCount(),
                "CT-SM-1: a tool_use turn re-calls the model after the tool result (T10)");
        assertTrue(tool.ran.get(), "CT-SM-1: the approved tool's handler ran (T7->S4)");
        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind(),
                "CT-SM-1: the cycle terminates at end_turn (T3->S5)");
        assertEquals(StopReason.END_TURN, outcome.stopReason(),
                "CT-SM-1: the terminal stop reason is end_turn");
        assertEquals("Here is the file.", outcome.finalText(),
                "CT-SM-1: end_turn returns the final assistant text (§ 3.1)");
    }

    @Test
    @DisplayName("CT-SM-1: the tool result is sent back to the model on the re-call (T10)")
    void toolResultIsSentBackOnRecall() {
        // Oracle: state machine A T10 — "append the batched toolResults as one user message
        // and re-call". The second Converse request must carry the tool result the loop
        // produced, correlated by toolUseId (INV-6). Assert against the captured request,
        // not impl state.
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "the data");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_77", "read_file", Map.of("path", "y.txt")))
                .then(textTurn("done", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        loop.run("Read y.txt");

        ConverseRequest recall = bedrock.requests.get(1);
        boolean carriesToolResult = recall.messages().stream()
                .flatMap(m -> m.content().stream())
                .anyMatch(b -> b.toolResult() != null && "tu_77".equals(b.toolResult().toolUseId()));
        assertTrue(carriesToolResult,
                "T10/INV-6: the re-call resends the tool result correlated by its toolUseId");
    }

    // --- CT-INV-2 : log-before-act ordering -----------------------------------------

    @Test
    @DisplayName("CT-INV-2: events are appended in the fixture's log-before-act order")
    void eventsAppendedInLogBeforeActOrder() {
        // Oracle: CT-INV-2 / INV-2 — "each side-effecting step's event is flushed before the
        // effect (log-before-act)". The authoritative per-cycle order is the contract fixture
        // session-tool-use-cycle.jsonl: USER_MESSAGE, then per assistant turn MODEL_RESPONSE
        // then MODEL_USAGE, then per toolUse TOOL_USE -> PERMISSION_DECISION -> TOOL_RESULT,
        // then the re-call's MODEL_RESPONSE/MODEL_USAGE. Assert the emitted event type
        // sequence equals the fixture's sequence (minus SESSION_START/OUTCOME, which are not
        // this task's lane).
        RecordingTool tool = new RecordingTool("run_command", OperationClass.SIDE_EFFECTING, "ran");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("running", "tu_01", "run_command", Map.of("command", "mvn -q test")))
                .then(textTurn("All pass.", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(sink, "t"));

        loop.run("Run the tests");

        assertEquals(List.of(
                "USER_MESSAGE",        // T1
                "MODEL_RESPONSE",      // T2 assistant turn logged BEFORE tools dispatch
                "MODEL_USAGE",
                "TOOL_USE",            // the toolUse digest (fixture seq 4)
                "PERMISSION_DECISION", // logged BEFORE the tool runs (INV-2 + INV-8)
                "TOOL_RESULT",         // logged after the tool returns (T9)
                "USER_MESSAGE",        // the batched tool result sent back (T10)
                "MODEL_RESPONSE",      // the re-call's end_turn turn
                "MODEL_USAGE"),
                typesIn(sink),
                "CT-INV-2: the event order matches the contract fixture's log-before-act order");
    }

    @Test
    @DisplayName("CT-INV-2: the assistant MODEL_RESPONSE is logged before any tool is dispatched")
    void modelResponseLoggedBeforeToolDispatch() {
        // Oracle: CT-INV-2 / INV-2 — "the assistant MODEL_RESPONSE is appended before tools
        // dispatch". A tool that records the event log's contents at the instant it runs must
        // observe the MODEL_RESPONSE already written (the response is durably recorded before
        // the side effect acts).
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "t");
        AtomicBoolean responseLoggedWhenToolRan = new AtomicBoolean(false);
        ToolHandler probe = new ToolHandler() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "probe";
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
                responseLoggedWhenToolRan.set(sink.toString().contains("\"type\":\"MODEL_RESPONSE\""));
                return "ok";
            }
        };
        ToolRegistry tools = ToolRegistry.of(List.of(probe));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_01", "read_file", Map.of("path", "z")))
                .then(textTurn("done", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(), log);

        loop.run("go");

        assertTrue(responseLoggedWhenToolRan.get(),
                "INV-2: the assistant MODEL_RESPONSE must be logged before the tool dispatches");
    }

    @Test
    @DisplayName("CT-INV-2: the PERMISSION_DECISION is logged before the tool handler runs (INV-8 + INV-2)")
    void permissionDecisionLoggedBeforeHandlerRuns() {
        // Oracle: INV-2 + INV-8 — no side effect executes without a preceding
        // PERMISSION_DECISION, and that decision is logged before the effect. The tool, when
        // it runs, must already see its PERMISSION_DECISION on disk.
        StringWriter sink = new StringWriter();
        EventLog log = EventLog.over(sink, "t");
        AtomicBoolean decisionLoggedWhenToolRan = new AtomicBoolean(false);
        ToolHandler probe = new ToolHandler() {
            @Override
            public String name() {
                return "read_file";
            }

            @Override
            public String description() {
                return "probe";
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
                decisionLoggedWhenToolRan.set(
                        sink.toString().contains("\"type\":\"PERMISSION_DECISION\""));
                return "ok";
            }
        };
        ToolRegistry tools = ToolRegistry.of(List.of(probe));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_01", "read_file", Map.of("path", "z")))
                .then(textTurn("done", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(), log);

        loop.run("go");

        assertTrue(decisionLoggedWhenToolRan.get(),
                "INV-8/INV-2: the PERMISSION_DECISION must be logged before the side effect");
    }

    // --- CT-SM-2 : a gate deny appends decision + denied result and continues ---------

    @Test
    @DisplayName("CT-SM-2: a gate deny logs PERMISSION_DECISION(deny)+TOOL_RESULT(denied), runs no handler")
    void gateDenyLogsDeniedResultAndRunsNoHandler() {
        // Oracle: CT-SM-2 / state machine A T8 — "a gate denial appends TOOL_RESULT(denied)
        // and the loop continues (no side effect)". With a side-effecting tool and a denying
        // approver, the loop must log a deny decision and a denied tool result, must NOT run
        // the handler (INV-8), and must continue to the next turn (which ends the turn).
        RecordingTool tool = new RecordingTool("run_command", OperationClass.SIDE_EFFECTING, "should not run");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("running", "tu_09", "run_command", Map.of("command", "rm -rf /")))
                .then(textTurn("Understood, I won't.", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysDeny(), EventLog.over(sink, "t"));

        LoopOutcome outcome = loop.run("Delete everything");

        assertFalse(tool.ran.get(), "CT-SM-2/INV-8: a denied tool's handler must NOT run");
        assertTrue(sink.toString().contains("\"decision\":\"deny\""),
                "CT-SM-2: a deny decision is recorded on the PERMISSION_DECISION event");
        assertTrue(sink.toString().contains("\"status\":\"denied\""),
                "CT-SM-2: a denied tool result (status denied) is recorded (T8)");
        assertEquals(2, bedrock.callCount(),
                "CT-SM-2: the loop continues after a denial — it re-calls with the denied result");
        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind(),
                "CT-SM-2: the loop proceeds to the next turn and completes normally");
    }

    @Test
    @DisplayName("CT-SM-2: the denied tool result is sent back to the model so it can react (T8)")
    void deniedResultSentBackToModel() {
        // Oracle: state machine A T8 — "loop continues with the denial result"; the denied
        // tool result, correlated by toolUseId (INV-6), is resent so the model reacts.
        RecordingTool tool = new RecordingTool("run_command", OperationClass.SIDE_EFFECTING, "x");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("running", "tu_09", "run_command", Map.of("command", "rm -rf /")))
                .then(textTurn("ok", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysDeny(), EventLog.over(new StringWriter(), "t"));

        loop.run("go");

        ConverseRequest recall = bedrock.requests.get(1);
        boolean carriesDenied = recall.messages().stream()
                .flatMap(m -> m.content().stream())
                .anyMatch(b -> b.toolResult() != null && "tu_09".equals(b.toolResult().toolUseId()));
        assertTrue(carriesDenied,
                "T8/INV-6: the denied result is resent on the re-call, correlated by toolUseId");
    }

    @Test
    @DisplayName("INV-8: READ_ONLY denies a side-effecting tool without prompting, runs no handler")
    void readOnlyDeniesSideEffectingTool() {
        // Oracle: AC-9.2 (gate behaviour, T-0.7) composed in the loop — in READ_ONLY a
        // Class-X tool is denied; the loop must record the deny + denied result and run no
        // handler (INV-8). The deny here comes from the mode, not the approver (no prompt).
        RecordingTool tool = new RecordingTool("write_file", OperationClass.SIDE_EFFECTING, "wrote");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("writing", "tu_05", "write_file",
                        Map.of("path", "a.txt", "content", "hi")))
                .then(textTurn("blocked", "end_turn"));
        // An approver that would approve if consulted — proving the DENY came from the mode.
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.READ_ONLY,
                alwaysApprove(), EventLog.over(sink, "t"));

        loop.run("write a file");

        assertFalse(tool.ran.get(), "AC-9.2/INV-8: READ_ONLY denies the write; the handler must not run");
        assertTrue(sink.toString().contains("\"status\":\"denied\""),
                "the denied tool result is recorded even though the deny came from the mode");
    }

    // --- end_turn / stop_sequence / edge reasons (§ 3.1, T3/T4/T5) -------------------

    @Test
    @DisplayName("§ 3.1: an immediate end_turn returns the final text with no tool dispatch")
    void immediateEndTurnReturnsFinalText() {
        // Oracle: § 3.1 / state machine A T3 — "end_turn: return final text". A first-turn
        // end_turn completes with one model call and no tools.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("Hello, how can I help?", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("hi");

        assertEquals(1, bedrock.callCount(), "§ 3.1: end_turn does not re-call");
        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind());
        assertEquals("Hello, how can I help?", outcome.finalText(),
                "§ 3.1: end_turn returns the model's final text");
    }

    @Test
    @DisplayName("T-4.2 / § 2.3: run(prompt, attachments) seeds the opening user turn with the prompt then the attachments")
    void runWithAttachmentsSeedsTurn(@TempDir Path dir) throws IOException {
        // Oracle: § 2.3 multimodal input — admitted attachment blocks join the opening user turn
        // (after the prompt text), so the wire mapper renders them into the first request. Assert
        // the first Converse call's user message carries the prompt text block then the image block,
        // in order — the attachment-to-user-turn path the C1 pipeline feeds.
        Path png = dir.resolve("diagram.png");
        Files.write(png, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("looking at it", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        loop.run("review this diagram", List.of(ContentBlock.image("png", png.toString())));

        var content = bedrock.requests.get(0).messages().get(0).content();
        assertEquals(2, content.size(),
                "§ 2.3: the opening user turn carries the prompt text and the attachment");
        assertEquals(Type.TEXT, content.get(0).type(), "the prompt text leads the turn");
        assertEquals(Type.IMAGE, content.get(1).type(),
                "§ 2.3: the admitted image attachment follows the prompt in the same turn");
    }

    @Test
    @DisplayName("T-4.2: run(prompt, emptyAttachments) carries only the prompt (the no-attachment case)")
    void runWithEmptyAttachmentsCarriesOnlyPrompt() {
        // Oracle: § 2.3 — with no attachment the opening turn is just the prompt text (the prior
        // behaviour, unchanged); run(prompt) and run(prompt, List.of()) are equivalent.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("ok", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        loop.run("just text", List.of());

        var content = bedrock.requests.get(0).messages().get(0).content();
        assertEquals(1, content.size(), "with no attachment the turn carries only the prompt text");
        assertEquals(Type.TEXT, content.get(0).type(), "the only block is the prompt text");
    }

    @Test
    @DisplayName("T-4.2: run(prompt, attachments) rejects a null attachments list")
    void runRejectsNullAttachments() {
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("ok", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        assertThrows(NullPointerException.class, () -> loop.run("x", null),
                "a null attachments list is a programming error");
    }

    @Test
    @DisplayName("§ 3.1: stop_sequence is treated as end_turn")
    void stopSequenceTreatedAsEndTurn() {
        // Oracle: § 3.1 — "stop_sequence: treat as end_turn unless a workflow uses
        // sequences". The loop completes (does not surface) on a stop_sequence turn.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("stopped here", "stop_sequence"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("hi");

        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind(),
                "§ 3.1: stop_sequence is treated as end_turn (a completed outcome)");
        assertEquals("stopped here", outcome.finalText());
    }

    @Test
    @DisplayName("state machine A T5: guardrail_intervened is surfaced, not retried, no tools run")
    void guardrailIsSurfaced() {
        // Oracle: § 3.1 / state machine A T5 — "guardrail_intervened: surface; do not retry
        // blindly". The loop must stop with a surfaced outcome carrying the stop reason, and
        // must not re-call.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("", "guardrail_intervened"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("hi");

        assertEquals(1, bedrock.callCount(), "T5: a surfaced edge reason is not retried blindly");
        assertEquals(LoopOutcome.Kind.SURFACED, outcome.kind(),
                "T5 -> S7: guardrail_intervened is surfaced, not completed");
        assertEquals(StopReason.GUARDRAIL_INTERVENED, outcome.stopReason(),
                "T5: the surfaced outcome carries the edge stop reason for the caller to decide");
        assertFalse(outcome.completed(), "a surfaced outcome is not a completion");
    }

    @Test
    @DisplayName("state machine A T4: model_context_window_exceeded is surfaced (compaction seam, T-2.x)")
    void contextWindowExceededIsSurfaced() {
        // Oracle: § 3.1 / state machine A T4 — context-window-exceeded hands to the
        // compaction machine. Compaction is out of scope (T-2.1/T-2.2); the loop surfaces the
        // reason rather than implementing compaction. Assert it surfaces, no tools, no
        // re-call.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("", "model_context_window_exceeded"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("hi");

        assertEquals(LoopOutcome.Kind.SURFACED, outcome.kind(),
                "T4: context-window-exceeded is surfaced (compaction is a later task)");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.stopReason());
    }

    // --- Multi-tool turn, multi-cycle, multi-block ----------------------------------

    @Test
    @DisplayName("CT-SM-1: a turn with multiple toolUse blocks gates and dispatches each, batches results")
    void multipleToolUseBlocksInOneTurn() {
        // Oracle: state machine A T6 ("next toolUse block") looped over a turn's blocks, then
        // T10 ("append all toolResults as one user message"). A turn with two toolUse blocks
        // must run both handlers and batch both results into one user message on the re-call.
        RecordingTool reader = new RecordingTool("read_file", OperationClass.READ, "data");
        RecordingTool runner = new RecordingTool("run_command", OperationClass.SIDE_EFFECTING, "out");
        ToolRegistry tools = ToolRegistry.of(List.of(reader, runner));
        // Build all three content blocks in one content(List) call: the SDK Message builder's
        // content(Consumer) overload replaces (not appends), so a multi-block turn must pass
        // the whole list at once for the model client to parse every block.
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock textBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText("doing two things");
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock readBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                        b -> b.toolUseId("tu_a").name("read_file")
                                .input(Document.mapBuilder().putString("path", "x").build()));
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock runBlock =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromToolUse(
                        b -> b.toolUseId("tu_b").name("run_command")
                                .input(Document.mapBuilder().putString("command", "ls").build()));
        Message twoTools = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(textBlock, readBlock, runBlock))
                .build();
        ConverseResponse twoToolTurn = ConverseResponse.builder()
                .output(ConverseOutput.builder().message(twoTools).build())
                .stopReason("tool_use")
                .usage(u -> u.inputTokens(80).outputTokens(30).totalTokens(110))
                .build();
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(twoToolTurn)
                .then(textTurn("both done", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(sink, "t"));

        loop.run("do two things");

        assertTrue(reader.ran.get() && runner.ran.get(), "T6: both toolUse blocks are dispatched");
        ConverseRequest recall = bedrock.requests.get(1);
        long resultMessages = recall.messages().stream()
                .filter(m -> m.content().stream().anyMatch(b -> b.toolResult() != null))
                .count();
        assertEquals(1, resultMessages,
                "T10: both tool results are batched into exactly one user message on the re-call");
        assertEquals(2, typesIn(sink).stream().filter("TOOL_RESULT"::equals).count(),
                "T9: each dispatched tool produces its own TOOL_RESULT event");
    }

    @Test
    @DisplayName("T9: an approved tool that fails yields a TOOL_RESULT(error), and the loop continues")
    void approvedToolFailureYieldsErrorResult() {
        // Oracle: state machine A T9 — "tool handler returns (incl. error)" -> append
        // TOOL_RESULT. 04-apis § 3 — a handler failure becomes an error tool result so the
        // model reacts (the registry maps a ToolInvocationException to an error result). The
        // loop must log a TOOL_RESULT with error status and continue (the tool ran, but
        // failed — distinct from a gate denial).
        ToolHandler failing = new ToolHandler() {
            @Override
            public String name() {
                return "run_command";
            }

            @Override
            public String description() {
                return "always fails";
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
                throw new com.srk.codingagent.tool.ToolInvocationException("command not found");
            }
        };
        ToolRegistry tools = ToolRegistry.of(List.of(failing));
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("running", "tu_e", "run_command", Map.of("command", "bogus")))
                .then(textTurn("that failed", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(sink, "t"));

        LoopOutcome outcome = loop.run("run bogus");

        assertTrue(sink.toString().contains("\"status\":\"error\""),
                "T9: an approved-but-failed tool yields a TOOL_RESULT(error) event");
        assertFalse(sink.toString().contains("\"status\":\"denied\""),
                "a tool failure is an error, not a denial (the gate approved it)");
        assertEquals(2, bedrock.callCount(), "T9 -> T10: the loop re-calls after the error result");
        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind());
    }

    @Test
    @DisplayName("§ 3.1: a multi-text-block end_turn joins the text blocks into the final answer")
    void endTurnJoinsMultipleTextBlocks() {
        // Oracle: § 3.1 / state machine A T3 — end_turn renders the final text. A turn with
        // two text blocks renders both, joined, as the final answer.
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock first =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText("line one");
        software.amazon.awssdk.services.bedrockruntime.model.ContentBlock second =
                software.amazon.awssdk.services.bedrockruntime.model.ContentBlock.fromText("line two");
        Message twoText = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(List.of(first, second))
                .build();
        ConverseResponse twoTextTurn = ConverseResponse.builder()
                .output(ConverseOutput.builder().message(twoText).build())
                .stopReason("end_turn")
                .usage(u -> u.inputTokens(40).outputTokens(8).totalTokens(48))
                .build();
        AgentLoop loop = loopWith(new ScriptedBedrockClient().then(twoTextTurn),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("speak");

        assertEquals("line one\nline two", outcome.finalText(),
                "§ 3.1: both text blocks of the final turn are rendered (joined)");
    }

    @Test
    @DisplayName("CT-SM-1: the loop iterates multiple tool-use cycles until end_turn")
    void multipleSequentialCycles() {
        // Oracle: state machine A loop body — "LOOP until stopReason == end_turn". Two
        // sequential tool_use turns then end_turn drives three model calls.
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "data");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("step 1", "tu_1", "read_file", Map.of("path", "a")))
                .then(toolUseTurn("step 2", "tu_2", "read_file", Map.of("path", "b")))
                .then(textTurn("complete", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(new StringWriter(), "t"));

        LoopOutcome outcome = loop.run("multi-step task");

        assertEquals(3, bedrock.callCount(), "the loop re-calls after each tool_use turn until end_turn");
        assertEquals("complete", outcome.finalText());
    }

    // --- Construction + input validation --------------------------------------------

    @Test
    @DisplayName("the loop requires all of its collaborators (composition contract)")
    void constructorRejectsNullCollaborators() {
        ModelClient client = new ModelClient(new ScriptedBedrockClient().then(textTurn("x", "end_turn")));
        ToolRegistry tools = ToolRegistry.of(List.of());
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        EventLog log = EventLog.over(new StringWriter(), "t");

        assertThrows(NullPointerException.class, () -> new AgentLoop(
                null, tools, gate, log, () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, null, gate, log, () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, null, log, () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, gate, null, () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, gate, log, null, BudgetGuard.NONE, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, gate, log, () -> TS, null, DISPOSER, MODEL_ID, null));
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, gate, log, () -> TS, BudgetGuard.NONE, null, MODEL_ID, null));
        assertThrows(IllegalArgumentException.class, () -> new AgentLoop(
                client, tools, gate, log, () -> TS, BudgetGuard.NONE, DISPOSER, "  ", null));
        // The 10-arg constructor also requires the compaction seam (the no-compaction wiring uses
        // CompactionSeam.NONE; null is rejected, matching the BudgetGuard.NONE contract).
        assertThrows(NullPointerException.class, () -> new AgentLoop(
                client, tools, gate, log, () -> TS, BudgetGuard.NONE, null, DISPOSER, MODEL_ID, null));
    }

    @Test
    @DisplayName("run rejects a null or blank prompt")
    void runRejectsBlankPrompt() {
        AgentLoop loop = loopWith(new ScriptedBedrockClient().then(textTurn("x", "end_turn")),
                ToolRegistry.of(List.of()), PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(new StringWriter(), "t"));

        assertThrows(NullPointerException.class, () -> loop.run(null));
        assertThrows(IllegalArgumentException.class, () -> loop.run("   "));
    }

    @Test
    @DisplayName("ADR-0005: every appended event carries the injected clock's timestamp, never Instant.now()")
    void usesInjectedClockForTimestamps() {
        // Oracle: ADR-0005 — the loop accepts an injected timestamp source and never calls
        // Instant.now(); every event it emits carries the injected ts verbatim. Assert the
        // emitted lines all carry the fixed clock value and none carry a different ts.
        ToolRegistry tools = ToolRegistry.of(List.of());
        StringWriter sink = new StringWriter();
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(textTurn("hi", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME, alwaysApprove(),
                EventLog.over(sink, "t"));

        loop.run("hello");

        List<String> lines = sink.toString().lines().toList();
        assertTrue(lines.stream().allMatch(l -> l.contains("\"ts\":\"" + TS + "\"")),
                "ADR-0005: every event carries the injected clock timestamp, not a derived one");
    }

    @Test
    @DisplayName("state machine A T13->T14: on COMPACT the loop invokes the compaction seam and continues in the derived session (NOT surfaced)")
    void budgetGuardCompactContinuesInDerivedSession() {
        // Oracle: AC-18.1 / state machine A T13->T14 — "when token usage reaches the threshold,
        // the agent shall compact ... and CONTINUE in a new derived conversation". The loop
        // consults the BudgetGuard after a turn; on COMPACT it invokes the CompactionSeam, and on a
        // CONTINUED result it continues driving in the derived transcript (NOT surfacing-and-
        // stopping — the obsolete T-0.8 behaviour). Expected: with a guard that says COMPACT on the
        // first turn and a seam that returns a derived transcript, the loop re-calls the model in
        // the derived session and completes there. The seam here is a controllable real
        // CompactionSeam (the lambda), not a mock of the SUT (AgentLoop) — the loop's continue-or-
        // surface dispatch is the SUT.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("compact me", "end_turn"))   // turn 1: triggers COMPACT before dispatch
                .then(textTurn("done in derived session", "end_turn")); // turn 2: in the derived session
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        // A guard that signals COMPACT once (the first turn) then CONTINUE, so the loop compacts
        // after turn 1 and completes after the derived-session turn.
        java.util.concurrent.atomic.AtomicInteger turnIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        BudgetGuard compactOnFirstTurn = usage ->
                turnIndex.getAndIncrement() == 0 ? BudgetGuard.Decision.COMPACT : BudgetGuard.Decision.CONTINUE;
        List<com.srk.codingagent.model.converse.ConverseMessage> derivedSeed = List.of(
                com.srk.codingagent.model.converse.ConverseMessage.user(
                        List.of(com.srk.codingagent.persistence.ContentBlock.text("[summary] continue here"))));
        CompactionSeam seam = (transcript, stopReason) -> CompactionSeam.CompactionResult.continued(derivedSeed);
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, compactOnFirstTurn, seam, DISPOSER, MODEL_ID, null);

        LoopOutcome outcome = loop.run("long task");

        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind(),
                "AC-18.1/T14: on COMPACT the loop continues in the derived session and completes there (NOT surfaced)");
        assertEquals(2, bedrock.callCount(),
                "T14: the loop re-calls the model in the derived session after compacting");
        assertEquals("done in derived session", outcome.finalText(),
                "T14: the loop drives to completion in the derived conversation");
    }

    @Test
    @DisplayName("state machine A T13->T14: the re-call after compaction carries the derived session's transcript, not the original's")
    void compactionContinuesWithDerivedTranscriptOnTheWire() {
        // Oracle: AC-18.4 / T14 — the loop CONTINUES "in a new derived conversation" carrying the
        // carried-forward context. After compaction, the next Converse request must be the derived
        // session's messages[] (the summary context block), not the original transcript. Assert
        // against the captured re-call request (the real wire), not impl state — the D2-class
        // discipline (assert the round-trip, not a field).
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("original-session answer", "end_turn"))
                .then(textTurn("derived-session answer", "end_turn"));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        java.util.concurrent.atomic.AtomicInteger turnIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        BudgetGuard compactOnFirstTurn = usage ->
                turnIndex.getAndIncrement() == 0 ? BudgetGuard.Decision.COMPACT : BudgetGuard.Decision.CONTINUE;
        String summarySentinel = "[COMPACTED-SUMMARY-SENTINEL] continue without re-explaining";
        List<com.srk.codingagent.model.converse.ConverseMessage> derivedSeed = List.of(
                com.srk.codingagent.model.converse.ConverseMessage.user(
                        List.of(com.srk.codingagent.persistence.ContentBlock.text(summarySentinel))));
        CompactionSeam seam = (transcript, stopReason) -> CompactionSeam.CompactionResult.continued(derivedSeed);
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, compactOnFirstTurn, seam, DISPOSER, MODEL_ID, null);

        loop.run("long task");

        ConverseRequest reCall = bedrock.requests.get(1);
        boolean carriesSummary = reCall.messages().stream()
                .flatMap(m -> m.content().stream())
                .map(b -> b.text())
                .filter(t -> t != null)
                .anyMatch(t -> t.contains("COMPACTED-SUMMARY-SENTINEL"));
        assertTrue(carriesSummary,
                "AC-18.4/T14: the re-call after compaction carries the derived session's summary context");
    }

    @Test
    @DisplayName("D4 (regression-of-T-2.8): a COMPACT on a tool_use turn hands the compaction seam a well-formed (toolUse/toolResult-paired) transcript, not a dangling toolUse (§ 6.A.1 / INV-6)")
    void compactionOnToolUseTurnReceivesWellFormedTranscript() {
        // Oracle: § 6.A.1 / INV-6 — the verified Bedrock Converse wire rule. "Every tool_use
        // block must have a corresponding tool_result block in the next message." Any messages[]
        // sent to Bedrock — INCLUDING the summarizer's verbatim replay during compaction — must
        // satisfy this pairing. State machine B LT1->LT2 pins WHEN compaction may run: between
        // COMPLETE turns; a tool_use turn is not complete until its tool_result is appended. So
        // when the budget guard returns COMPACT on a tool_use turn, the transcript handed to the
        // compaction seam MUST already carry the matching tool_result (no dangling tool_use that
        // a pairing-enforcing Bedrock rejects with the D4 ValidationException).
        //
        // This is the live-only regression a mocked Bedrock never replayed: the pre-fix loop
        // appended the assistant tool_use turn, evaluated the budget guard, and called
        // compaction.compact(transcript, ...) BEFORE appending the tool_result — so the captured
        // transcript ended in a dangling tool_use. The assertion below enforces the WIRE pairing
        // contract (the defect), so it FAILS against the pre-fix ordering and PASSES after the fix.
        // The seam is a controllable real CompactionSeam (the lambda) that captures the transcript
        // it is handed; the SUT is AgentLoop's ordering of dispatch vs. budget-seam.
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "file contents");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_d4", "read_file", Map.of("path", "x.txt")))
                .then(textTurn("done in derived session", "end_turn"));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        // COMPACT on the first (tool_use) turn, CONTINUE thereafter, so the loop compacts on the
        // tool_use turn and then completes in the derived session.
        java.util.concurrent.atomic.AtomicInteger turnIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        BudgetGuard compactOnFirstTurn = usage ->
                turnIndex.getAndIncrement() == 0 ? BudgetGuard.Decision.COMPACT : BudgetGuard.Decision.CONTINUE;
        // A real CompactionSeam (the lambda) that captures the transcript it is handed, then
        // CONTINUES (a benign derived seed) so the loop proceeds.
        java.util.concurrent.atomic.AtomicReference<List<com.srk.codingagent.model.converse.ConverseMessage>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<com.srk.codingagent.model.converse.ConverseMessage> derivedSeed = List.of(
                com.srk.codingagent.model.converse.ConverseMessage.user(
                        List.of(com.srk.codingagent.persistence.ContentBlock.text("[summary] continue here"))));
        CompactionSeam capturingSeam = (transcript, stopReason) -> {
            captured.set(transcript);
            return CompactionSeam.CompactionResult.continued(derivedSeed);
        };
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, compactOnFirstTurn, capturingSeam, DISPOSER, MODEL_ID, null);

        loop.run("read x.txt");

        List<com.srk.codingagent.model.converse.ConverseMessage> handed = captured.get();
        assertTrue(handed != null,
                "the compaction seam must have been invoked on the tool_use turn's COMPACT");
        assertToolUseToolResultPaired(handed);
    }

    /**
     * Enforces the § 6.A.1 / INV-6 wire pairing rule over a transcript: every {@code toolUse}
     * content block (carrying a {@code toolUseId}) must have its matching {@code toolResult}
     * (same {@code toolUseId}) in the IMMEDIATELY following message, and the transcript must not
     * end in a dangling {@code toolUse}. This is exactly what a pairing-enforcing Bedrock
     * enforces on any {@code messages[]} it receives — including the summarizer's verbatim replay
     * during compaction. The assertion asserts the structural wire contract (the D4 defect), not
     * that the loop reorders.
     */
    private static void assertToolUseToolResultPaired(
            List<com.srk.codingagent.model.converse.ConverseMessage> transcript) {
        for (int i = 0; i < transcript.size(); i++) {
            for (com.srk.codingagent.persistence.ContentBlock block : transcript.get(i).content()) {
                if (block instanceof com.srk.codingagent.persistence.ContentBlock.ToolUse toolUse) {
                    assertTrue(i + 1 < transcript.size(),
                            "§ 6.A.1/INV-6: toolUse '" + toolUse.toolUseId() + "' must be followed by a "
                                    + "message carrying its toolResult — it is the last message (dangling "
                                    + "tool_use), which live Bedrock rejects (the D4 ValidationException)");
                    boolean nextCarriesMatchingResult = transcript.get(i + 1).content().stream()
                            .filter(b -> b instanceof com.srk.codingagent.persistence.ContentBlock.ToolResult)
                            .map(b -> ((com.srk.codingagent.persistence.ContentBlock.ToolResult) b).toolUseId())
                            .anyMatch(id -> toolUse.toolUseId().equals(id));
                    assertTrue(nextCarriesMatchingResult,
                            "§ 6.A.1/INV-6: the message immediately after toolUse '" + toolUse.toolUseId()
                                    + "' must carry a toolResult with the same toolUseId");
                }
            }
        }
    }

    @Test
    @DisplayName("state machine A T13->T15: a failed compaction (seam surfaces) stops the loop with the surfaced stop reason")
    void compactionFailureSurfacesForExitFive() {
        // Oracle: CT-SM-7 / state machine A T15 — "compaction failure path exits 5". When the
        // compaction seam returns a SURFACED result (the summary/derive could not recover context,
        // LT4->LT7), the loop must stop with that surfaced stop reason so the one-shot boundary maps
        // it to exit 5. The seam surfaces MODEL_CONTEXT_WINDOW_EXCEEDED (OneShotRunner maps that to
        // 5). Assert the loop returns SURFACED carrying that reason and does not re-call.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("about to overflow", "end_turn"));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        CompactionSeam failingSeam = (transcript, stopReason) ->
                CompactionSeam.CompactionResult.surfaced(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED);
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, usage -> BudgetGuard.Decision.COMPACT, failingSeam, DISPOSER, MODEL_ID, null);

        LoopOutcome outcome = loop.run("long task");

        assertEquals(1, bedrock.callCount(),
                "T15: a failed compaction does not re-call the model; it surfaces and stops");
        assertEquals(LoopOutcome.Kind.SURFACED, outcome.kind(),
                "CT-SM-7/T15: a compaction failure surfaces (so the boundary exits 5)");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, outcome.stopReason(),
                "CT-SM-7: the surfaced reason is the context-exhausted reason OneShotRunner maps to exit 5");
    }

    @Test
    @DisplayName("CompactionSeam.NONE: the no-compaction wiring surfaces the turn's stop reason on COMPACT, after the tool_use turn completes (BudgetGuard.NONE never reaches it)")
    void compactionSeamNoneSurfaces() {
        // Oracle: the no-compaction wiring is BudgetGuard.NONE + CompactionSeam.NONE — the analogue
        // of the prior surface-on-COMPACT behaviour. CompactionSeam.NONE returns a SURFACED result
        // carrying the turn's stop reason, so if a guard ever returns COMPACT with NONE wired, the
        // loop surfaces rather than continuing (defensive: with BudgetGuard.NONE the seam is never
        // consulted, but the contract is that NONE surfaces). Drive a guard that says COMPACT with
        // the 9-arg constructor (which defaults the seam to CompactionSeam.NONE).
        //
        // The budget seam is consulted at a COMPLETE-turn boundary (state machine B LT1 -> LT2 —
        // "a turn completes" then the threshold check; T13's source S1/S0 -> S6 is between
        // complete turns). A tool_use turn is NOT complete until its toolResult is appended
        // (§ 6.A.1 / INV-6 — every toolUse must be followed by its matching toolResult). So on a
        // tool_use turn the loop first completes the turn (dispatches the tool, appends the
        // toolResult), THEN consults the budget seam on the now well-formed transcript: the tool
        // therefore RAN before NONE surfaces. (This is exactly the D4 ordering: complete the turn
        // before the seam so compaction never sees a dangling toolUse.)
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "data");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_1", "read_file", Map.of("path", "x")));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        // 9-arg constructor: no compaction seam -> defaults to CompactionSeam.NONE.
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, usage -> BudgetGuard.Decision.COMPACT, DISPOSER, MODEL_ID, null);

        LoopOutcome outcome = loop.run("read x");

        assertTrue(tool.ran.get(),
                "LT1/§ 6.A.1: the tool_use turn completes (tool runs, toolResult appended) before "
                        + "the budget seam is consulted on the well-formed transcript");
        assertEquals(LoopOutcome.Kind.SURFACED, outcome.kind(),
                "CompactionSeam.NONE surfaces the turn's stop reason (the no-compaction wiring)");
        // CompactionSeam.NONE surfaces the triggering turn's stop reason verbatim; the scripted
        // turn here is a tool_use turn, so the surfaced reason is TOOL_USE (the no-compaction
        // contract: NONE is a pass-through of the turn's stop reason, not a fixed value).
        assertEquals(StopReason.TOOL_USE, outcome.stopReason(),
                "CompactionSeam.NONE surfaces the triggering turn's stop reason verbatim");
        // The scripted model has exactly one turn; the loop surfaces on that turn (NONE does not
        // continue), so it never re-calls.
        assertEquals(1, bedrock.callCount(),
                "CompactionSeam.NONE surfaces on the triggering turn and does not re-call");
    }

    @Test
    @DisplayName("§ 2: the system prompt is sent on the model call when provided")
    void systemPromptIsSent() {
        // Oracle: 02-architecture.md § 2 — the loop calls Converse(messages, system,
        // toolConfig). A configured system prompt must reach the request the model client
        // builds. Verify against the captured request, not impl state.
        ToolRegistry tools = ToolRegistry.of(List.of());
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(textTurn("hi", "end_turn"));
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession("root"), alwaysApprove());
        AgentLoop loop = new AgentLoop(modelClient, tools, gate, EventLog.over(new StringWriter(), "t"),
                () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, List.of("You are a coding agent."));

        loop.run("hi");

        assertTrue(bedrock.requests.get(0).hasSystem(),
                "§ 2: the system prompt reaches the Converse request");
        assertEquals("You are a coding agent.", bedrock.requests.get(0).system().get(0).text());
    }

    // --- T-1.5 output disposal wired into the loop (US-19, ADR-0006) ------------------

    /** The {@code text} of the first toolResult content block carried on the re-call request. */
    private static String reCallToolResultText(ScriptedBedrockClient bedrock) {
        ConverseRequest recall = bedrock.requests.get(1);
        return recall.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b.toolResult() != null)
                .flatMap(b -> b.toolResult().content().stream())
                .map(c -> c.text())
                .filter(t -> t != null)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("US-19/AC-19.1: a >16KB tool output is reduced (head+tail) on the re-call, not inlined whole")
    void oversizedOutputReducedForContext() {
        // Oracle: AC-19.1 — output exceeding NFR-OUTPUT-MAX-INLINE (16384 bytes default) must be
        // reduced before entering context. A read_file returning >16KB must reach the model as a
        // head+tail reduction with a truncation marker, not the whole output. Assert against the
        // captured re-call request (what enters context), not impl state.
        String head = "FILE-HEAD-SENTINEL";
        String tail = "FILE-TAIL-SENTINEL";
        String huge = head + "z".repeat(20_000) + tail; // > 16384 bytes
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, huge);
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_big", "read_file", Map.of("path", "big.txt")))
                .then(textTurn("read it", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        loop.run("read big.txt");

        String contextText = reCallToolResultText(bedrock);
        assertTrue(contextText != null, "the re-call must carry the tool result as a text block");
        assertTrue(contextText.length() < huge.length(),
                "AC-19.1: the output entering context must be reduced, not the whole 20KB output");
        assertTrue(contextText.startsWith(head),
                "ADR-0006: the reduction keeps the head of the output");
        assertTrue(contextText.endsWith(tail),
                "ADR-0006: the reduction keeps the tail (failures are legible from the tail)");
        assertTrue(contextText.contains("truncated") && contextText.contains("evt:"),
                "AC-19.1/19.2: the reduction carries a truncated marker pointing at the fullRef");
    }

    @Test
    @DisplayName("US-19/AC-19.1: a small (<16KB) tool output enters context whole (not reduced)")
    void smallOutputNotReduced() {
        // Oracle: AC-19.1 only reduces output EXCEEDING the cap; a small output must reach the
        // model unchanged. Assert the re-call carries the exact small output, with no truncation
        // marker.
        String small = "small file contents";
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, small);
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_s", "read_file", Map.of("path", "s.txt")))
                .then(textTurn("done", "end_turn"));
        AgentLoop loop = loopWith(bedrock, tools, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), EventLog.over(new StringWriter(), "t"));

        loop.run("read s.txt");

        String contextText = reCallToolResultText(bedrock);
        assertEquals(small, contextText,
                "AC-19.1: a small output (under the cap) enters context whole, unchanged");
        assertFalse(contextText.contains("truncated"),
                "a small output carries no truncation marker (nothing was reduced)");
    }

    @Test
    @DisplayName("AC-19.2/19.3: a reduced output is full-persisted to the log and retrievable via its fullRef")
    void oversizedOutputFullPersistedAndRetrievable(@TempDir Path dir) {
        // Oracle: AC-19.2 — the FULL output is persisted to the session log; AC-19.3 — it is
        // retrievable from the log via the fullRef rather than re-running the command. This is
        // the G1-cycle round-trip the live smoke test depends on: dispose -> persist -> retrieve
        // -> equals the original. Drive the loop over a real SessionStore-backed log so the full
        // output is on disk, then resolve the fullRef the model received against that same log.
        String head = "BIG-OUT-HEAD";
        String tail = "BUILD FAILED at the very end";
        String huge = head + "Q".repeat(30_000) + tail; // > 16384 bytes
        RecordingTool tool = new RecordingTool("run_command", OperationClass.SIDE_EFFECTING, huge);
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("building", "tu_b", "run_command", Map.of("command", "mvn test")))
                .then(textTurn("checked", "end_turn"));

        SessionStore store = new SessionStore(dir);
        String repoKey = "repo-1";
        String sessionId = "one-shot";
        ModelClient modelClient = new ModelClient(bedrock);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(sessionId), alwaysApprove());
        try (EventLog log = store.openLog(repoKey, sessionId)) {
            AgentLoop loop = new AgentLoop(modelClient, tools, gate, log,
                    () -> TS, BudgetGuard.NONE, DISPOSER, MODEL_ID, null);
            loop.run("run the build");
        }

        // The fullRef the model received in the reduced content (extracted from the marker).
        String contextText = reCallToolResultText(bedrock);
        String fullRef = extractFullRef(contextText);
        assertTrue(fullRef != null, "the reduced content must name a fullRef (evt:<seq>) to retrieve");

        Object retrieved = new OutputRetrieval(store).retrieve(repoKey, sessionId, fullRef).orElse(null);
        assertEquals(huge, retrieved,
                "AC-19.2/19.3: the full output is persisted and retrievable byte-for-byte via the fullRef");
    }

    /** Pulls the {@code evt:<seq>} pointer out of a reduction's truncation marker, or null. */
    private static String extractFullRef(String reduced) {
        if (reduced == null) {
            return null;
        }
        int at = reduced.indexOf("evt:");
        if (at < 0) {
            return null;
        }
        int end = at + "evt:".length();
        while (end < reduced.length() && Character.isDigit(reduced.charAt(end))) {
            end++;
        }
        return reduced.substring(at, end);
    }
}
