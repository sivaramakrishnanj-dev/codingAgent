package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;

/**
 * Live-reachability contract for the production tool composition (T-2.7 regression of
 * T-2.3/T-2.4): the registry the production composition root ({@link AgentLoopFactory} via
 * {@link ToolRegistryComposer}) actually builds on a real {@code codingagent} run must offer the
 * model all THREE M2 tools — {@code spawn_subagent} (C13, ADR-0010), {@code read_memory} and
 * {@code write_memory} (C12, ADR-0007) — alongside the seven file/search/run tools, each with the
 * operation class the gate keys on and each rendering a Converse toolSpec.
 *
 * <p>This is the test that would have caught the wiring gap: the factory composed only the seven
 * file/search/run tools and never registered the sub-agent or memory tools, and that class is
 * JaCoCo-excluded so no unit test exercised it. The composition logic is extracted into
 * {@link ToolRegistryComposer} (NOT coverage-excluded), and this test drives the SAME composer the
 * factory drives — over a {@link ModelClient} backed by a scripted Bedrock double and stores rooted
 * at a {@link TempDir} — so the wiring is now pinned under the coverage gate.
 *
 * <p><b>SUT.</b> The SUT is the real {@link ToolRegistryComposer} and the real registry +
 * orchestrator + nested {@link com.srk.codingagent.loop.AgentLoop} it composes. The only test
 * double is a hand-rolled {@link ScriptedBedrockClient} replaying scripted {@link ConverseResponse}s
 * — the same Bedrock double pattern {@code SubAgentOrchestratorTest} and {@code AgentLoopTest} use.
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, not to the composer's code:
 * <ul>
 *   <li><b>C7 (02-architecture &sect; 1.2):</b> the registry renders to Converse {@code toolConfig};
 *       a tool's schema and handler agree.</li>
 *   <li><b>ADR-0007:</b> {@code read_memory} is Class R (READ), {@code write_memory} is Class X
 *       (SIDE_EFFECTING).</li>
 *   <li><b>ADR-0010 / AC-17.1:</b> {@code spawn_subagent} is Class X (SIDE_EFFECTING) and runs a
 *       real nested loop returning a summary-only result (AC-17.4, INV-11).</li>
 *   <li><b>D2 (T-0.5-RD2):</b> the child reuses the same {@link ModelClient} wire path — a
 *       plain-string toolResult lands in the Converse {@code text} member, never {@code json}.</li>
 * </ul>
 */
class LiveToolRegistryCompositionTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-22T09:00:00Z";

    /** The seven file/search/run tools the factory has always offered. */
    private static final List<String> FILE_SEARCH_RUN_TOOLS = List.of(
            "read_file", "grep", "glob", "list", "write_file", "edit_file", "run_command");

    // --- Scripted external Bedrock dependency (the only external double) ------------------

    /** A {@link BedrockRuntimeClient} replaying a scripted queue of responses, one per call. */
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
            // no-op
        }
    }

    private static ConverseResponse toolUseTurn(String text, String toolUseId, String tool,
            Map<String, String> input) {
        Document.MapBuilder in = Document.mapBuilder();
        input.forEach(in::putString);
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

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300, 10, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory#create} builds it — same collaborator
     * wiring (the parent grant store shared, the boundary clock + child-id supplier captured) — but
     * over a {@link ModelClient} backed by the scripted Bedrock and stores rooted at the temp dir.
     * This is the production composition path; only Bedrock and the store roots are test-controlled.
     */
    private static ToolRegistryComposer composer(
            BedrockRuntimeClient bedrock, Path workspace, Path storeRoot, EventLog log) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(bedrock), config(), workspace, log,
                new MemoryStore(storeRoot), new SessionStore(storeRoot),
                GrantStore.forSession(LINEAGE), alwaysApprove(), LINEAGE, LINEAGE,
                () -> TS, childIds);
    }

    // --- The wiring contract: all ten tools register, render, report classes -------------

    @Test
    @DisplayName("C7/ADR-0007/ADR-0010/ADR-0008: the live registry contains all twelve tools (the 7 + memory + spawn + web)")
    void liveRegistryContainsAllTwelveTools(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: C7 (02-architecture § 1.2 — the registry holds the tool definitions a live run
        // offers) + ADR-0007 (read_memory/write_memory exist) + ADR-0010 (spawn_subagent exists) +
        // ADR-0008 (web_search/web_fetch exist). The factory's registry must expose every tool a live
        // run offers; the T-2.7 lesson is that an implemented-but-unregistered tool is invisible at
        // runtime. The registry rejects duplicate names, so a clean composition proves the names coexist.
        var registry = composer(new ScriptedBedrockClient(), workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        List<String> expected = new ArrayList<>(FILE_SEARCH_RUN_TOOLS);
        expected.add("read_memory");
        expected.add("write_memory");
        expected.add("spawn_subagent");
        expected.add("web_search");
        expected.add("web_fetch");
        assertTrue(registry.toolNames().containsAll(expected),
                "the live registry offers all twelve tools: " + registry.toolNames());
        assertEquals(12, registry.toolNames().size(),
                "exactly the twelve production tools register (no more, no fewer): " + registry.toolNames());
    }

    @Test
    @DisplayName("C7: every tool the live registry holds renders a Converse toolSpec the model sees")
    void liveRegistryRendersToolSpecForEveryTool(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: C7 — "render to Converse toolConfig". A tool not rendered is a tool the model
        // never sees. Every registered tool must render a toolSpec carrying a JSON inputSchema (the
        // schema⇄handler-agree invariant).
        var registry = composer(new ScriptedBedrockClient(), workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        ToolConfiguration toolConfig = registry.toToolConfiguration();
        assertEquals(12, toolConfig.tools().size(),
                "every registered tool renders a toolSpec (C7)");
        toolConfig.tools().forEach(tool ->
                assertNotNull(tool.toolSpec().inputSchema().json(),
                        "each toolSpec carries a JSON inputSchema (C7 schema⇄handler agree)"));
    }

    @Test
    @DisplayName("ADR-0007/ADR-0010: the live registry reports each M2 tool's gate class (read_memory=READ, write_memory=X, spawn_subagent=X)")
    void liveRegistryReportsM2OperationClasses(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: ADR-0007 — "read_memory/write_memory are Class R / Class X tools (reads free,
        // writes gated)"; ADR-0010 — spawn_subagent is "Class X, gated". The registry is the single
        // source the loop's gate keys on, so a wrong class would gate a read or wave a write
        // through. Expected classes trace to those ADRs, not to the tool classes' code.
        var registry = composer(new ScriptedBedrockClient(), workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        assertEquals(Optional.of(OperationClass.READ), registry.operationClass("read_memory"),
                "ADR-0007: read_memory is Class R (a read is free)");
        assertEquals(Optional.of(OperationClass.SIDE_EFFECTING), registry.operationClass("write_memory"),
                "ADR-0007: write_memory is Class X (a write is gated)");
        assertEquals(Optional.of(OperationClass.SIDE_EFFECTING), registry.operationClass("spawn_subagent"),
                "ADR-0010: spawn_subagent is Class X (gated)");
    }

    @Test
    @DisplayName("ADR-0008/RD-6/AC-11.2: the live registry reports web_search and web_fetch as Class X (so READ_ONLY denies them)")
    void liveRegistryReportsWebLookupClassX(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: ADR-0008 / 04-apis § 3 — web_search/web_fetch are Class X; RD-6 / AC-11.2 — Class X
        // is denied in READ_ONLY. The registry is the single source the loop's gate keys on, so the
        // web tools MUST report SIDE_EFFECTING for the READ_ONLY denial to hold on a live run. The
        // T-2.7 lesson: the tools must actually be registered in the live composer to be reachable.
        var registry = composer(new ScriptedBedrockClient(), workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        assertEquals(Optional.of(OperationClass.SIDE_EFFECTING), registry.operationClass("web_search"),
                "ADR-0008/RD-6: web_search is Class X (gated; denied in READ_ONLY)");
        assertEquals(Optional.of(OperationClass.SIDE_EFFECTING), registry.operationClass("web_fetch"),
                "ADR-0008/RD-6: web_fetch is Class X (gated; denied in READ_ONLY)");
    }

    // --- The sub-agent path's REAL wire contract -----------------------------------------

    @Test
    @DisplayName("AC-17.1/AC-17.4/INV-11: dispatching spawn_subagent runs a real nested loop and returns a summary-only result")
    void spawnSubagentDispatchRunsRealNestedLoopAndReturnsSummary(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-17.1 ("spawn a sub-agent to perform [a subtask] and return a summarized
        // result") + AC-17.4/INV-11 ("incorporate only the summarized result"). Driving the
        // spawn_subagent tool the live registry composed must run a REAL nested AgentLoop over the
        // real ModelClient wire path (a child that completes its turn) and hand back ONLY the
        // child's final answer. The summary text traces to the scripted child turn + the AC, not to
        // composer internals. The gate auto-approves (ASK_EVERY_TIME + always-approve) so the
        // Class-X spawn dispatches.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("Subtask done: 2 files inspected.", "end_turn"));
        var registry = composer(bedrock, workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        ContentBlock.ToolResult result = registry.dispatch(ContentBlock.toolUse(
                "tu_spawn", "spawn_subagent", Map.of("prompt", "do the well-scoped subtask")));

        assertEquals("ok", result.status(),
                "AC-17.1: a child that ends its turn dispatches as an ok tool result");
        assertEquals("Subtask done: 2 files inspected.", result.content(),
                "AC-17.4/INV-11: the parent receives the child's final answer as the summary, "
                        + "not its transcript");
    }

    @Test
    @DisplayName("AC-17.2/D2: the child sub-agent runs its OWN nested loop over the SAME ModelClient wire path (plain-string toolResult to text, not json)")
    void childSubagentReusesD2SafeWirePath(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-17.2 — the child operates with its own context (a real nested loop runs its own
        // tool-use cycle); D2 (T-0.5-RD2) — a plain-string toolResult must map to the Converse text
        // member (routing a String into json caused the real-Bedrock ValidationException). The child
        // is built over the SAME ModelClient as the parent (reuse by construction), so this asserts
        // the real wire contract — not field presence. Drive the child read_file → toolResult →
        // end_turn cycle and verify the re-call request carries the toolResult on the TEXT member.
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_child_read", "read_file", Map.of("path", "x.txt")))
                .then(textTurn("done reading x.txt", "end_turn"));
        // The child read_file tool reads from the workspace; seed a file so the read returns content.
        writeWorkspaceFile(workspace, "x.txt", "plain string file body");
        var registry = composer(bedrock, workspace, storeRoot,
                EventLog.over(new StringWriter(), "parent")).parentRegistry();

        registry.dispatch(ContentBlock.toolUse(
                "tu_spawn", "spawn_subagent", Map.of("prompt", "read x.txt and summarize")));

        // The child's re-call (request #2) carries the read_file toolResult on the TEXT member,
        // never the json member (the D2 contract). request #1 is the child's first model call.
        assertTrue(bedrock.requests.size() >= 2,
                "AC-17.2: the child ran a real nested tool-use cycle (>= 2 Converse calls)");
        ConverseRequest recall = bedrock.requests.get(1);
        var toolResultBlock = recall.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b.toolResult() != null && "tu_child_read".equals(b.toolResult().toolUseId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the child's re-call must carry its toolResult"));
        var content = toolResultBlock.toolResult().content().get(0);
        assertNotNull(content.text(),
                "D2: a plain-string toolResult routes to the Converse text member");
        assertNull(content.json(),
                "D2: a plain-string toolResult does NOT route to the json member (the D2 bug)");
    }

    @Test
    @DisplayName("AC-17.5/INV-11: a spawned child writes its transcript to its OWN log; the parent log gets only the spawn+result events")
    void childTranscriptStaysInChildLogParentGetsOnlySummary(
            @TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-17.5 ("record sub-agent spawns and results as events") + INV-11 ("the parent
        // context receives only its summary, never its event stream"). The orchestrator the composer
        // wires opens the CHILD's own log from the SessionStore (the child never shares the parent's
        // log, ADR-0010 Notes). The PARENT log must carry only SUBAGENT_SPAWN/SUBAGENT_RESULT; the
        // child's per-turn events live in the child's own session log.
        StringWriter parentSink = new StringWriter();
        EventLog parentLog = EventLog.over(parentSink, "parent");
        SessionStore sessionStore = new SessionStore(storeRoot);
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_c1", "read_file", Map.of("path", "y.txt")))
                .then(textTurn("Child final summary.", "end_turn"));
        writeWorkspaceFile(workspace, "y.txt", "y contents");
        var registry = new ToolRegistryComposer(
                new ModelClient(bedrock), config(), workspace, parentLog,
                new MemoryStore(storeRoot), sessionStore, GrantStore.forSession(LINEAGE),
                alwaysApprove(), LINEAGE, LINEAGE, () -> TS, () -> CHILD_ID).parentRegistry();

        registry.dispatch(ContentBlock.toolUse(
                "tu_spawn", "spawn_subagent", Map.of("prompt", "read y.txt and summarize")));

        // The PARENT log carries ONLY the two sub-agent edge events — not the child's per-turn
        // USER_MESSAGE/MODEL_RESPONSE/TOOL_USE/TOOL_RESULT.
        assertEquals(List.of("SUBAGENT_SPAWN", "SUBAGENT_RESULT"), typesIn(parentSink.toString()),
                "INV-11: the parent log receives only the spawn + summary, never the child's events");
        // The child's full transcript lives in the child's OWN session log.
        List<String> childTypes = childLogTypes(sessionStore, CHILD_ID);
        assertTrue(childTypes.contains("TOOL_USE") && childTypes.contains("TOOL_RESULT"),
                "AC-17.5: the child's transcript (TOOL_USE/TOOL_RESULT) lives in the CHILD's own log");
    }

    @Test
    @DisplayName("read_memory/write_memory: dispatching the live registry's memory tools reaches the real handlers over the wired store")
    void memoryToolsDispatchReachesRealHandlers(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-12.1 ("when the developer instructs the agent to remember a fact... write it as
        // a memory entry") + AC-14.2/INV-14 ("re-read from disk on each load"). The live registry's
        // write_memory then read_memory must reach the REAL handlers over the SAME wired MemoryStore:
        // a write followed by a read of the same slug returns the body just written (re-read fresh).
        EventLog log = EventLog.over(new StringWriter(), "parent");
        var registry = composer(new ScriptedBedrockClient(), workspace, storeRoot, log)
                .parentRegistry();

        ContentBlock.ToolResult write = registry.dispatch(ContentBlock.toolUse(
                "tu_w", "write_memory", Map.of(
                        "slug", "prefer-tabs",
                        "tier", "PROJECT",
                        "why", "team style",
                        "body", "Use tabs for indentation.")));
        assertEquals("ok", write.status(), "AC-12.1: an explicit remember write dispatches ok");

        ContentBlock.ToolResult read = registry.dispatch(ContentBlock.toolUse(
                "tu_r", "read_memory", Map.of("slug", "prefer-tabs")));
        assertEquals("ok", read.status(), "the read of the just-written slug dispatches ok");
        assertTrue(String.valueOf(read.content()).contains("Use tabs for indentation."),
                "AC-14.2/INV-14: read_memory re-reads from the SAME store the write_memory tool wrote to");
    }

    // --- helpers -------------------------------------------------------------------------

    private static void writeWorkspaceFile(Path workspace, String name, String content) {
        try {
            java.nio.file.Files.writeString(workspace.resolve(name), content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to seed workspace file " + name, e);
        }
    }

    private static List<String> typesIn(String jsonl) {
        List<String> types = new ArrayList<>();
        for (String line : jsonl.lines().toList()) {
            int i = line.indexOf("\"type\":\"");
            if (i >= 0) {
                int start = i + "\"type\":\"".length();
                types.add(line.substring(start, line.indexOf('"', start)));
            }
        }
        return types;
    }

    private static List<String> childLogTypes(SessionStore store, String childSessionId) {
        List<String> types = new ArrayList<>();
        for (Event e : store.readEvents(LINEAGE, childSessionId)) {
            types.add(e.type().name());
        }
        return types;
    }
}
