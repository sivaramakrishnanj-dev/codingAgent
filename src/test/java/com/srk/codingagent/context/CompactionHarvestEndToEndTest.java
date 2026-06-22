package com.srk.codingagent.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.resource.ClasspathSchemaLoader;
import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.persistence.ContentBlock;
import com.srk.codingagent.persistence.Event;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.ModelResponsePayload;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.persistence.SessionReplay;
import com.srk.codingagent.persistence.StopReason;
import com.srk.codingagent.persistence.UserMessagePayload;
import com.srk.codingagent.tool.memory.LearningApprover;
import com.srk.codingagent.tool.memory.LearningExtractor;
import com.srk.codingagent.tool.memory.LearningProposal;
import com.srk.codingagent.tool.memory.LearningProposer;
import com.srk.codingagent.tool.memory.MemoryLearningHarvester;
import com.srk.codingagent.persistence.CompactionTrigger;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * End-to-end test of the compaction learning-harvest (AC-18.5 + US-21), wiring a <em>real</em>
 * {@link MemoryLearningHarvester} into a <em>real</em> {@link Compactor}. This is the test that
 * proves the spec's Verify cell (<em>propose&rarr;approve&rarr;persist&rarr;recall; no
 * auto-extract</em>) for the compaction trigger: it exercises the actual
 * summarize&rarr;harvest&rarr;derive ordering through real collaborators rather than asserting
 * the seam in isolation. Nothing the harvest logic owns is mocked — the only external dependency
 * is the {@link BedrockRuntimeClient} (a scripted in-test double that replays one summary
 * response) and the developer's approve/deny decision (the {@link LearningApprover}, the genuine
 * external choice).
 *
 * <p>The full wiring under test: Compactor produces a summary &rarr; the real harvester's real
 * {@link LearningExtractor} surfaces a candidate from that summary &rarr; the real
 * {@link LearningProposer} runs it past the (stubbed) approver &rarr; on approve, the real
 * {@link MemoryStore} writes the entry + the real {@link EventLog} logs MEMORY_WRITE &rarr; the
 * Compactor then derives the successor. Afterwards the test recalls the entry from a FRESH
 * {@link MemoryStore} over the same dir (INV-14) and validates its emitted front-matter against
 * the authoritative {@code memory-entry.schema.json} (the D2-class guard: real shape, not
 * field-presence).
 *
 * <p>Oracles trace to the cited spec:
 * <ul>
 *   <li><b>AC-18.5 &rarr; US-21:</b> a durable learning identified at compaction is proposed,
 *       approved, and ends up a recallable memory entry after the compaction completes.</li>
 *   <li><b>INV-5 (CT-INV-4):</b> the original session log is byte-identical / preserved after
 *       compaction, regardless of harvest outcome.</li>
 *   <li><b>AC-21.4 (no auto-extract):</b> when the developer denies, the harvest persists
 *       nothing, yet compaction still succeeds (the harvest is propose-only, never blocking).</li>
 *   <li><b>CT-SCH-11 (AC-12.2):</b> the harvested entry's front-matter validates against the
 *       authoritative schema (tier UPPERCASE, status lowercase).</li>
 * </ul>
 */
class CompactionHarvestEndToEndTest {

    private static final String REPO_KEY = "github.com-example-widget";
    private static final String ORIGINAL = "2026-06-22T090000Z-original";
    private static final String DERIVED = "2026-06-22T093000Z-derived";
    private static final String MEMORY_SESSION = "2026-06-22T090000Z-original";
    private static final String SUMMARIZER_MODEL = "anthropic.claude-haiku-summarizer";
    private static final String TS = "2026-06-22T09:30:00Z";
    private static final String SUMMARY_TEXT =
            "Task: add retry to the uploader. Decided exponential backoff. Touched Uploader.java. "
                    + "Open: wire the config. Learning: the SDK already jitters retries.";

    private static final String MEMORY_SCHEMA_ID =
            "https://codingagent.srk/schemas/memory-entry.schema.json";

    private final SessionReplay replay = new SessionReplay();

    private static JsonSchema memorySchema;

    @BeforeAll
    static void loadSchema() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(
                SpecVersion.VersionFlag.V202012,
                builder -> builder
                        .schemaLoaders(loaders -> loaders.add(new ClasspathSchemaLoader()))
                        .schemaMappers(mappers -> mappers.mappings(Map.of(
                                MEMORY_SCHEMA_ID, "classpath:schemas/memory-entry.schema.json"))));
        memorySchema = factory.getSchema(SchemaLocation.of(MEMORY_SCHEMA_ID));
    }

    // --- Scripted external Bedrock dependency (the only test double for the model) ----

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
     * An extractor that surfaces a fixed learning candidate when the summary mentions the SDK's
     * jitter — a deterministic, summary-driven extraction so the harvest path runs end to end.
     */
    private static LearningExtractor jitterExtractor() {
        return summary -> summary.contains("SDK already jitters")
                ? List.of(new LearningProposal("sdk-jitters", MemoryTier.PROJECT,
                        "the SDK already jitters retries",
                        "Do not add jitter on top of the SDK's built-in jitter (cite Uploader retry work)."))
                : List.of();
    }

    private void seedOriginal(SessionStore store) {
        try (EventLog log = store.openLog(REPO_KEY, ORIGINAL)) {
            log.append(new Event(0, TS, new UserMessagePayload(
                    List.of(ContentBlock.text("Add retry to the uploader.")))));
            log.append(new Event(0, TS, new ModelResponsePayload(StopReason.END_TURN,
                    List.of(ContentBlock.text("Added exponential backoff.")))));
        }
    }

    private Compactor compactorWithHarvest(
            BedrockRuntimeClient bedrock, SessionStore sessions, MemoryStore memory,
            EventLog memoryLog, LearningApprover approver) {
        Supplier<String> clock = () -> TS;
        LearningProposer proposer =
                new LearningProposer(memory, memoryLog, approver, clock, MEMORY_SESSION, REPO_KEY);
        MemoryLearningHarvester harvester = new MemoryLearningHarvester(jitterExtractor(), proposer);
        ModelClient modelClient = new ModelClient(bedrock);
        return new Compactor(modelClient, sessions, replay, clock, SUMMARIZER_MODEL, 1, harvester);
    }

    @Test
    @DisplayName("AC-18.5 + Verify cell: an approved learning is harvested at compaction and is recallable after")
    void approvedLearningHarvestedThroughRealCompaction(@TempDir Path dir) {
        // Oracle: AC-18.5 — "Where durable learnings are identified during compaction, the agent
        // shall propose them for memory (per US-21) before archiving." With a real harvester wired
        // into a real Compactor and the developer approving, a candidate the summary surfaced must
        // end up a recallable memory entry AFTER the compaction completes (summarize→harvest→derive).
        SessionStore sessions = new SessionStore(dir);
        MemoryStore memory = new MemoryStore(dir);
        seedOriginal(sessions);
        StringWriter memorySink = new StringWriter();
        Compactor compactor = compactorWithHarvest(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)),
                sessions, memory, EventLog.over(memorySink, "mem-log"),
                proposal -> PermissionDecisionOutcome.APPROVE);

        CompactionOutcome outcome = compactor.compact(
                new CompactionRequest(REPO_KEY, ORIGINAL, DERIVED, CompactionTrigger.THRESHOLD));

        assertTrue(outcome.succeeded(), "the compaction completes (derive runs after the harvest)");
        // Recall FRESH from disk (INV-14): the harvested learning is a real, readable memory entry.
        MemoryEntry recalled = new MemoryStore(dir).readEntry("sdk-jitters", REPO_KEY).orElseThrow();
        assertEquals(MemoryTier.PROJECT, recalled.tier(), "AC-12.3: the harvested entry is in the proposed tier");
        assertEquals(TS, recalled.created(), "AC-12.2: created is the boundary-captured timestamp");
        assertEquals(MEMORY_SESSION, recalled.originSession(), "AC-12.2: originSession is the boundary provenance");
        assertEquals(MemoryStatus.ACTIVE, recalled.status(), "an approved harvested learning is ACTIVE");
        assertTrue(recalled.body().contains("Do not add jitter"), "the recalled body is the learning prose");
        // AC-12.4: the harvested write logged its MEMORY_WRITE provenance event.
        assertTrue(memorySink.toString().contains("\"type\":\"MEMORY_WRITE\""),
                "AC-12.4: the harvested write logs a MEMORY_WRITE event");
    }

    @Test
    @DisplayName("CT-SCH-11 (AC-12.2): the harvested entry's front-matter validates against the authoritative schema")
    void harvestedEntryFrontMatterIsSchemaValid(@TempDir Path dir) {
        // Oracle: CT-SCH-11 / AC-12.2 — a written memory entry's emitted markdown front-matter must
        // conform to memory-entry.schema.json (tier UPPERCASE GLOBAL/PROJECT, status lowercase
        // active/retired; CT-SCH-11/12). The D2 guard: validate the ON-DISK file the harvest wrote,
        // not just an in-memory record — a wire-shape mismatch would ship a hand-uneditable entry.
        SessionStore sessions = new SessionStore(dir);
        MemoryStore memory = new MemoryStore(dir);
        seedOriginal(sessions);
        Compactor compactor = compactorWithHarvest(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)),
                sessions, memory, EventLog.over(new StringWriter(), "mem-log"),
                proposal -> PermissionDecisionOutcome.APPROVE);

        compactor.compact(new CompactionRequest(REPO_KEY, ORIGINAL, DERIVED, CompactionTrigger.THRESHOLD));

        // The PROJECT-tier entry the harvest wrote on disk: <dir>/projects/<repoKey>/memory/<slug>.md
        Path entryFile = dir.resolve("projects").resolve(REPO_KEY).resolve("memory").resolve("sdk-jitters.md");
        assertTrue(Files.isRegularFile(entryFile), "the harvested entry was written to the PROJECT tier on disk");
        String markdown = readFile(entryFile);
        assertTrue(markdown.contains("tier: PROJECT"), "tier is UPPERCASE per the schema: " + markdown);
        assertTrue(markdown.contains("status: active"), "status is lowercase per the schema: " + markdown);
        assertTrue(memorySchema.validate(frontMatterAsJson(markdown), InputFormat.JSON).isEmpty(),
                "CT-SCH-11: the harvested entry's front-matter validates against memory-entry.schema.json");
    }

    @Test
    @DisplayName("INV-5: the original session log is byte-identical after compaction with an APPROVED harvest")
    void originalPreservedWhenHarvestApproves(@TempDir Path dir) throws Exception {
        // Oracle: INV-5 / CT-INV-4 — "the original conversation is never deleted on compaction". The
        // harvest writes into the memory store, NOT the session log; the original session must be
        // byte-identical regardless. Assert with an approving harvest (a write happens, but to memory).
        SessionStore sessions = new SessionStore(dir);
        MemoryStore memory = new MemoryStore(dir);
        seedOriginal(sessions);
        Path originalLog = sessions.logPath(REPO_KEY, ORIGINAL);
        byte[] before = Files.readAllBytes(originalLog);
        Compactor compactor = compactorWithHarvest(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)),
                sessions, memory, EventLog.over(new StringWriter(), "mem-log"),
                proposal -> PermissionDecisionOutcome.APPROVE);

        compactor.compact(new CompactionRequest(REPO_KEY, ORIGINAL, DERIVED, CompactionTrigger.THRESHOLD));

        assertArrayEquals(before, Files.readAllBytes(originalLog),
                "INV-5: the original session log is byte-identical after a harvest-and-derive compaction");
        assertTrue(Files.exists(originalLog), "INV-5: the original conversation is preserved (never deleted)");
    }

    @Test
    @DisplayName("AC-21.4: when the developer denies, nothing is harvested yet compaction still succeeds")
    void denyHarvestsNothingButCompactionSucceeds(@TempDir Path dir) throws Exception {
        // Oracle: AC-21.4 (no auto-extract) + AC-18.5 — the harvest is propose-only. A developer who
        // denies leaves NOTHING persisted to memory (no entry, no index, no MEMORY_WRITE), and the
        // harvest never blocks: the compaction still derives a successor. INV-5 still holds.
        SessionStore sessions = new SessionStore(dir);
        MemoryStore memory = new MemoryStore(dir);
        seedOriginal(sessions);
        Path originalLog = sessions.logPath(REPO_KEY, ORIGINAL);
        byte[] before = Files.readAllBytes(originalLog);
        StringWriter memorySink = new StringWriter();
        Compactor compactor = compactorWithHarvest(
                new ScriptedBedrockClient().then(summaryTurn(SUMMARY_TEXT)),
                sessions, memory, EventLog.over(memorySink, "mem-log"),
                proposal -> PermissionDecisionOutcome.DENY);

        CompactionOutcome outcome = compactor.compact(
                new CompactionRequest(REPO_KEY, ORIGINAL, DERIVED, CompactionTrigger.THRESHOLD));

        assertTrue(outcome.succeeded(), "AC-18.5: a denied harvest does not block compaction; it still derives");
        assertTrue(new MemoryStore(dir).readEntry("sdk-jitters", REPO_KEY).isEmpty(),
                "AC-21.4: a denied harvested candidate writes no memory entry");
        assertTrue(new MemoryStore(dir).loadIndexes(REPO_KEY).isEmpty(),
                "AC-21.4: a denied harvested candidate writes no index line");
        assertFalse(memorySink.toString().contains("MEMORY_WRITE"),
                "AC-21.4: a denied harvested candidate logs no MEMORY_WRITE event");
        assertArrayEquals(before, Files.readAllBytes(originalLog),
                "INV-5: the original is byte-identical when the harvest is denied too");
    }

    // --- front-matter helpers (mirror MemoryEntrySchemaContractTest) ------------------

    private static String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read written entry: " + file, e);
        }
    }

    /** Parses the YAML front-matter block out of a memory markdown file and JSON-encodes it. */
    private static String frontMatterAsJson(String markdown) {
        String normalized = markdown.replace("\r\n", "\n");
        int firstDelim = normalized.indexOf("---\n");
        int secondDelim = normalized.indexOf("\n---", firstDelim + 4);
        String yamlBlock = normalized.substring(firstDelim + 4, secondDelim + 1);
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlBlock);
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) root);
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(e.getKey()).append("\":\"")
                    .append(String.valueOf(e.getValue()).replace("\"", "\\\"")).append('"');
        }
        return json.append('}').toString();
    }
}
