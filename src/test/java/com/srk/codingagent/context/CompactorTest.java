package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.model.converse.ConverseMessage;
import com.srk.codingagent.model.converse.ConverseWireMapper;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.persistence.CompactionPayload;
import com.srk.codingagent.persistence.CompactionTrigger;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.EdgeType;
import com.srk.codingagent.persistence.ErrorPayload;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Unit tests for {@link Compactor} — compaction-with-derivation (component C6, ADR-0006,
 * state-machine B). The SUT is a real {@link Compactor} composed with a real
 * {@link ModelClient} (and its real {@link ConverseWireMapper}), a real {@link SessionStore}
 * over a temp dir, and a real {@link SessionReplay}. Nothing the compaction logic owns is
 * mocked: the only external dependency, the {@link BedrockRuntimeClient}, is a hand-rolled
 * in-test double that replays a scripted summary response — the summarizer Converse call goes
 * through the real Model Client seam with a stubbed Bedrock response (the tester discipline:
 * SUT not mocked, only Bedrock is).
 *
 * <p>Oracles trace to the spec, not to {@link Compactor}'s code:
 * <ul>
 *   <li><b>CT-SM-6 (B: LT2→LT3):</b> a transcript drives compaction to a new
 *       {@code DERIVED_FROM} session seeded with the summary + recent tail.</li>
 *   <li><b>CT-SM-7 (B: LT4→exit5):</b> a summary/derive failure appends an {@code ERROR} and
 *       produces the compaction-failed signal that maps to exit 5.</li>
 *   <li><b>CT-INV-3 (INV-4):</b> compaction creates a new session and the parent's event bytes
 *       are byte-identical after (read the file bytes before/after).</li>
 *   <li><b>CT-INV-4 (INV-5):</b> the original session's log file still exists after compaction.</li>
 *   <li><b>CT-INV-6 (INV-7):</b> a reasoning block's signature survives the derived session's
 *       seeded transcript byte-identically (read it back, replay, map through the wire mapper).</li>
 *   <li><b>INV-6:</b> the derived session's seeded transcript is a valid wire transcript (every
 *       toolResult pairs with a prior toolUse).</li>
 *   <li><b>AC-18.4:</b> the summary is carried forward as an initial context block.</li>
 *   <li><b>§6.A.1 wire rule (D3 regression):</b> the summary Converse request — whose replayed
 *       transcript carries {@code toolUse}/{@code toolResult} blocks — presents the session's
 *       {@code toolConfig}, so Bedrock does not reject it ("The toolConfig field must be defined
 *       when using toolUse and toolResult content blocks.").</li>
 * </ul>
 */
class CompactorTest {

    private static final String REPO_KEY = "github.com-example-widget";
    private static final String ORIGINAL = "2026-06-22T090000Z-original";
    private static final String DERIVED = "2026-06-22T093000Z-derived";
    private static final String SUMMARIZER_MODEL = "anthropic.claude-haiku-summarizer";
    private static final String TS = "2026-06-22T09:30:00Z";
    private static final String SUMMARY_TEXT =
            "Task: add retry to the uploader. Decided exponential backoff. Touched Uploader.java. "
                    + "Open: wire the config. Learning: the SDK already jitters.";

    /**
     * The tool name the summarized session offers — the same tool ({@code read_file}) whose
     * {@code toolUse}/{@code toolResult} blocks {@link #seedOriginal} plants in the replayed
     * transcript. The §6.A.1 wire rule requires the summary request's {@code toolConfig} to be
     * present (and these are the session's tool defs) for that transcript to be accepted.
     */
    private static final String SESSION_TOOL_NAME = "read_file";

    private final SessionReplay replay = new SessionReplay();

    // --- Scripted external Bedrock dependency (the only test double) -----------------

    private static final class ScriptedBedrockClient implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> script = new ArrayDeque<>();
        private final List<ConverseRequest> requests = new ArrayList<>();
        private RuntimeException toThrow;

        ScriptedBedrockClient then(ConverseResponse response) {
            script.addLast(response);
            return this;
        }

        ScriptedBedrockClient throwing(RuntimeException ex) {
            this.toThrow = ex;
            return this;
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            if (toThrow != null) {
                throw toThrow;
            }
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

    private Compactor compactorOver(BedrockRuntimeClient bedrock, SessionStore store, int tail) {
        ModelClient modelClient = new ModelClient(bedrock);
        return new Compactor(modelClient, store, replay, () -> TS, SUMMARIZER_MODEL, tail,
                sessionToolConfig());
    }

    /**
     * A representative non-empty {@link ToolConfiguration} standing in for the tool definitions
     * the summarized session offered — one {@code read_file} toolSpec, matching the tool whose
     * blocks {@link #seedOriginal} plants in the transcript. The real Compactor receives the live
     * registry's {@code toToolConfiguration()} here; the test substitutes an equivalent shape so
     * the §6.A.1 contract assertion has expected tool defs to check.
     */
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
                        .name(SESSION_TOOL_NAME)
                        .description("Read a file from the workspace.")
                        .inputSchema(ToolInputSchema.fromJson(inputSchema))
                        .build()))
                .build();
    }

    /**
     * Seeds the original session with a representative transcript: a user prompt, an assistant
     * turn carrying a reasoning block (with a signature, INV-7) + a toolUse, the batched
     * toolResult turn (INV-6 pairing), and a final assistant answer.
     */
    private void seedOriginal(SessionStore store, String reasoningSignature) {
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            log.append(new Event(0, TS, new UserMessagePayload(
                    List.of(ContentBlock.text("Add retry to the uploader.")))));
            log.append(new Event(0, TS, new ModelResponsePayload(StopReason.TOOL_USE, List.of(
                    ContentBlock.reasoning("I should add exponential backoff.", reasoningSignature, null),
                    ContentBlock.text("Reading the uploader."),
                    ContentBlock.toolUse("tu_01", "read_file",
                            java.util.Map.of("path", "Uploader.java"))))));
            log.append(new Event(0, TS, new UserMessagePayload(
                    List.of(ContentBlock.toolResult("tu_01", "ok", "class Uploader { ... }")))));
            log.append(new Event(0, TS, new ModelResponsePayload(StopReason.END_TURN,
                    List.of(ContentBlock.text("Added the retry.")))));
        }
    }

    private CompactionRequest request(CompactionTrigger trigger) {
        return new CompactionRequest(REPO_KEY, ORIGINAL, DERIVED, trigger);
    }

    // --- CT-SM-6 : threshold triggers compaction → derived session -------------------

    @Test
    @DisplayName("CT-SM-6: compaction derives a new DERIVED_FROM session seeded with the summary + tail")
    void ctSm6_derivesNewSessionWithSummaryAndTail(@TempDir Path dir) {
        // Oracle: CT-SM-6 / state-machine B LT2→LT3 — "threshold triggers compaction → derived
        // session", a new DERIVED_FROM session seeded with the summary + recent tail. Drive the
        // real compactor; assert a NEW session exists, linked DERIVED_FROM, carrying the summary.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig-orig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 2);

        CompactionOutcome outcome = compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertTrue(outcome.succeeded(), "CT-SM-6: compaction produces a derived session");
        assertEquals(DERIVED, outcome.derivedSessionId(),
                "CT-SM-6: the derived session carries the boundary-captured id");
        assertTrue(Files.exists(store.logPath(REPO_KEY, DERIVED)),
                "CT-SM-6: a new derived session log is created");

        SessionMeta meta = store.readMeta(REPO_KEY, DERIVED).orElseThrow();
        assertEquals(EdgeType.DERIVED_FROM, meta.edgeType(),
                "CT-SM-6/INV-4: the derived session's edge is DERIVED_FROM");
        assertEquals(ORIGINAL, meta.parentSessionId(),
                "CT-SM-6: the derived session's parent is the original");

        // AC-18.4: the summary is carried forward as the initial context block of the derived seed.
        List<ConverseMessage> derivedMessages =
                replay.replay(store.readEvents(REPO_KEY, DERIVED));
        String firstUserText = ((ContentBlock.Text) derivedMessages.get(0).content().get(0)).text();
        assertTrue(firstUserText.contains(SUMMARY_TEXT),
                "AC-18.4: the derived session is seeded with the summary as initial context");
    }

    @Test
    @DisplayName("CT-SM-6: the summary call goes to the configured summarizer model with the compaction prompt")
    void ctSm6_summaryCallUsesSummarizerModelAndPrompt(@TempDir Path dir) {
        // Oracle: ADR-0006 (OQ-D) — "a dedicated Converse call to ... a configured cheaper
        // summarizer model with a fixed compaction system prompt". Assert the captured request
        // targets the summarizer model and carries the compaction system prompt (the prompt asks
        // for task state / decisions / files / open work / learnings, AC-18.4).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT));
        Compactor compactor = compactorOver(bedrock, store, 2);

        compactor.compact(request(CompactionTrigger.MANUAL));

        ConverseRequest summaryCall = bedrock.requests.get(0);
        assertEquals(SUMMARIZER_MODEL, summaryCall.modelId(),
                "ADR-0006: the summary call targets the configured summarizer model");
        assertTrue(summaryCall.hasSystem() && !summaryCall.system().isEmpty(),
                "ADR-0006: the summary call carries a fixed compaction system prompt");
        // Oracle: AC-18.4 / ADR-0006 (OQ-D) — the compaction prompt must ask for the five
        // carry-forward elements the spec names (task state, decisions made, files touched,
        // open work, durable learnings) — enough to "continue without re-explaining". Assert the
        // prompt covers each spec-named element rather than comparing to the impl's constant.
        String prompt = summaryCall.system().get(0).text().toLowerCase(java.util.Locale.ROOT);
        assertTrue(prompt.contains("task state"),
                "AC-18.4: the prompt asks for the outstanding task state");
        assertTrue(prompt.contains("decision"),
                "AC-18.4/ADR-0006: the prompt asks for the decisions made");
        assertTrue(prompt.contains("file"),
                "AC-18.4/ADR-0006: the prompt asks for the files touched");
        assertTrue(prompt.contains("open work"),
                "AC-18.4/ADR-0006: the prompt asks for the open work");
        assertTrue(prompt.contains("learning"),
                "ADR-0006/AC-18.5: the prompt asks for durable learnings (the harvest seam)");
    }

    @Test
    @DisplayName("§6.A.1 (D3): the summary call presents the session's toolConfig because the transcript carries tool blocks")
    void sixA1_summaryCallCarriesSessionToolConfigForToolBlockTranscript(@TempDir Path dir) {
        // Oracle: §6.A.1 verified Bedrock wire rule — "when messages[] carry toolUse/toolResult
        // content blocks, toolConfig must be present on the request". The seeded transcript the
        // summarizer replays CONTAINS a toolUse (tu_01, read_file) and its paired toolResult
        // (seedOriginal), so the live rule forces the request to carry a toolConfig — the session's
        // tool definitions — or Bedrock returns the ValidationException "The toolConfig field must
        // be defined when using toolUse and toolResult content blocks." (the D3 defect). Assert the
        // captured summary request both PRESENTS a toolConfig and carries the session's tool
        // definition — phrased as the wire-rule contract, not "the impl now passes toolConfig".
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        ScriptedBedrockClient bedrock = new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT));
        // tail=4 carries the whole transcript (incl. the toolUse/toolResult turns) forward too, but
        // the summary REQUEST below replays the full original transcript regardless — that is where
        // the tool blocks live and where the wire rule bites.
        Compactor compactor = compactorOver(bedrock, store, 4);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        ConverseRequest summaryCall = bedrock.requests.get(0);
        // 1) The replayed summary transcript indeed carries tool blocks (the precondition that
        //    triggers the wire rule) — so this is the live-broken scenario, not a no-tools call.
        boolean transcriptHasToolBlocks = summaryCall.messages().stream()
                .flatMap(m -> m.content().stream())
                .anyMatch(b -> b.toolUse() != null || b.toolResult() != null);
        assertTrue(transcriptHasToolBlocks,
                "precondition: the summarizer replays a transcript carrying toolUse/toolResult blocks");
        // 2) §6.A.1: the request must therefore present a toolConfig (the D3 contract). On the
        //    AWS SDK v2 ConverseRequest, an absent toolConfig reads back as null.
        assertNotNull(summaryCall.toolConfig(),
                "§6.A.1: a request whose messages carry tool blocks must define toolConfig (D3)");
        // 3) And it is the session's tool definitions (the tools available in the summarized
        //    session), not an empty placeholder — assert the session tool's name is present.
        List<String> toolNames = summaryCall.toolConfig().tools().stream()
                .map(t -> t.toolSpec().name())
                .toList();
        assertTrue(toolNames.contains(SESSION_TOOL_NAME),
                "§6.A.1: the summary request carries the summarized session's tool definitions ("
                        + SESSION_TOOL_NAME + ")");
    }

    @Test
    @DisplayName("CT-SM-6: a COMPACTION(from,to,summaryRef) marker records the lineage edge (LT3 side effect)")
    void ctSm6_compactionMarkerRecordsEdge(@TempDir Path dir) {
        // Oracle: state-machine B LT3 — the side effect is "append COMPACTION(from,to,summaryRef)".
        // The derived log carries a COMPACTION event recording from=original, to=derived, with the
        // configured trigger reason. (CT-INV-3 keeps the parent byte-identical, so the marker lands
        // on the child.)
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 1);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        CompactionPayload marker = store.readEvents(REPO_KEY, DERIVED).stream()
                .map(Event::payload)
                .filter(p -> p instanceof CompactionPayload)
                .map(p -> (CompactionPayload) p)
                .findFirst()
                .orElseThrow(() -> new AssertionError("LT3: a COMPACTION marker must be appended"));
        assertEquals(ORIGINAL, marker.fromSessionId(), "LT3: COMPACTION records from = original");
        assertEquals(DERIVED, marker.toSessionId(), "LT3: COMPACTION records to = derived");
        assertEquals(CompactionTrigger.THRESHOLD, marker.triggerReason(),
                "LT3: COMPACTION records the trigger reason");
    }

    @Test
    @DisplayName("CT-SM-6: the recent-tail count caps how many verbatim turns are carried forward")
    void ctSm6_recentTailIsCapped(@TempDir Path dir) {
        // Oracle: ADR-0006 — the derived session carries "the summary ... PLUS a configurable tail
        // of recent verbatim turns". With tail=1, only the last of the original's 4 message turns
        // is carried forward (after the summary seed). Assert the derived message count = 1
        // (summary) + 1 (tail).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 1);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        List<ConverseMessage> derived = replay.replay(store.readEvents(REPO_KEY, DERIVED));
        assertEquals(2, derived.size(),
                "ADR-0006: derived seed = 1 summary turn + tail of 1 verbatim turn");
        // The tail's single turn is the original's last turn (the final assistant answer).
        assertEquals("Added the retry.",
                ((ContentBlock.Text) derived.get(1).content().get(0)).text(),
                "ADR-0006: the carried tail is the most-recent verbatim turn(s)");
    }

    // --- CT-INV-3 : parent events byte-identical after compaction --------------------

    @Test
    @DisplayName("CT-INV-3: compaction creates a new session and the parent's events are byte-identical after")
    void ctInv3_parentBytesUnchanged(@TempDir Path dir) throws Exception {
        // Oracle: CT-INV-3 / INV-4 — "compaction creates a new session; the parent's events are
        // byte-identical after". Read the parent log's raw bytes before and after compaction and
        // assert they are identical (the original is never mutated, edited, or appended to).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig-parent==");
        Path parentLog = store.logPath(REPO_KEY, ORIGINAL);
        byte[] before = Files.readAllBytes(parentLog);
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 2);

        CompactionOutcome outcome = compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertTrue(outcome.succeeded(), "a new session is created");
        assertNotEquals(ORIGINAL, outcome.derivedSessionId(),
                "INV-4: the derived session is a new session, not the original mutated in place");
        byte[] after = Files.readAllBytes(parentLog);
        assertArrayEquals(before, after,
                "CT-INV-3/INV-4: the parent's log bytes are identical after compaction (never mutated)");
    }

    // --- CT-INV-4 : the original conversation file still exists ----------------------

    @Test
    @DisplayName("CT-INV-4: the original conversation log still exists after compaction (INV-5)")
    void ctInv4_originalPreserved(@TempDir Path dir) {
        // Oracle: CT-INV-4 / INV-5 — "original conversation file still present after compaction"
        // (RD-8, AC-18.3). The store has no delete; assert the original log file still exists.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 2);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertTrue(Files.exists(store.logPath(REPO_KEY, ORIGINAL)),
                "CT-INV-4/INV-5: the original conversation log is preserved (never deleted)");
        assertFalse(store.readEvents(REPO_KEY, ORIGINAL).isEmpty(),
                "INV-5: the original's events remain readable");
    }

    // --- CT-INV-6 / INV-7 : reasoning signature byte-identical in the derived seed ----

    @Test
    @DisplayName("CT-INV-6/INV-7: a reasoning signature survives into the derived session's seed byte-identically")
    void ctInv6_reasoningSignaturePreservedInDerivedSeed(@TempDir Path dir) {
        // Oracle: CT-INV-6 / INV-7 — "a replayed ReasoningBlock keeps its signature". Compact a
        // transcript whose assistant turn carries a reasoning block with a signature, with a tail
        // large enough to carry that turn forward, then read the DERIVED session back, replay it,
        // and assert the reasoning block's signature member is byte-identical to the original (not
        // merely "a signature is present"). A mutated/dropped signature would error the first live
        // Converse call in the derived session (the D2 class of bug).
        String signature = "EqQBverbatim-tamper-check-hash-9f2a==";
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, signature);
        // tail=3 carries the assistant turn that holds the reasoning block into the derived seed.
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 3);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        List<ConverseMessage> derived = replay.replay(store.readEvents(REPO_KEY, DERIVED));
        String derivedSignature = derived.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.Reasoning)
                .map(b -> ((ContentBlock.Reasoning) b).signature())
                .findFirst()
                .orElse(null);
        assertEquals(signature, derivedSignature,
                "CT-INV-6/INV-7: the carried reasoning signature is byte-identical in the derived seed");
    }

    @Test
    @DisplayName("INV-7: the derived seed re-sends the reasoning signature verbatim through the wire mapper")
    void inv7_derivedSeedReSendsSignatureThroughWireMapper(@TempDir Path dir) {
        // Oracle: INV-7 / § 6.A.1 — the derived session's first live Converse call must replay the
        // reasoning signature verbatim or it errors. Exercise the actual path: derive, read the
        // derived seed back, replay → messages[], map through the REAL ConverseWireMapper (us →
        // request), and assert the request carries the signature byte-identical. This is the real
        // contract, not a structurally-blind field-present check.
        String signature = "EqQBverbatim==";
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, signature);
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 3);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        List<ConverseMessage> derived = replay.replay(store.readEvents(REPO_KEY, DERIVED));
        ConverseRequest wireRequest = new ConverseWireMapper()
                .toRequest("anthropic.claude-opus-4-8", derived, null, null);
        String wireSignature = wireRequest.messages().stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b.reasoningContent() != null)
                .map(b -> b.reasoningContent().reasoningText().signature())
                .findFirst()
                .orElse(null);
        assertEquals(signature, wireSignature,
                "INV-7: the derived seed re-sends the reasoning signature verbatim to the wire");
    }

    @Test
    @DisplayName("INV-6: the derived session's seeded transcript is wire-valid (toolResult pairs with prior toolUse)")
    void inv6_derivedSeedIsWireValid(@TempDir Path dir) {
        // Oracle: INV-6 / CT-INV-5 — "every ToolResultBlock.toolUseId matches a prior
        // ToolUseBlock.toolUseId in the same session". Carry the full tail (toolUse turn +
        // toolResult turn) forward and assert the derived seed preserves the pairing — the
        // ConverseWireMapper accepts it without throwing (an unpaired toolResult would be rejected).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)), store, 4);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        List<ConverseMessage> derived = replay.replay(store.readEvents(REPO_KEY, DERIVED));
        assertTrue(toolResultsArePaired(derived),
                "INV-6: every toolResult in the derived seed follows a toolUse with the same id");
        // The wire mapper enforces INV-6: building a request must not throw on the derived seed.
        new ConverseWireMapper().toRequest("anthropic.claude-opus-4-8", derived, null, null);
    }

    // --- CT-SM-7 : compaction failure path → exit 5 ----------------------------------

    @Test
    @DisplayName("CT-SM-7: a summary Converse failure appends an ERROR and signals exit 5 (LT4)")
    void ctSm7_summaryFailureAppendsErrorAndExitsFive(@TempDir Path dir) {
        // Oracle: CT-SM-7 / state-machine B LT4 — "compaction failure path exits 5
        // (context-exhausted)". When the summary call fails at the backend (no usable context can
        // be recovered), an ERROR is appended and the outcome carries the exit-5 signal
        // (cli-exit-codes 5). Use a Bedrock double that throws to fail the (real) Model Client.
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().throwing(
                        software.amazon.awssdk.services.bedrockruntime.model.ValidationException.builder()
                                .message("backend down").build()),
                store, 2);

        CompactionOutcome outcome = compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertFalse(outcome.succeeded(), "CT-SM-7: a failed compaction does not produce a derived session");
        assertEquals(5, outcome.failureExitCodeIfPresent().orElse(-1),
                "CT-SM-7: a compaction failure maps to exit 5 (context-exhausted, cli-exit-codes 5)");
        assertTrue(errorAppended(store, DERIVED),
                "CT-SM-7/LT4: an ERROR event is appended on the failure path");
    }

    @Test
    @DisplayName("CT-SM-7: an empty summary (no usable text) fails to exit 5 (LT4)")
    void ctSm7_emptySummaryFailsToFive(@TempDir Path dir) {
        // Oracle: CT-SM-7 / AC-18.4 — the summary must carry "enough context ... to continue".
        // A summarizer that returns no usable text cannot recover context, so the derive fails
        // (LT4) and maps to exit 5. (Distinct from a backend failure: the call succeeds but yields
        // nothing usable.)
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().then(summaryTurn("   ")), store, 2);

        CompactionOutcome outcome = compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertFalse(outcome.succeeded(),
                "CT-SM-7: an empty summary cannot carry context, so compaction fails");
        assertEquals(5, outcome.failureExitCodeIfPresent().orElse(-1),
                "CT-SM-7: the empty-summary failure maps to exit 5");
    }

    @Test
    @DisplayName("CT-INV-3: a failed compaction also leaves the parent byte-identical (no mutation on failure)")
    void ctInv3_parentUnchangedOnFailure(@TempDir Path dir) throws Exception {
        // Oracle: INV-4/INV-5 — the original is never mutated, on success OR failure. A summary
        // failure must not touch the parent's bytes either (the ERROR lands on the child).
        SessionStore store = new SessionStore(dir);
        seedOriginal(store, "sig==");
        Path parentLog = store.logPath(REPO_KEY, ORIGINAL);
        byte[] before = Files.readAllBytes(parentLog);
        Compactor compactor = compactorOver(
                new ScriptedBedrockClient().throwing(
                        software.amazon.awssdk.services.bedrockruntime.model.ValidationException.builder()
                                .message("down").build()),
                store, 2);

        compactor.compact(request(CompactionTrigger.THRESHOLD));

        assertArrayEquals(before, Files.readAllBytes(parentLog),
                "INV-4/INV-5: the parent is byte-identical even when compaction fails");
    }

    // --- construction + input validation --------------------------------------------

    @Test
    @DisplayName("the compactor requires its collaborators and a valid summarizer model / tail")
    void constructorRejectsBadArgs(@TempDir Path dir) {
        SessionStore store = new SessionStore(dir);
        ModelClient client = new ModelClient(new ScriptedBedrockClient());
        ToolConfiguration tools = sessionToolConfig();
        assertThrows(NullPointerException.class,
                () -> new Compactor(null, store, replay, () -> TS, SUMMARIZER_MODEL, 2, tools));
        assertThrows(NullPointerException.class,
                () -> new Compactor(client, null, replay, () -> TS, SUMMARIZER_MODEL, 2, tools));
        assertThrows(NullPointerException.class,
                () -> new Compactor(client, store, null, () -> TS, SUMMARIZER_MODEL, 2, tools));
        assertThrows(NullPointerException.class,
                () -> new Compactor(client, store, replay, null, SUMMARIZER_MODEL, 2, tools));
        assertThrows(IllegalArgumentException.class,
                () -> new Compactor(client, store, replay, () -> TS, "  ", 2, tools));
        assertThrows(IllegalArgumentException.class,
                () -> new Compactor(client, store, replay, () -> TS, SUMMARIZER_MODEL, -1, tools));
    }

    @Test
    @DisplayName("the compactor tolerates a null toolConfig (the genuinely no-tools session)")
    void constructorToleratesNullToolConfig(@TempDir Path dir) {
        // Oracle: ModelClient.converse Javadoc / §6.A.1 — toolConfig is "null when no tools are
        // offered". A session with no tools has no tool blocks in its transcript, so the absent
        // toolConfig does not violate the wire rule. The constructor must therefore accept null
        // toolConfig (it is null-tolerant ONLY for this case), unlike the other references.
        SessionStore store = new SessionStore(dir);
        ModelClient client = new ModelClient(new ScriptedBedrockClient());
        Compactor compactor = new Compactor(
                client, store, replay, () -> TS, SUMMARIZER_MODEL, 2, (ToolConfiguration) null);
        assertThrows(NullPointerException.class, () -> compactor.compact(null),
                "the compactor still validates its other contracts when toolConfig is null");
    }

    @Test
    @DisplayName("a compaction request rejects a derived id equal to the original (INV-4: no in-place mutation)")
    void requestRejectsSelfDerivation() {
        // Oracle: INV-4 — compaction derives a NEW session, never mutates in place. A request whose
        // derived id equals the original would be an in-place mutation and is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new CompactionRequest(REPO_KEY, ORIGINAL, ORIGINAL, CompactionTrigger.THRESHOLD),
                "INV-4: the derived id must differ from the original");
    }

    @Test
    @DisplayName("compact rejects a null request (defensive contract)")
    void compactRejectsNull(@TempDir Path dir) {
        Compactor compactor = compactorOver(new ScriptedBedrockClient(), new SessionStore(dir), 2);
        assertThrows(NullPointerException.class, () -> compactor.compact(null));
    }

    // --- helpers --------------------------------------------------------------------

    private static boolean errorAppended(SessionStore store, String sessionId) {
        return store.readEvents(REPO_KEY, sessionId).stream()
                .map(Event::payload)
                .anyMatch(p -> p instanceof ErrorPayload);
    }

    private static boolean toolResultsArePaired(List<ConverseMessage> messages) {
        Set<String> seenToolUseIds = new HashSet<>();
        for (ConverseMessage message : messages) {
            for (ContentBlock block : message.content()) {
                if (block instanceof ContentBlock.ToolUse toolUse) {
                    seenToolUseIds.add(toolUse.toolUseId());
                } else if (block instanceof ContentBlock.ToolResult toolResult
                        && !seenToolUseIds.contains(toolResult.toolUseId())) {
                    return false;
                }
            }
        }
        return true;
    }
}
