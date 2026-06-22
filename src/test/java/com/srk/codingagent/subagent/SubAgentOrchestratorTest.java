package com.srk.codingagent.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.BudgetGuard;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GateRequest;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.persistence.SubAgentResultPayload;
import com.srk.codingagent.persistence.SubAgentSpawnPayload;
import com.srk.codingagent.tool.ToolHandler;
import com.srk.codingagent.tool.ToolRegistry;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Unit tests for {@link SubAgentOrchestrator} — component C13, ADR-0010: the sub-agent
 * orchestrator that runs a nested {@link AgentLoop} with isolated context, a fresh
 * (no-inherited-grants) permission gate, a wall-clock budget, and summary-only propagation.
 *
 * <p><b>SUT and collaborators.</b> The SUT is a real {@link SubAgentOrchestrator}. For the
 * tests that exercise the real wire path the orchestrator drives a <em>real</em> nested
 * {@link AgentLoop} (over a real {@link ModelClient} + its real {@code ConverseWireMapper}, a
 * real {@link ToolRegistry}, a real {@link PermissionGate} with a fresh {@link GrantStore},
 * and a real {@link EventLog}); the only test double is a hand-rolled
 * {@link ScriptedBedrockClient} replaying scripted {@link ConverseResponse}s — the same Bedrock
 * double pattern {@code AgentLoopTest} uses. For the bound and budget tests the child loop is
 * a small controllable {@link ChildAgentLoopFactory} (a real seam, not a mock of the SUT).
 *
 * <p><b>Oracles.</b> Expected values trace to the spec, not to the orchestrator's code:
 * <ul>
 *   <li><b>CT-INV-9 / INV-10 / AC-10.6:</b> a grant remembered in the parent's store is NOT
 *       readable by the child (the child gets {@code GrantStore.forSubAgent}).</li>
 *   <li><b>CT-INV-10 / INV-12 / AC-17.3:</b> concurrent children never exceed
 *       NFR-SUBAGENT-MAX (default 1); an over-the-bound spawn is rejected, not run.</li>
 *   <li><b>INV-11 / AC-17.4:</b> the parent receives ONLY the summary; the child's per-turn
 *       events are NOT projected into the parent's log/context.</li>
 *   <li><b>AC-17.5:</b> SUBAGENT_SPAWN/RESULT events are recorded in the PARENT's log, and the
 *       child's session meta carries edgeType = SPAWNED_BY.</li>
 *   <li><b>AC-17.6:</b> an over-budget or failed child yields a failure result (never hangs);
 *       the budget is injected so the over-budget path is deterministic.</li>
 *   <li><b>D2 (T-0.5-RD2):</b> a child tool-use cycle's toolResult round-trips through the
 *       real wire path — a plain-string toolResult lands in the Converse {@code text} member,
 *       never {@code json}.</li>
 * </ul>
 */
class SubAgentOrchestratorTest {

    private static final String REPO = "github.com/example/widget";
    private static final String PARENT_LINEAGE = "parent-session";
    private static final String PARENT_MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_MODEL = "anthropic.claude-haiku-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-22T09:00:00Z";

    private static final OutputDisposer DISPOSER = new OutputDisposer(16384);

    // --- Scripted external Bedrock dependency (the only external double) -----------------

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

    private static Document minimalSchema() {
        return Document.mapBuilder().putString("type", "object").build();
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

    /** A real read-class tool that returns a plain-string result and records that it ran. */
    private static final class RecordingTool implements ToolHandler {
        private final String name;
        private final OperationClass operationClass;
        private final Object result;
        private boolean ran;

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
            ran = true;
            return result;
        }
    }

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
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

    /**
     * A child-loop factory that runs a REAL nested {@link AgentLoop} over the scripted Bedrock —
     * the same wire path the parent uses (D2-safe by construction). The returned
     * {@link ChildAgentRun} is {@code () -> realLoop.run(prompt)}, exactly the production shape.
     */
    private static ChildAgentLoopFactory realChildLoopFactory(
            BedrockRuntimeClient bedrock, ToolRegistry tools) {
        return ctx -> {
            AgentLoop childLoop = new AgentLoop(
                    new ModelClient(bedrock), tools, ctx.childGate(), ctx.childLog(),
                    () -> TS, BudgetGuard.NONE, DISPOSER, ctx.modelId(), null);
            return () -> childLoop.run(ctx.prompt());
        };
    }

    private SubAgentOrchestrator orchestrator(
            SessionStore store, EventLog parentLog, GrantStore parentGrants,
            ChildAgentLoopFactory childLoopFactory, int subAgentMax, Duration cap) {
        return new SubAgentOrchestrator(
                store, parentLog, childLoopFactory, parentGrants, PermissionMode.ASK_EVERY_TIME,
                alwaysApprove(), REPO, PARENT_MODEL, () -> CHILD_ID, () -> TS, subAgentMax, cap);
    }

    // --- AC-17.1 / AC-17.4 : spawn -> summarized result ------------------------------

    @Test
    @DisplayName("AC-17.1/AC-17.4: a spawned sub-agent returns its completed summary to the parent")
    void spawnReturnsCompletedSummary(@TempDir Path storeRoot) {
        // Oracle: AC-17.1 ("spawn a sub-agent to perform [a subtask] and return a summarized
        // result") + AC-17.4 ("incorporate only the summarized result"). The child's end_turn
        // final text IS the summary. Expected text traces to the scripted child turn + the AC,
        // not to orchestrator internals.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("Subtask done: 3 files updated.", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("Do the well-scoped subtask");

        assertTrue(result.success(), "AC-17.1: a child that ends its turn completes successfully");
        assertEquals("Subtask done: 3 files updated.", result.summary(),
                "AC-17.4: the parent receives the child's final answer as the summary");
        assertEquals(CHILD_ID, result.childSessionId(), "the result names the child's own session");
        assertEquals(EdgeType.SPAWNED_BY, result.edgeType(),
                "AC-17.5/INV-11: the child is linked SPAWNED_BY the parent");
    }

    // --- CT-INV-9 / INV-10 / AC-10.6 : no inherited grants ----------------------------

    @Test
    @DisplayName("CT-INV-9/INV-10/AC-10.6: a grant remembered in the parent is NOT readable by the child")
    void childDoesNotInheritParentGrants(@TempDir Path storeRoot) {
        // Oracle: CT-INV-9 / INV-10 / AC-10.6 — "a remembered grant... is NOT inherited by a
        // sub-agent (RD-5)". Remember a command grant in the PARENT store; the child gate the
        // orchestrator builds must consult a store where that grant is absent. We assert via a
        // probe gate captured from the child context: the child's grant store finds no match
        // for the parent's remembered key.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        GrantStore parentGrants = GrantStore.forSession(PARENT_LINEAGE);
        // A grant remembered in the PARENT store (the GrantStore public API takes the normalized
        // match key as a string; the literal stands in for any remembered Class-X grant key).
        String rememberedKey = "run_command:npm test";
        parentGrants.remember(rememberedKey);

        List<GrantStore> capturedChildStores = new ArrayList<>();
        ChildAgentLoopFactory capturingFactory = ctx -> {
            // The orchestrator mints the child's fresh store via parentGrants.forSubAgent and
            // wires it into ctx.childGate(); we mint the same kind of child store here to assert
            // its emptiness — the forSubAgent contract is the no-inherit boundary (INV-10).
            GrantStore childStore = parentGrants.forSubAgent(ctx.childSessionId());
            capturedChildStores.add(childStore);
            // A real nested loop over a scripted Bedrock that immediately completes.
            ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                    .then(textTurn("done", "end_turn"));
            AgentLoop childLoop = new AgentLoop(new ModelClient(bedrock), ToolRegistry.of(List.of()),
                    ctx.childGate(), ctx.childLog(), () -> TS, BudgetGuard.NONE, DISPOSER,
                    ctx.modelId(), null);
            return () -> childLoop.run(ctx.prompt());
        };
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, parentGrants, capturingFactory, 1, Duration.ofSeconds(30));

        orch.spawn("subtask");

        // The parent still holds its grant...
        assertNotNull(parentGrants.findExact(rememberedKey),
                "the parent's own remembered grant is unaffected");
        assertEquals(1, parentGrants.size(), "the parent store keeps exactly its one grant");
        // ...but the child's fresh store inherited none of it (INV-10/CT-INV-9).
        assertEquals(1, capturedChildStores.size(), "the child loop was built once");
        GrantStore childStore = capturedChildStores.get(0);
        assertEquals(0, childStore.size(),
                "CT-INV-9/INV-10: the child starts with a brand-new EMPTY grant store");
        assertNull(childStore.findExact(rememberedKey),
                "CT-INV-9/INV-10/AC-10.6: the parent's remembered grant is NOT readable by the child");
    }

    @Test
    @DisplayName("INV-10: the child's gate prompts for a Class-X op the parent had remembered (fresh mode)")
    void childGatePromptsForOpParentRemembered(@TempDir Path storeRoot) {
        // Oracle: INV-10 / AC-10.6 — the child runs the configured mode FRESH. Under
        // ASK_ONCE_THEN_REMEMBER, a command the PARENT gate remembered (first prompt-then-grant)
        // auto-approves in the parent on a second call, but the CHILD gate (fresh empty store)
        // must still PROMPT for the same op. We let the parent gate remember by approving once,
        // then build the child gate as the orchestrator does and drive the same op; the child's
        // approver MUST be consulted (proving the grant did not carry over). No knowledge of the
        // gate's internal match-key form is needed — we drive the real gate end to end.
        GrantStore parentGrants = GrantStore.forSession(PARENT_LINEAGE);
        AtomicInteger parentApproverCalls = new AtomicInteger(0);
        Approver parentApprover = req -> {
            parentApproverCalls.incrementAndGet();
            return PermissionDecisionOutcome.APPROVE;
        };
        GateRequest sameOp = GateRequest.forCommand("tu_op", "ls");
        // Parent remember-mode: first call prompts + remembers, second call auto-approves.
        PermissionGate parentGate = new PermissionGate(
                PermissionMode.ASK_ONCE_THEN_REMEMBER, parentGrants, parentApprover);
        parentGate.evaluate(sameOp);
        parentGate.evaluate(GateRequest.forCommand("tu_op2", "ls"));
        assertEquals(1, parentApproverCalls.get(),
                "sanity: the parent gate prompted once then remembered (second call auto-approved)");

        // The child gate, built as the orchestrator builds it: fresh empty store via forSubAgent.
        AtomicInteger childApproverCalls = new AtomicInteger(0);
        Approver childApprover = req -> {
            childApproverCalls.incrementAndGet();
            return PermissionDecisionOutcome.APPROVE;
        };
        PermissionGate childGate = new PermissionGate(
                PermissionMode.ASK_ONCE_THEN_REMEMBER,
                parentGrants.forSubAgent(CHILD_ID), childApprover);

        childGate.evaluate(GateRequest.forCommand("tu_child", "ls"));

        assertEquals(1, childApproverCalls.get(),
                "INV-10/AC-10.6: the child prompts for an op the parent had remembered "
                        + "(the grant did not carry over to the fresh child store)");
    }

    // --- CT-INV-10 / INV-12 / AC-17.3 : concurrency bound -----------------------------

    @Test
    @DisplayName("CT-INV-10/INV-12/AC-17.3: a spawn that would exceed NFR-SUBAGENT-MAX is rejected, not run")
    void concurrencyBoundRejectsOverTheMaxSpawn(@TempDir Path storeRoot) throws InterruptedException {
        // Oracle: CT-INV-10 / INV-12 / AC-17.3 — "concurrent sub-agents <= NFR-SUBAGENT-MAX
        // (default 1)". With max=1, while one child is in flight a concurrent second spawn must
        // be rejected (never run >max concurrently). We hold the first child inside its loop on
        // a latch, fire a second spawn from another thread, and assert it was rejected without
        // building/running a second child.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        CountDownLatch firstChildEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstChild = new CountDownLatch(1);
        AtomicInteger childrenBuilt = new AtomicInteger(0);
        AtomicInteger maxObservedActive = new AtomicInteger(0);

        ChildAgentLoopFactory blockingFirstChild = ctx -> {
            childrenBuilt.incrementAndGet();
            return blockingRun(firstChildEntered, releaseFirstChild);
        };
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                blockingFirstChild, 1, Duration.ofSeconds(30));

        Thread first = new Thread(() -> orch.spawn("first child"));
        first.start();
        assertTrue(firstChildEntered.await(5, TimeUnit.SECONDS),
                "the first child must be running before the second spawn");
        maxObservedActive.set(orch.activeChildCount());

        // A genuinely-concurrent second spawn while the first holds the only permit.
        SubAgentResult second = orch.spawn("second child");

        assertEquals(1, maxObservedActive.get(),
                "INV-12/AC-17.3: at most NFR-SUBAGENT-MAX (1) child runs concurrently");
        assertFalse(second.success(),
                "CT-INV-10: the over-the-bound spawn is rejected with a failure result");
        assertTrue(second.summary().contains("NFR-SUBAGENT-MAX"),
                "CT-INV-10: the rejection names the concurrency bound");
        assertEquals(1, childrenBuilt.get(),
                "CT-INV-10: the second child is NOT built/run (the bound is enforced before running)");

        releaseFirstChild.countDown();
        first.join(5_000);
    }

    /**
     * A child run that signals it entered, then blocks until released (or interrupted). Used to
     * hold the single concurrency permit (the bound test) or to drive the over-budget timeout
     * (the budget test, where {@code release} is never counted down).
     */
    private static ChildAgentRun blockingRun(CountDownLatch entered, CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return com.srk.codingagent.loop.LoopOutcome.completed("released");
        };
    }

    // --- AC-17.6 : over-budget -> failure result (never hangs) ------------------------

    @Test
    @DisplayName("AC-17.6: a child that exceeds its wall-clock budget yields a failure result, not a hang")
    void overBudgetChildYieldsFailureResult(@TempDir Path storeRoot) {
        // Oracle: AC-17.6 — "If a sub-agent... exceeds its budget, then the parent shall receive
        // a failure result and decide a next step rather than hanging." The budget is injected as
        // a tiny Duration so the over-budget path is deterministic (no real 600s sleep): the
        // child loop blocks until interrupted; the orchestrator must time it out and return a
        // failure result.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        CountDownLatch never = new CountDownLatch(1); // never released; only interruption ends it
        ChildAgentLoopFactory hangingChild = ctx -> blockingRun(new CountDownLatch(1), never);
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                hangingChild, 1, Duration.ofMillis(150));

        long start = System.nanoTime();
        SubAgentResult result = orch.spawn("a child that will not finish in time");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertFalse(result.success(),
                "AC-17.6: an over-budget child returns a FAILURE result");
        assertTrue(result.summary().contains("budget"),
                "AC-17.6: the failure result names the exceeded budget");
        assertTrue(elapsedMs < 5_000,
                "AC-17.6: the parent does not hang — it returns shortly after the 150ms cap "
                        + "(was " + elapsedMs + "ms)");
    }

    @Test
    @DisplayName("AC-17.4: a completed child with empty final text still yields a successful, non-blank summary")
    void completedChildWithEmptyTextYieldsNonBlankSummary(@TempDir Path storeRoot) {
        // Oracle: AC-17.4 — the parent incorporates the summarized result. A child that ends its
        // turn with no text block still COMPLETED (state machine A T3->S5); the parent must
        // receive a usable, non-blank summary block rather than an empty/invalid one.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ChildAgentLoopFactory emptyTextChild = ctx -> () -> LoopOutcome.completed("");
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                emptyTextChild, 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("a subtask that ends with no text");

        assertTrue(result.success(), "AC-17.4: a child that ended its turn completed successfully");
        assertFalse(result.summary().isBlank(),
                "AC-17.4: the parent always receives a non-blank summary block");
    }

    @Test
    @DisplayName("AC-17.6: a child failure with a null-message cause still yields a failure result")
    void failingChildWithNullMessageYieldsFailureResult(@TempDir Path storeRoot) {
        // Oracle: AC-17.6 — any child failure surfaces as a failure result. An exception whose
        // message is null must not break the failure-result construction (the summary stays
        // non-blank: it names the exception class).
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ChildAgentLoopFactory throwingChild = ctx -> () -> {
            throw new IllegalStateException(); // null message
        };
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                throwingChild, 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("a child that fails with no message");

        assertFalse(result.success(), "AC-17.6: a failed child returns a failure result");
        assertTrue(result.summary().contains("IllegalStateException"),
                "AC-17.6: the failure result names the failure cause class even with no message");
    }

    @Test
    @DisplayName("AC-17.6: a child loop that throws yields a failure result, not a parent crash")
    void failingChildYieldsFailureResult(@TempDir Path storeRoot) {
        // Oracle: AC-17.6 — "If a sub-agent fails... the parent shall receive a failure result
        // ... rather than [the parent crashing]." A child loop that throws must surface as a
        // failure result, not propagate the exception to the parent.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ChildAgentLoopFactory throwingChild = ctx -> () -> {
            throw new IllegalStateException("boom inside the child loop");
        };
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                throwingChild, 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("a child that fails");

        assertFalse(result.success(), "AC-17.6: a failed child returns a failure result");
        assertTrue(result.summary().contains("failed"),
                "AC-17.6: the failure result describes the failure");
    }

    @Test
    @DisplayName("AC-17.6: a child that surfaces an edge stop reason maps to a failure result")
    void surfacedChildMapsToFailureResult(@TempDir Path storeRoot) {
        // Oracle: AC-17.6 — a child that does not complete (surfaces an edge condition, e.g.
        // max_tokens) yields a failure result so the parent decides a next step. The scripted
        // turn returns max_tokens, which the loop surfaces (SURFACED outcome).
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("partial", "max_tokens"));
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("subtask that hits max tokens");

        assertFalse(result.success(),
                "AC-17.6: a surfaced (non-completed) child yields a failure result");
        assertTrue(result.summary().contains(StopReason.MAX_TOKENS.name()),
                "AC-17.6: the failure result names the surfaced stop reason so the parent can decide");
    }

    // --- AC-17.5 / INV-11 : lineage events in parent, transcript in child --------------

    @Test
    @DisplayName("AC-17.5: SUBAGENT_SPAWN and SUBAGENT_RESULT are recorded in the PARENT's log")
    void spawnAndResultLoggedInParentLog(@TempDir Path storeRoot) {
        // Oracle: AC-17.5 — "record sub-agent spawns and results as events, linked by lineage".
        // The parent log must carry a SUBAGENT_SPAWN (before the child runs) and a
        // SUBAGENT_RESULT (after). Assert the parent's event-type sequence, not impl state.
        SessionStore store = new SessionStore(storeRoot);
        StringWriter parentSink = new StringWriter();
        EventLog parentLog = EventLog.over(parentSink, "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("child summary", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        orch.spawn("subtask");

        assertEquals(List.of("SUBAGENT_SPAWN", "SUBAGENT_RESULT"), typesIn(parentSink.toString()),
                "AC-17.5: the parent log records the spawn then the result");
    }

    @Test
    @DisplayName("AC-17.5: the child's session meta carries edgeType = SPAWNED_BY linked to the parent")
    void childMetaCarriesSpawnedByLineage(@TempDir Path storeRoot) {
        // Oracle: AC-17.5 / INV-11 — "a sub-agent session has edgeType = SPAWNED_BY". The
        // child's persisted .meta.json must record SPAWNED_BY pointing at the parent lineage.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("done", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        orch.spawn("subtask");

        Optional<SessionMeta> meta = store.readMeta(REPO, CHILD_ID);
        assertTrue(meta.isPresent(), "the child's session meta is written");
        assertEquals(EdgeType.SPAWNED_BY, meta.get().edgeType(),
                "AC-17.5/INV-11: the child session is linked SPAWNED_BY");
        assertEquals(PARENT_LINEAGE, meta.get().parentSessionId(),
                "AC-17.5: the child's lineage points at the parent");
    }

    @Test
    @DisplayName("INV-11: the parent log/context receives ONLY the summary, never the child's per-turn events")
    void parentReceivesSummaryOnlyNotChildTranscript(@TempDir Path storeRoot) {
        // Oracle: INV-11 / AC-17.4 — "the parent context receives only its summary, never its
        // event stream." The child runs a full tool_use cycle (its own TOOL_USE/TOOL_RESULT
        // events land in the CHILD log), but the PARENT log must carry only SUBAGENT_SPAWN and
        // SUBAGENT_RESULT — none of the child's per-turn event kinds — and the SUBAGENT_RESULT
        // payload must carry the summary, not the transcript.
        SessionStore store = new SessionStore(storeRoot);
        StringWriter parentSink = new StringWriter();
        EventLog parentLog = EventLog.over(parentSink, "parent");
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "file contents");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_c1", "read_file", Map.of("path", "x.txt")))
                .then(textTurn("Child final summary.", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, tools), 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("read x.txt and summarize");

        assertTrue(tool.ran, "the child ran its tool (a real nested tool-use cycle occurred)");
        // The PARENT log carries ONLY the two sub-agent edge events — not the child's
        // USER_MESSAGE/MODEL_RESPONSE/TOOL_USE/TOOL_RESULT (those are the child's stream).
        assertEquals(List.of("SUBAGENT_SPAWN", "SUBAGENT_RESULT"), typesIn(parentSink.toString()),
                "INV-11: the parent log receives ONLY the spawn + summary, never the child's per-turn events");
        // The child's per-turn events DO live in the child's own log.
        List<String> childTypes = typesIn(readChildLog(store, CHILD_ID));
        assertTrue(childTypes.contains("TOOL_USE") && childTypes.contains("TOOL_RESULT"),
                "the child's full transcript (TOOL_USE/TOOL_RESULT) lives in the CHILD's own log");
        // The summary the parent gets is the child's final answer, not its transcript.
        assertEquals("Child final summary.", result.summary(),
                "AC-17.4/INV-11: the parent receives the summary block only");
    }

    private static String readChildLog(SessionStore store, String childSessionId) {
        StringBuilder sb = new StringBuilder();
        for (Event e : store.readEvents(REPO, childSessionId)) {
            sb.append("\"type\":\"").append(e.type().name()).append("\"\n");
        }
        return sb.toString();
    }

    // --- convenience + validation + idle state ----------------------------------------

    @Test
    @DisplayName("AC-17.1: spawn(prompt) is the no-override convenience over spawn(spec)")
    void spawnPromptConvenience(@TempDir Path storeRoot) {
        // Oracle: AC-17.1 — the simplest spawn carries only the prompt (inherit model + default
        // budget). It must behave like spawn(SubAgentSpec.of(prompt)).
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("done via convenience", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        SubAgentResult result = orch.spawn("just a prompt");

        assertTrue(result.success());
        assertEquals("done via convenience", result.summary(),
                "AC-17.1: the prompt convenience runs the child and returns its summary");
    }

    @Test
    @DisplayName("INV-12: activeChildCount is 0 when idle and never exceeds the bound after a spawn")
    void activeChildCountIdleAfterSpawn(@TempDir Path storeRoot) {
        // Oracle: INV-12 — the active count is bounded; once a (blocking-free) child finishes,
        // the permit is released and the count returns to 0 so the next spawn can run.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("a", "end_turn"))
                .then(textTurn("b", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, ToolRegistry.of(List.of())), 1, Duration.ofSeconds(30));

        assertEquals(0, orch.activeChildCount(), "idle: no children active");
        orch.spawn("first");
        assertEquals(0, orch.activeChildCount(),
                "INV-12: the permit is released after the child finishes (back to 0)");
        SubAgentResult second = orch.spawn("second");
        assertTrue(second.success(),
                "INV-12: after the first child releases its permit a second spawn runs");
    }

    @Test
    @DisplayName("the orchestrator rejects an invalid construction (subAgentMax < 1, non-positive cap)")
    void constructorValidates(@TempDir Path storeRoot) {
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        GrantStore grants = GrantStore.forSession(PARENT_LINEAGE);
        ChildAgentLoopFactory factory = ctx -> () -> com.srk.codingagent.loop.LoopOutcome.completed("x");

        assertTrue(SubAgentOrchestrator.DEFAULT_WALL_CLOCK_CAP.toSeconds() == 600,
                "NFR-SUBAGENT-BUDGET: the default wall-clock cap is 600s");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SubAgentOrchestrator(store, parentLog, factory, grants,
                        PermissionMode.ASK_EVERY_TIME, alwaysApprove(), REPO, PARENT_MODEL,
                        () -> CHILD_ID, () -> TS, 0, Duration.ofSeconds(30)),
                "subAgentMax < 1 is rejected");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SubAgentOrchestrator(store, parentLog, factory, grants,
                        PermissionMode.ASK_EVERY_TIME, alwaysApprove(), REPO, PARENT_MODEL,
                        () -> CHILD_ID, () -> TS, 1, Duration.ZERO),
                "a non-positive default wall-clock cap is rejected");
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new SubAgentOrchestrator(null, parentLog, factory, grants,
                        PermissionMode.ASK_EVERY_TIME, alwaysApprove(), REPO, PARENT_MODEL,
                        () -> CHILD_ID, () -> TS, 1, Duration.ofSeconds(30)),
                "a null sessionStore is rejected");
    }

    // --- D2 wire-path reuse (T-0.5-RD2) -----------------------------------------------

    @Test
    @DisplayName("D2: a child tool-use cycle's plain-string toolResult routes to the Converse text member, not json")
    void childToolResultRoundTripsViaTextMember(@TempDir Path storeRoot) {
        // Oracle: D2 (T-0.5-RD2) — a plain-string toolResult must map to the Converse text
        // member; routing a String into json produced the real-Bedrock ValidationException. The
        // child reuses the same AgentLoop + ModelClient + ConverseWireMapper, so this is reused
        // by construction — but assert it: drive a child tool_use -> toolResult -> end_turn
        // cycle and verify the re-call request carries the toolResult on the TEXT member.
        SessionStore store = new SessionStore(storeRoot);
        EventLog parentLog = EventLog.over(new StringWriter(), "parent");
        RecordingTool tool = new RecordingTool("read_file", OperationClass.READ, "plain string file body");
        ToolRegistry tools = ToolRegistry.of(List.of(tool));
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(toolUseTurn("reading", "tu_d2", "read_file", Map.of("path", "y.txt")))
                .then(textTurn("done", "end_turn"));
        SubAgentOrchestrator orch = orchestrator(
                store, parentLog, GrantStore.forSession(PARENT_LINEAGE),
                realChildLoopFactory(bedrock, tools), 1, Duration.ofSeconds(30));

        orch.spawn("read y.txt");

        // The child's re-call (request #2) carries the tool result for tu_d2 on the TEXT member,
        // not the json member (the D2 contract).
        ConverseRequest recall = bedrock.requests.get(1);
        var toolResultBlock = recall.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b.toolResult() != null && "tu_d2".equals(b.toolResult().toolUseId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the re-call must carry the child's toolResult"));
        var content = toolResultBlock.toolResult().content().get(0);
        assertNotNull(content.text(),
                "D2: a plain-string toolResult routes to the Converse text member");
        assertNull(content.json(),
                "D2: a plain-string toolResult does NOT route to the json member (the D2 bug)");
        assertEquals("plain string file body", content.text(),
                "D2: the toolResult text is the plain string the tool returned");
    }
}
