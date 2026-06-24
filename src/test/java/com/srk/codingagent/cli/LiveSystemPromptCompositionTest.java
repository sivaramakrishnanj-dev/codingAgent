package com.srk.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.config.ResolvedConfig;
import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.model.converse.ModelClient;
import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GrantStore;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.SessionStore;
import com.srk.codingagent.workflow.BrownfieldPlaybook;
import java.io.StringWriter;
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
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;

/**
 * Live-reachability contract for the production system-prompt composition (T-2.4-RD-D5, a
 * regression-of-T-2.4 live-only wiring fix): the system prompt the production composition root
 * ({@link AgentLoopFactory} via {@link ToolRegistryComposer}) actually assembles for a live
 * {@code codingagent} run must carry the always-loaded two-tier memory INDEX (ADR-0007 "Index +
 * selective load") alongside the brownfield playbook blocks, so a fresh session can see which
 * curated entries exist (slug + one-line description) and call {@code read_memory} with the correct
 * slug instead of guessing.
 *
 * <p>This is the test that would have caught D5: a real-Bedrock smoke session could not recall a
 * stored memory learning because the index was built + maintained on disk
 * ({@link MemoryStore#loadIndexes} is unit-tested) but the live system prompt was the STATIC
 * {@link BrownfieldPlaybook#systemPrompt()} the factory handed straight to the loop — the index was
 * never injected. The assembly is extracted into {@link ToolRegistryComposer#parentSystemPrompt()}
 * (NOT JaCoCo-excluded; the factory is), and this test drives the SAME seam the factory drives,
 * over a {@link MemoryStore} rooted at a {@link TempDir} — so the contract that index content
 * reaches the prompt is pinned under the coverage gate (the same gate-covered-seam pattern as
 * {@link LiveToolRegistryCompositionTest}).
 *
 * <p><b>SUT.</b> The real {@link ToolRegistryComposer} and the real {@link MemoryStore} it loads the
 * index from. No model call is exercised — {@code parentSystemPrompt()} only reads the index and
 * combines it with the playbook — so no scripted Bedrock turn is scripted; the {@link ModelClient}
 * is present only to satisfy the composer's non-null collaborator contract.
 *
 * <p><b>Oracles trace to the spec, not to the seam's code:</b>
 * <ul>
 *   <li><b>ADR-0007 "Index + selective load" / AC-14.3:</b> on session start both tiers' indexes
 *       load into the system prompt; the rendered surface must show each entry's slug and its
 *       one-line description (the model needs both to pick the right slug for {@code read_memory}).</li>
 *   <li><b>INV-14 / AC-14.2 (re-read-fresh, no masking cache):</b> the index is loaded at session
 *       start, not snapshot-and-cached; an entry written after one prompt build appears on the next
 *       build.</li>
 *   <li><b>ADR-0012 / D5 requirement (do not replace or reorder the priming):</b> the playbook
 *       blocks the G1 explore&rarr;edit&rarr;verify gate depends on remain present and in order;
 *       the index is an ADDITIONAL block. An empty index adds no memory section at all.</li>
 * </ul>
 */
class LiveSystemPromptCompositionTest {

    private static final String LINEAGE = "one-shot";
    private static final String MODEL = "anthropic.claude-opus-4-8";
    private static final String CHILD_ID = "child-session-1";
    private static final String TS = "2026-06-22T09:00:00Z";

    /** A {@link BedrockRuntimeClient} that never expects a call (no model turn is exercised). */
    private static final class UnusedBedrockClient implements BedrockRuntimeClient {
        private final Deque<ConverseResponse> script = new ArrayDeque<>();

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            if (script.isEmpty()) {
                throw new IllegalStateException("no model call is expected when assembling the prompt");
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

    private static Approver alwaysApprove() {
        return req -> PermissionDecisionOutcome.APPROVE;
    }

    private static ResolvedConfig config() {
        return new ResolvedConfig(MODEL, PermissionMode.ASK_EVERY_TIME, "us-east-1", null,
                1, null, ResolvedConfig.Commands.empty(), 0.85, 16384, 5, 300, 10, 300);
    }

    /**
     * Builds the composer EXACTLY as {@link AgentLoopFactory#create} builds it — same collaborator
     * wiring, with {@code sessionLineage} threaded as both repoKey and originSession — but over a
     * {@link MemoryStore} rooted at the temp dir. This is the production composition path; only the
     * store root (and the never-called Bedrock client) are test-controlled.
     */
    private static ToolRegistryComposer composer(Path workspace, MemoryStore store) {
        Supplier<String> childIds = () -> CHILD_ID;
        return new ToolRegistryComposer(
                new ModelClient(new UnusedBedrockClient()), config(), workspace,
                EventLog.over(new StringWriter(), "parent"), store, new SessionStore(workspace),
                GrantStore.forSession(LINEAGE), alwaysApprove(), LINEAGE, LINEAGE,
                () -> TS, childIds);
    }

    /** Writes a PROJECT-tier entry through the real store, exercising the real index format. */
    private static void writeEntry(MemoryStore store, String slug, String why, String body) {
        store.write(
                new MemoryEntry(slug, MemoryTier.PROJECT, TS, LINEAGE, why, MemoryStatus.ACTIVE, body),
                LINEAGE);
    }

    private static String joined(List<String> blocks) {
        return String.join("\n", blocks);
    }

    // --- The live contract: a written index entry reaches the assembled system prompt ----------

    @Test
    @DisplayName("ADR-0007/AC-14.3: a written index entry's slug AND description reach the assembled live system prompt")
    void writtenIndexEntryReachesTheSystemPrompt(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: ADR-0007 "Index + selective load" — "On session start, both indexes load into the
        // system prompt"; AC-14.3 — the index lists entries for quick review. The model can only
        // call read_memory with the correct slug if BOTH the slug and its one-line description are
        // visible in the prompt. Expected values trace to the entry we seeded (slug + why) and to
        // the ADR contract, not to the composer's rendered heading text.
        MemoryStore store = new MemoryStore(storeRoot);
        writeEntry(store, "money-values-use-long-cents",
                "represent money as long cents to avoid float rounding", "Long cents, not double.");

        List<String> blocks = composer(workspace, store).parentSystemPrompt();
        String prompt = joined(blocks);

        assertTrue(prompt.contains("money-values-use-long-cents"),
                "ADR-0007: the entry's slug is visible in the system prompt so read_memory can target it");
        assertTrue(prompt.contains("represent money as long cents to avoid float rounding"),
                "AC-14.3: the entry's one-line description is visible so the model can judge relevance");
    }

    @Test
    @DisplayName("ADR-0007: both tiers' index entries (GLOBAL + PROJECT) reach the assembled system prompt")
    void bothTierEntriesReachTheSystemPrompt(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: ADR-0007 "Two tiers" — "on load, both tiers' indexes are read", and "Index +
        // selective load" — both indexes load into the system prompt. A GLOBAL entry and a PROJECT
        // entry must both be visible. Expected slugs trace to the entries seeded into each tier.
        MemoryStore store = new MemoryStore(storeRoot);
        store.write(new MemoryEntry("prefer-slf4j", MemoryTier.GLOBAL, TS, LINEAGE,
                "always use slf4j parameterized logging", MemoryStatus.ACTIVE, "Use {} placeholders."),
                LINEAGE);
        store.write(new MemoryEntry("repo-uses-maven", MemoryTier.PROJECT, TS, LINEAGE,
                "this repo builds with maven not gradle", MemoryStatus.ACTIVE, "mvn clean verify."),
                LINEAGE);

        String prompt = joined(composer(workspace, store).parentSystemPrompt());

        assertTrue(prompt.contains("prefer-slf4j"),
                "ADR-0007: the GLOBAL-tier entry's slug reaches the prompt");
        assertTrue(prompt.contains("repo-uses-maven"),
                "ADR-0007: the PROJECT-tier entry's slug reaches the prompt");
    }

    // --- Empty index: no memory section is fabricated ------------------------------------------

    @Test
    @DisplayName("AC-14.3: an empty index adds NO memory section — the prompt is exactly the playbook blocks")
    void emptyIndexAddsNoMemorySection(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: AC-14.3 / the D5 requirement — when neither tier has an INDEX.md yet, no memory
        // heading is fabricated; the system prompt is exactly the brownfield playbook blocks (the
        // empty-index live path is unchanged from today). The oracle is the playbook's own blocks,
        // which trace to ADR-0012 / BrownfieldPlaybook (the spec'd priming), not to the composer.
        MemoryStore store = new MemoryStore(storeRoot);

        List<String> blocks = composer(workspace, store).parentSystemPrompt();

        assertEquals(BrownfieldPlaybook.systemPrompt(), blocks,
                "empty index → the assembled prompt equals the playbook blocks with no extra section");
    }

    @Test
    @DisplayName("ADR-0012/D5: the brownfield playbook blocks are preserved in order; the index is an ADDITIONAL trailing block")
    void playbookBlocksPreservedAndIndexAppended(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: the D5 requirement — "do NOT replace or reorder the existing explore-before-edit /
        // verify-after-change priming; the index is an ADDITIONAL block". The G1
        // explore→edit→verify→resume gate depends on the playbook blocks. So the assembled blocks
        // must START with the playbook blocks unchanged and in order, with exactly one extra block
        // (the index) appended. The playbook blocks are the oracle (ADR-0012 / BrownfieldPlaybook).
        MemoryStore store = new MemoryStore(storeRoot);
        writeEntry(store, "some-learning", "a curated learning", "body");

        List<String> blocks = composer(workspace, store).parentSystemPrompt();

        List<String> playbook = BrownfieldPlaybook.systemPrompt();
        assertEquals(playbook.size() + 1, blocks.size(),
                "the index is exactly one ADDITIONAL block appended to the playbook blocks");
        assertEquals(playbook, blocks.subList(0, playbook.size()),
                "ADR-0012/D5: the playbook priming blocks are preserved unchanged and in order");
    }

    // --- Re-read-fresh (INV-14): the index is loaded per session start, not cached -------------

    @Test
    @DisplayName("INV-14/AC-14.2: a second entry written after the first prompt build appears on the NEXT build (re-read-fresh, not a cached snapshot)")
    void indexIsReReadFreshPerSessionStart(@TempDir Path workspace, @TempDir Path storeRoot) {
        // Oracle: INV-14 / AC-14.2 — "Memory is re-read from disk on each load — not baked into a
        // resumed transcript"; "a write is immediately visible to a subsequent read". The fix must
        // load the index at session start (per build), NOT snapshot-and-cache it. So: write entry-1,
        // build the prompt (a session start) and assert entry-1 is present; then write entry-2 and
        // build the prompt AGAIN (a fresh session start) and assert entry-2 now appears too. A cached
        // snapshot would miss the second entry. Expected slugs trace to the entries seeded.
        MemoryStore store = new MemoryStore(storeRoot);
        ToolRegistryComposer composer = composer(workspace, store);

        writeEntry(store, "first-learning", "the first curated learning", "first body");
        String firstBuild = joined(composer.parentSystemPrompt());
        assertTrue(firstBuild.contains("first-learning"),
                "the first entry appears on the first session-start prompt build");
        assertFalse(firstBuild.contains("second-learning"),
                "the second entry does not yet exist at the first build");

        writeEntry(store, "second-learning", "the second curated learning", "second body");
        String secondBuild = joined(composer.parentSystemPrompt());
        assertTrue(secondBuild.contains("first-learning") && secondBuild.contains("second-learning"),
                "INV-14/AC-14.2: the second build re-reads the index from disk, so the newly-written "
                        + "entry now appears — proving the load is per-session-start, not a cached snapshot");
    }
}
