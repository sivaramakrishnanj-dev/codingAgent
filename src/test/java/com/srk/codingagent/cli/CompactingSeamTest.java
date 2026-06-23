package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.context.Compactor;
import com.srk.codingagent.context.LearningHarvester;
import com.srk.codingagent.loop.CompactionSeam;
import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.SessionMeta;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.persistence.UserMessagePayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

/**
 * Gate-covered contract for the production {@link CompactingSeam} (T-2.8): the seam that bridges
 * the {@link com.srk.codingagent.loop.AgentLoop}'s T13 budget hook to the real {@link Compactor}
 * (C6, ADR-0006). It is the analogue of {@code LiveToolRegistryCompositionTest} for the
 * compaction wiring — extracted from the (JaCoCo-excluded) {@link AgentLoopFactory} so the
 * summarize&rarr;derive&rarr;continue path is exercised under the coverage gate.
 *
 * <p><b>SUT.</b> The SUT is the real {@link CompactingSeam} over a real {@link Compactor}, a real
 * {@link SessionStore} + {@link SessionReplay} rooted at a {@link TempDir}. The only test double is
 * a hand-rolled scripted {@link BedrockRuntimeClient} replaying the summary turn (the same pattern
 * {@code CompactorTest} / {@code LiveToolRegistryCompositionTest} use). Nothing the seam or
 * compactor owns is mocked.
 *
 * <p><b>Oracles trace to the cited spec, not to impl behaviour:</b>
 * <ul>
 *   <li><b>CT-SM-6 / AC-18.1 (state machine B LT2&rarr;LT3, machine A T14):</b> the threshold path
 *       compacts into a derived session and the seam returns a CONTINUED result — the loop
 *       continues, it does not surface.</li>
 *   <li><b>INV-4 / CT-INV-3:</b> compaction creates a NEW session; the parent's events are
 *       byte-identical after.</li>
 *   <li><b>INV-5 / CT-INV-4 (AC-18.3):</b> the original conversation file is still present after
 *       compaction.</li>
 *   <li><b>AC-18.4 / D2-class round-trip:</b> the CONTINUED transcript is the derived session's
 *       replayed {@code messages[]} (summary context + recent-tail verbatim turns, INV-7 signatures
 *       intact) — a valid request the loop drives next, asserted on the real replayed shape, not a
 *       field.</li>
 *   <li><b>CT-SM-7 (state machine B LT4&rarr;exit5):</b> a compaction failure surfaces
 *       {@link StopReason#MODEL_CONTEXT_WINDOW_EXCEEDED}, which {@link OneShotRunner} maps to exit
 *       5 (context-exhausted).</li>
 * </ul>
 */
class CompactingSeamTest {

    private static final String REPO_KEY = "github.com-example-widget";
    private static final String ORIGINAL = "one-shot";
    private static final String DERIVED = "one-shot-derived-abc";
    private static final String SUMMARIZER_MODEL = "anthropic.claude-haiku-summarizer";
    private static final String TS = "2026-06-22T09:30:00Z";
    private static final String SUMMARY_TEXT =
            "Task: add retry to the uploader. Decided exponential backoff. Touched Uploader.java. "
                    + "Open: wire the config.";

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

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op for the in-test double
        }
    }

    /** A summarizer Converse double that throws to model an unrecoverable summary call (LT4). */
    private static final class FailingBedrockClient implements BedrockRuntimeClient {
        @Override
        public ConverseResponse converse(ConverseRequest request) {
            throw software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException
                    .builder().message("synthetic backend failure").build();
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

    private static ConverseResponse summaryTurn(String text) {
        Message message = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(c -> c.text(text))
                .build();
        return ConverseResponse.builder()
                .output(ConverseOutput.builder().message(message).build())
                .stopReason("end_turn")
                .usage(u -> u.inputTokens(500).outputTokens(120).totalTokens(620))
                .build();
    }

    /**
     * Seeds the original ("one-shot") session with a representative transcript including a reasoning
     * block with a signature (INV-7), a toolUse, the batched toolResult, and a final answer.
     */
    private void seedOriginal(SessionStore store, String reasoningSignature) {
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            log.append(new Event(0, TS, new UserMessagePayload(
                    List.of(ContentBlock.text("Add retry to the uploader.")))));
            log.append(new Event(0, TS, new ModelResponsePayload(StopReason.TOOL_USE, List.of(
                    ContentBlock.reasoning("I should add exponential backoff.", reasoningSignature, null),
                    ContentBlock.text("Reading the uploader."),
                    ContentBlock.toolUse("tu_01", "read_file", Map.of("path", "Uploader.java"))))));
            log.append(new Event(0, TS, new UserMessagePayload(
                    List.of(ContentBlock.toolResult("tu_01", "ok", "class Uploader { ... }")))));
            log.append(new Event(0, TS, new ModelResponsePayload(StopReason.END_TURN,
                    List.of(ContentBlock.text("Added the retry.")))));
        }
    }

    /**
     * Builds the seam EXACTLY as {@link AgentLoopFactory} builds it — a real {@link Compactor} (no
     * harvest, since the v1 production harvest is inert — {@link LearningHarvester#NONE} here keeps
     * the wiring identical without touching the user-home memory store) over the scripted model and
     * a fixed derived-session-id supplier (the boundary-captured id, ADR-0005).
     */
    private CompactionSeam seam(BedrockRuntimeClient bedrock, SessionStore store) {
        Supplier<String> clock = () -> TS;
        // §6.A.1: the seeded transcript carries tool blocks, so the live wiring threads the
        // session's toolConfig into the Compactor (mirroring AgentLoopFactory) so the summary
        // request is wire-valid. A representative read_file toolSpec stands in for the registry's
        // rendered config.
        Compactor compactor = new Compactor(
                new ModelClient(bedrock), store, replay, clock, SUMMARIZER_MODEL, 4,
                sessionToolConfig(), LearningHarvester.NONE);
        return new CompactingSeam(compactor, store, replay, REPO_KEY, ORIGINAL, () -> DERIVED);
    }

    /** A representative {@code read_file} {@link ToolConfiguration} for the summary call (§6.A.1). */
    private static ToolConfiguration sessionToolConfig() {
        Document inputSchema = Document.mapBuilder()
                .putString("type", "object")
                .putDocument("properties", Document.mapBuilder()
                        .putDocument("path", Document.mapBuilder()
                                .putString("type", "string")
                                .build())
                        .build())
                .build();
        return ToolConfiguration.builder()
                .tools(Tool.fromToolSpec(ToolSpecification.builder()
                        .name("read_file")
                        .description("Read a file from the workspace.")
                        .inputSchema(ToolInputSchema.fromJson(inputSchema))
                        .build()))
                .build();
    }

    // --- CT-SM-6 / AC-18.1 : threshold path compacts and CONTINUES (not surfaces) ----

    @Test
    @DisplayName("CT-SM-6/AC-18.1/T14: the live seam compacts into a derived session and returns CONTINUED (not surfaced)")
    void compactsAndContinuesInDerivedSession(@TempDir Path dir) {
        // Oracle: AC-18.1 ("compact ... and CONTINUE in a new derived conversation") + CT-SM-6 (B
        // LT2->LT3) + state machine A T14 ("continue in the derived conversation"). Driving the
        // live seam over a real Compactor must derive a new session and return a CONTINUED result
        // carrying the derived transcript — the contract the loop relies on to keep driving instead
        // of surfacing-and-stopping (the gap T-2.8 closes).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig-orig==");
        CompactionSeam seam = seam(new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store);

        CompactionSeam.CompactionResult result = seam.compact(
                List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        assertTrue(result.continued(),
                "AC-18.1/T14: the live seam CONTINUES in the derived session (does not surface)");
        assertTrue(Files.exists(store.logPath(REPO_KEY, DERIVED)),
                "CT-SM-6: a new derived session log is created");
        SessionMeta meta = store.readMeta(REPO_KEY, DERIVED).orElseThrow();
        assertEquals(EdgeType.DERIVED_FROM, meta.edgeType(),
                "CT-SM-6/INV-4: the derived session's edge is DERIVED_FROM");
        assertEquals(ORIGINAL, meta.parentSessionId(),
                "CT-SM-6: the derived session's parent is the original");
    }

    @Test
    @DisplayName("AC-18.4/D2 round-trip: the CONTINUED transcript is the derived session's replayed messages[] (summary + tail), not the original's")
    void continuedTranscriptIsTheDerivedReplay(@TempDir Path dir) {
        // Oracle: AC-18.4 ("carry forward enough context ... to continue without re-explaining") +
        // INV-7 (reasoning signatures replayed verbatim). The CONTINUED transcript must be the
        // derived session's replayed messages[] — the summary context block as the first turn, plus
        // the recent-tail verbatim turns (carrying the reasoning signature). Assert the real
        // replayed shape (the round-trip), not a field — the D2-class discipline.
        String signature = "sig-INV7-replay==";
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, signature);
        CompactionSeam seam = seam(new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store);

        CompactionSeam.CompactionResult result = seam.compact(
                List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        List<ConverseMessage> derived = result.derivedTranscript();
        assertFalse(derived.isEmpty(), "AC-18.4: the derived transcript carries forward context");
        // The first turn is the summary context block (AC-18.4 — seeded as the initial context).
        String firstText = ((ContentBlock.Text) derived.get(0).content().get(0)).text();
        assertTrue(firstText.contains(SUMMARY_TEXT),
                "AC-18.4: the derived transcript's first turn is the compaction summary context");
        // INV-7: the recent-tail carried forward a MODEL_RESPONSE turn with its reasoning signature
        // verbatim (the same bytes the original held) — a dropped/mutated signature would error the
        // first live Converse call in the derived session.
        boolean carriesReasoningSignature = derived.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.Reasoning)
                .map(b -> ((ContentBlock.Reasoning) b).signature())
                .anyMatch(signature::equals);
        assertTrue(carriesReasoningSignature,
                "INV-7: the recent-tail turn carries its reasoning signature verbatim into the derived session");
    }

    @Test
    @DisplayName("AC-18.4: the derived transcript replays into a well-formed next Converse request (the loop can drive it)")
    void derivedTranscriptDrivesAWellFormedNextCall(@TempDir Path dir) {
        // Oracle: AC-18.1/T14 — work continues in the derived conversation. The carried-forward
        // derived transcript must be a transcript the loop can actually send: a real ModelClient
        // over the same wire path builds a valid ConverseRequest from it (the round-trip the live
        // run depends on). Assert the next Converse call over the derived transcript is well-formed
        // (carries the derived messages), mirroring LiveToolRegistryCompositionTest's wire rigour.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        CompactionSeam seam = seam(new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store);
        CompactionSeam.CompactionResult result = seam.compact(
                List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        // Send the derived transcript through a real ModelClient (the loop's next move) over a fresh
        // scripted Bedrock; the request must be well-formed and carry the summary context.
        ScriptedBedrockClient next = new ScriptedBedrockClient()
                .then(summaryTurn("continuing in the derived session"));
        new ModelClient(next).converse(SUMMARIZER_MODEL, result.derivedTranscript(), null, null);

        ConverseRequest derivedCall = next.requests.get(0);
        assertFalse(derivedCall.messages().isEmpty(),
                "T14: the derived transcript builds a non-empty (well-formed) next Converse request");
        boolean carriesSummary = derivedCall.messages().stream()
                .flatMap(m -> m.content().stream())
                .map(b -> b.text())
                .filter(t -> t != null)
                .anyMatch(t -> t.contains("Touched Uploader.java"));
        assertTrue(carriesSummary,
                "AC-18.4: the next call in the derived session carries the carried-forward summary context");
    }

    // --- INV-4 / INV-5 : parent preserved -------------------------------------------

    @Test
    @DisplayName("INV-4/CT-INV-3: the parent session's events are byte-identical after the live compaction")
    void parentByteIdenticalAfterCompaction(@TempDir Path dir) throws Exception {
        // Oracle: INV-4 / CT-INV-3 — "compaction creates a new session; the parent's events are
        // byte-identical after". The seam compacts; the original log file's bytes must be unchanged.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Path originalLog = store.logPath(REPO_KEY, ORIGINAL);
        byte[] before = Files.readAllBytes(originalLog);
        CompactionSeam seam = seam(new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store);

        seam.compact(List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        assertArrayEquals(before, Files.readAllBytes(originalLog),
                "INV-4/CT-INV-3: the parent session log is byte-identical after compaction");
    }

    @Test
    @DisplayName("INV-5/CT-INV-4 (AC-18.3): the original conversation file is still present after compaction")
    void originalPreservedAfterCompaction(@TempDir Path dir) {
        // Oracle: INV-5 / CT-INV-4 / AC-18.3 — "the original conversation is never deleted on
        // compaction (preserved for history)". After the live compaction, the original log file
        // must still exist.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        CompactionSeam seam = seam(new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store);

        seam.compact(List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        assertTrue(Files.exists(store.logPath(REPO_KEY, ORIGINAL)),
                "INV-5/CT-INV-4/AC-18.3: the original conversation file is preserved after compaction");
    }

    // --- CT-SM-7 : a failed compaction surfaces for exit 5 --------------------------

    @Test
    @DisplayName("CT-SM-7: a failed compaction (summary call fails) surfaces MODEL_CONTEXT_WINDOW_EXCEEDED so the boundary exits 5")
    void failedCompactionSurfacesForExitFive(@TempDir Path dir) {
        // Oracle: CT-SM-7 / state machine B LT4->LT7 -> machine A T15 / cli-exit-codes 5 — "the
        // compaction failure path exits 5 (context-exhausted)". When the summary Converse call
        // fails (the Compactor returns FAILED), the seam must surface MODEL_CONTEXT_WINDOW_EXCEEDED
        // — the reason OneShotRunner already maps to exit 5. Assert the seam returns a SURFACED
        // result carrying that reason (the loop then surfaces, OneShotRunner maps it to 5).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        CompactionSeam seam = seam(new FailingBedrockClient(), store);

        CompactionSeam.CompactionResult result = seam.compact(
                List.of(ConverseMessage.user(List.of(ContentBlock.text("live")))),
                StopReason.END_TURN);

        assertFalse(result.continued(),
                "CT-SM-7: a failed compaction does not continue; it surfaces");
        assertEquals(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED, result.surfacedStopReason(),
                "CT-SM-7: the failure surfaces the context-exhausted reason OneShotRunner maps to exit 5");
    }
}
