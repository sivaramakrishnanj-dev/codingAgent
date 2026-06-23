package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.context.Compactor;
import com.srk.codingagent.context.LearningHarvester;
import com.srk.codingagent.context.OutputDisposer;
import com.srk.codingagent.loop.AgentLoop;
import com.srk.codingagent.loop.CompactionSeam;
import com.srk.codingagent.loop.LoopOutcome;
import com.srk.codingagent.loop.TokenBudgetGuard;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.permission.PermissionGate;
import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.tool.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Live-reachability regression for the compaction-and-continue wiring (T-2.8; the compaction
 * analogue of {@code LiveToolRegistryCompositionTest}). This is the test that would have caught
 * the verified gap: a long task driven past the budget threshold must actually compact and
 * <em>continue in a derived session</em> from the live loop composition — not surface-and-stop
 * (the prior broken-for-live behaviour where the loop only LOGGED on {@code COMPACT}).
 *
 * <p><b>SUT.</b> The SUT is a real {@link AgentLoop} composed with a real {@link TokenBudgetGuard}
 * (so the 0.85&times;window threshold genuinely fires on a measured-usage turn, AC-18.1) and a
 * real {@link CompactingSeam} over a real {@link Compactor} + {@link SessionStore} +
 * {@link SessionReplay} — wired exactly as {@link AgentLoopFactory#create} wires them. The only
 * test double is the scripted {@link BedrockRuntimeClient}; the loop, guard, seam, compactor, and
 * stores are real (the SUT-not-mocked discipline; the same Bedrock-double pattern the precedent
 * tests use).
 *
 * <p><b>Oracles trace to the cited spec:</b>
 * <ul>
 *   <li><b>AC-18.1 / CT-SM-6 / state machine A T13&rarr;T14:</b> a turn whose measured input usage
 *       reaches the threshold makes the live loop compact and CONTINUE in a derived conversation —
 *       the loop completes there; the outcome is NOT {@code SURFACED}.</li>
 *   <li><b>INV-4 / AC-18.3:</b> a real {@code DERIVED_FROM} session is created on disk from the
 *       live run (the derived log + lineage meta exist after the run).</li>
 *   <li><b>AC-18.4 / D2 round-trip:</b> the next live Converse call after compaction is well-formed
 *       and carries the derived session's carried-forward summary context (asserted on the captured
 *       request, not a field).</li>
 * </ul>
 */
class LiveCompactionReachabilityTest {

    private static final String MODEL_ID = "anthropic.claude-opus-4-8";
    private static final String SUMMARIZER_MODEL = "anthropic.claude-opus-4-8";
    private static final String REPO_KEY = "one-shot";
    private static final String ORIGINAL = "one-shot";
    private static final String DERIVED = "one-shot-derived-xyz";
    private static final String TS = "2026-06-22T09:00:00Z";

    /**
     * A small context window so a single scripted turn's measured input usage (set above the
     * threshold below) trips the real TokenBudgetGuard at 0.85 x window (AC-18.1) — the live
     * threshold path, not a forced guard.
     */
    private static final int SMALL_WINDOW = 1000;
    private static final double THRESHOLD = 0.85;

    private final SessionReplay replay = new SessionReplay();

    // --- Scripted external Bedrock dependency (the only test double) -----------------

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

        int callCount() {
            return requests.size();
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

    /**
     * A text turn whose measured input usage is {@code inputTokens} — the lever that trips (or
     * does not trip) the real budget guard at {@code 0.85 x window}.
     */
    private static ConverseResponse textTurn(String text, String stopReason, int inputTokens) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason(stopReason)
                .usage(u -> u.inputTokens(inputTokens).outputTokens(10).totalTokens(inputTokens + 10))
                .build();
    }

    private static ConverseResponse summaryTurn(String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("end_turn")
                .usage(u -> u.inputTokens(50).outputTokens(20).totalTokens(70))
                .build();
    }

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    /**
     * Builds the live loop EXACTLY as {@link AgentLoopFactory#create} does: a real TokenBudgetGuard
     * (so the threshold genuinely fires), a real CompactingSeam over a real Compactor, both over the
     * supplied ModelClient — only Bedrock and the store root are test-controlled.
     */
    private AgentLoop liveLoop(ModelClient modelClient, SessionStore store, EventLog log) {
        Supplier<String> clock = () -> TS;
        // §6.A.1 / ADR-0006: the Compactor carries the live session's toolConfig (the SAME registry
        // the loop offers, exactly as AgentLoopFactory threads it) so the summary call is wire-valid
        // for a transcript that may carry tool blocks.
        ToolRegistry tools = ToolRegistry.of(List.of());
        Compactor compactor = new Compactor(
                modelClient, store, replay, clock, SUMMARIZER_MODEL, 4,
                tools.toToolConfiguration(), LearningHarvester.NONE);
        CompactionSeam compaction =
                new CompactingSeam(compactor, store, replay, REPO_KEY, ORIGINAL, () -> DERIVED);
        PermissionGate gate = new PermissionGate(
                PermissionMode.ASK_EVERY_TIME, GrantStore.forSession(ORIGINAL), alwaysApprove());
        TokenBudgetGuard guard = new TokenBudgetGuard(SMALL_WINDOW, THRESHOLD);
        return new AgentLoop(modelClient, tools, gate, log,
                clock, guard, compaction, new OutputDisposer(16384), MODEL_ID, null);
    }

    @Test
    @DisplayName("AC-18.1/CT-SM-6/T14: a live loop past the budget threshold compacts and CONTINUES in a derived session (NOT surfaced)")
    void liveLoopPastThresholdCompactsAndContinues(@TempDir Path dir) {
        // Oracle: AC-18.1 / state machine A T13->T14 — "when token usage reaches the threshold the
        // agent shall compact ... and continue in a new derived conversation". With a real
        // TokenBudgetGuard at 0.85 x 1000 = 850, a turn-1 inputTokens=900 trips compaction; the live
        // seam derives a session and the loop CONTINUES in it (turn 2, scripted end_turn). The run
        // must COMPLETE (not SURFACE) — the regression the gap analysis describes (COMPACT -> SURFACED
        // -> stopped). Turn 2's low usage keeps the guard quiet so the derived session completes.
        SessionStore store = new SessionStore(dir);
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("working on the long task", "end_turn", 900))  // trips threshold (>=850)
                .then(summaryTurn("Task: long task. Decided X. Touched A.java. Open: finish B."))  // summary call
                .then(textTurn("continuing in the derived session", "end_turn", 50)); // derived-session turn
        ModelClient modelClient = new ModelClient(bedrock);

        LoopOutcome outcome;
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            outcome = liveLoop(modelClient, store, log).run("do a long task");
        }

        assertEquals(LoopOutcome.Kind.COMPLETED, outcome.kind(),
                "AC-18.1/T14: the live loop compacts and CONTINUES in the derived session (NOT surfaced)");
        assertFalse(outcome.kind() == LoopOutcome.Kind.SURFACED,
                "the gap regression: on COMPACT the live loop must not surface-and-stop");
        assertEquals("continuing in the derived session", outcome.finalText(),
                "T14: the loop drives to completion in the derived conversation");
        assertEquals(3, bedrock.callCount(),
                "the live run made: turn-1 -> summary call -> derived-session turn (the compact-and-continue path)");
    }

    @Test
    @DisplayName("INV-4/AC-18.3: the live compaction creates a real DERIVED_FROM session on disk")
    void liveCompactionCreatesDerivedSessionOnDisk(@TempDir Path dir) {
        // Oracle: INV-4 / AC-18.3 — "a compaction creates a new session with edgeType DERIVED_FROM;
        // link the derived one". After the live run the derived session must exist on disk with the
        // DERIVED_FROM lineage edge to the original.
        SessionStore store = new SessionStore(dir);
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("long task work", "end_turn", 900))
                .then(summaryTurn("Task: long task summary."))
                .then(textTurn("derived done", "end_turn", 50));
        ModelClient modelClient = new ModelClient(bedrock);
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            liveLoop(modelClient, store, log).run("do a long task");
        }

        SessionMeta meta = store.readMeta(REPO_KEY, DERIVED).orElseThrow(
                () -> new AssertionError("INV-4: the live run must create the derived session's meta"));
        assertEquals(EdgeType.DERIVED_FROM, meta.edgeType(),
                "INV-4: the live-derived session's edge is DERIVED_FROM");
        assertEquals(ORIGINAL, meta.parentSessionId(),
                "AC-18.3: the live-derived session links the original as its parent");
    }

    @Test
    @DisplayName("AC-18.4/D2: the next live Converse call after compaction carries the derived session's carried-forward summary context")
    void nextLiveCallCarriesDerivedSummaryContext(@TempDir Path dir) {
        // Oracle: AC-18.4 — "carry forward enough context for work to continue without the developer
        // re-explaining". The live Converse call AFTER compaction (the third call) must be the
        // derived session's request carrying the summary context block — not the original transcript.
        // Assert against the captured request (the real wire round-trip), not a field (D2 discipline).
        String summarySentinel = "Touched PaymentService.java; open work: add the idempotency key";
        SessionStore store = new SessionStore(dir);
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient()
                .then(textTurn("long task work", "end_turn", 900))
                .then(summaryTurn(summarySentinel))
                .then(textTurn("derived done", "end_turn", 50));
        ModelClient modelClient = new ModelClient(bedrock);
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            liveLoop(modelClient, store, log).run("do a long task");
        }

        // Request #0 = the original turn; #1 = the summary call; #2 = the first call in the derived
        // session (the continue). The derived-session call carries the summary context block.
        ConverseRequest derivedCall = bedrock.requests.get(2);
        boolean carriesSummary = derivedCall.messages().stream()
                .flatMap(m -> m.content().stream())
                .map(b -> b.text())
                .filter(t -> t != null)
                .anyMatch(t -> t.contains("PaymentService.java"));
        assertTrue(carriesSummary,
                "AC-18.4/T14: the next live call after compaction carries the derived session's summary context");
    }
}
