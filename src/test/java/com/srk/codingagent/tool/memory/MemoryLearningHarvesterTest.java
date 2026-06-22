package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.context.LearningHarvester;
import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MemoryLearningHarvester} — the compaction learning-harvest wiring (component
 * C6 &rarr; C12, AC-18.5, US-21, INV-13/AC-21.4). The SUT is a real
 * {@link MemoryLearningHarvester} composed with a real {@link LearningProposer} over a real
 * {@link MemoryStore} ({@link TempDir}) and a real {@link EventLog} ({@link StringWriter}); the
 * injected stubs are only the true decision boundaries — the {@link LearningExtractor} (what the
 * summary surfaced) and the {@link LearningApprover} (the developer's approve/deny). No SUT is
 * mocked: the extract&rarr;propose&rarr;approve&rarr;write path runs for real.
 *
 * <p>Oracles trace to the cited spec, not to the SUT's code:
 * <ul>
 *   <li><b>AC-18.5 &rarr; US-21:</b> durable learnings identified in the summary are
 *       <em>proposed</em> for memory; each candidate is run through the propose-and-approve
 *       path.</li>
 *   <li><b>AC-21.4 / INV-13:</b> the harvest never persists without approval — a denied
 *       candidate writes nothing; a summary that surfaces none harvests nothing (no
 *       auto-extract at the harvest moment).</li>
 *   <li><b>AC-21.3:</b> an approved harvested candidate is written, re-readable fresh from disk
 *       with its provenance + body intact (the D2-class "real shape, not field-presence" guard).</li>
 * </ul>
 */
class MemoryLearningHarvesterTest {

    private static final String SESSION = "2026-06-22T09-00-00-sess";
    private static final String REPO_KEY = "github.com_srk_codingagent";
    private static final String CREATED = "2026-06-22T10:00:00Z";
    private static final Supplier<String> CLOCK = () -> CREATED;

    /** An extractor that surfaces a fixed list of candidates regardless of the summary text. */
    private static LearningExtractor extractorOf(LearningProposal... candidates) {
        List<LearningProposal> fixed = List.of(candidates);
        return summary -> fixed;
    }

    /** An approver that records each proposal it is shown and returns a fixed decision. */
    private static final class RecordingApprover implements LearningApprover {
        private final PermissionDecisionOutcome decision;
        private final List<LearningProposal> shown = new ArrayList<>();

        RecordingApprover(PermissionDecisionOutcome decision) {
            this.decision = decision;
        }

        @Override
        public PermissionDecisionOutcome approve(LearningProposal proposal) {
            shown.add(proposal);
            return decision;
        }
    }

    private MemoryLearningHarvester harvester(
            MemoryStore store, EventLog log, LearningExtractor extractor, LearningApprover approver) {
        LearningProposer proposer = new LearningProposer(store, log, approver, CLOCK, SESSION, REPO_KEY);
        return new MemoryLearningHarvester(extractor, proposer);
    }

    @Test
    @DisplayName("AC-18.5 + AC-21.1: every extracted candidate is proposed to the developer for approval")
    void everyCandidateIsProposed(@TempDir Path dir) {
        // Oracle: AC-18.5 — "durable learnings identified during compaction ... shall propose
        // them for memory (per US-21)". + AC-21.1 — each is proposed to the developer. With two
        // extracted candidates and an approver that denies, BOTH must still be presented (proposal
        // happens before the decision; the decision then persists nothing).
        RecordingApprover approver = new RecordingApprover(PermissionDecisionOutcome.DENY);
        MemoryLearningHarvester harvester = harvester(
                new MemoryStore(dir), EventLog.over(new StringWriter(), "log"),
                extractorOf(
                        new LearningProposal("learning-a", MemoryTier.PROJECT, "why-a", "body-a"),
                        new LearningProposal("learning-b", MemoryTier.GLOBAL, "why-b", "body-b")),
                approver);

        harvester.harvest("summary text with two durable learnings");

        assertEquals(2, approver.shown.size(),
                "AC-18.5/AC-21.1: every extracted durable learning is proposed to the developer");
        assertEquals(List.of("learning-a", "learning-b"),
                approver.shown.stream().map(LearningProposal::slug).toList(),
                "AC-18.5: the candidates the summary surfaced are the ones proposed, in order");
    }

    @Test
    @DisplayName("AC-18.5 + AC-21.3: an approved harvested candidate is written and recallable with provenance")
    void approvedCandidateIsHarvestedAndRecallable(@TempDir Path dir) {
        // Oracle: AC-18.5 → AC-21.3 — an approved durable learning identified at compaction is
        // written as a memory entry with provenance. The D2 guard: re-read FRESH from disk (a new
        // MemoryStore over the same dir, INV-14) and assert the actual shape — tier/created/
        // originSession/why/status/body against the proposal + boundary clock — not just present.
        MemoryStore store = new MemoryStore(dir);
        StringWriter sink = new StringWriter();
        MemoryLearningHarvester harvester = harvester(store, EventLog.over(sink, "log"),
                extractorOf(new LearningProposal(
                        "sdk-jitters", MemoryTier.GLOBAL, "the SDK already jitters retries",
                        "Do not add jitter on top of the SDK's built-in jitter.")),
                new RecordingApprover(PermissionDecisionOutcome.APPROVE));

        int persisted = harvester.harvest("Learning: the SDK already jitters.");

        assertEquals(1, persisted, "AC-18.5: one approved learning was harvested and persisted");
        MemoryEntry recalled = new MemoryStore(dir).readEntry("sdk-jitters", REPO_KEY).orElseThrow();
        assertEquals(MemoryTier.GLOBAL, recalled.tier(), "AC-12.3: the harvested entry is in the proposed tier");
        assertEquals(CREATED, recalled.created(), "AC-12.2: created is the boundary-captured timestamp");
        assertEquals(SESSION, recalled.originSession(), "AC-12.2: originSession is the boundary provenance");
        assertEquals("the SDK already jitters retries", recalled.why(), "AC-12.2: why is the proposal's provenance");
        assertEquals(com.srk.codingagent.memory.MemoryStatus.ACTIVE, recalled.status(),
                "an approved harvested learning is written ACTIVE");
        assertEquals("Do not add jitter on top of the SDK's built-in jitter.", recalled.body().strip(),
                "the recalled body is the proposed learning prose");
        // The provenance event lands too (AC-12.4) — the same MEMORY_WRITE the explicit path logs.
        assertTrue(sink.toString().contains("\"type\":\"MEMORY_WRITE\""),
                "AC-12.4: an approved harvested write logs a MEMORY_WRITE event");
    }

    @Test
    @DisplayName("AC-21.4 / INV-13: a denied harvested candidate persists nothing (no file, index, or event)")
    void deniedCandidatePersistsNothing(@TempDir Path dir) {
        // Oracle: AC-21.4 / INV-13 / CT-INV-11 — no auto-extract: a learning the developer rejects
        // at the harvest moment writes NOTHING. Assert all three persistence surfaces are absent:
        // no entry file on disk, no index line, no MEMORY_WRITE event.
        MemoryStore store = new MemoryStore(dir);
        StringWriter sink = new StringWriter();
        MemoryLearningHarvester harvester = harvester(store, EventLog.over(sink, "log"),
                extractorOf(new LearningProposal("rejected-harvest", MemoryTier.PROJECT, "w", "b")),
                new RecordingApprover(PermissionDecisionOutcome.DENY));

        int persisted = harvester.harvest("Learning: something the developer will reject.");

        assertEquals(0, persisted, "AC-21.4: a denied harvested candidate persists nothing");
        assertTrue(new MemoryStore(dir).readEntry("rejected-harvest", REPO_KEY).isEmpty(),
                "AC-21.4/INV-13: no entry file is written for a rejected harvested candidate");
        assertTrue(new MemoryStore(dir).loadIndexes(REPO_KEY).isEmpty(),
                "AC-21.4: no index line is written for a rejected harvested candidate");
        assertFalse(sink.toString().contains("MEMORY_WRITE"),
                "AC-21.4: no MEMORY_WRITE event is logged for a rejected harvested candidate");
    }

    @Test
    @DisplayName("AC-21.4 / INV-13: a summary that surfaces no learnings harvests nothing (no auto-extract)")
    void noCandidatesHarvestsNothing(@TempDir Path dir) {
        // Oracle: AC-21.4 / INV-13 — when the summary surfaces no durable learnings the harvest
        // proposes nothing and persists nothing. LearningExtractor.NONE is exactly this baseline.
        MemoryStore store = new MemoryStore(dir);
        StringWriter sink = new StringWriter();
        MemoryLearningHarvester harvester = harvester(store, EventLog.over(sink, "log"),
                LearningExtractor.NONE, new RecordingApprover(PermissionDecisionOutcome.APPROVE));

        int persisted = harvester.harvest("A summary with no durable learnings worth remembering.");

        assertEquals(0, persisted, "INV-13: an empty extraction harvests nothing");
        assertTrue(new MemoryStore(dir).loadIndexes(REPO_KEY).isEmpty(),
                "INV-13: nothing is persisted when the summary surfaces no learnings");
        assertFalse(sink.toString().contains("MEMORY_WRITE"),
                "AC-21.4: no MEMORY_WRITE event when nothing is harvested");
    }

    @Test
    @DisplayName("AC-21.2: a selective approver harvests only the approved candidates (count = approved)")
    void harvestCountIsApprovedCount(@TempDir Path dir) {
        // Oracle: AC-21.2 + AC-21.3 — approval is per-candidate. With three candidates and an
        // approver that approves only the middle one, exactly one is persisted; the returned count
        // is the number approved+persisted, not the number proposed.
        MemoryStore store = new MemoryStore(dir);
        LearningApprover selective = proposal ->
                "keep".equals(proposal.slug()) ? PermissionDecisionOutcome.APPROVE : PermissionDecisionOutcome.DENY;
        MemoryLearningHarvester harvester = harvester(store, EventLog.over(new StringWriter(), "log"),
                extractorOf(
                        new LearningProposal("drop-1", MemoryTier.GLOBAL, "w", "b"),
                        new LearningProposal("keep", MemoryTier.GLOBAL, "w", "b"),
                        new LearningProposal("drop-2", MemoryTier.PROJECT, "w", "b")),
                selective);

        int persisted = harvester.harvest("three candidates");

        assertEquals(1, persisted, "AC-21.2/AC-21.3: the harvest count is the number approved");
        assertTrue(new MemoryStore(dir).readEntry("keep", REPO_KEY).isPresent(), "the approved candidate persists");
        assertTrue(new MemoryStore(dir).readEntry("drop-1", REPO_KEY).isEmpty(), "a denied candidate does not persist");
        assertTrue(new MemoryStore(dir).readEntry("drop-2", REPO_KEY).isEmpty(), "a denied candidate does not persist");
    }

    @Test
    @DisplayName("the harvester requires its extractor and proposer (defensive contract)")
    void constructorValidates(@TempDir Path dir) {
        LearningProposer proposer = new LearningProposer(new MemoryStore(dir),
                EventLog.over(new StringWriter(), "log"), LearningApprover.DENY_ALL, CLOCK, SESSION, REPO_KEY);
        assertThrows(NullPointerException.class, () -> new MemoryLearningHarvester(null, proposer));
        assertThrows(NullPointerException.class,
                () -> new MemoryLearningHarvester(LearningExtractor.NONE, null));
    }

    @Test
    @DisplayName("harvest rejects a null summary (defensive contract)")
    void harvestRejectsNullSummary(@TempDir Path dir) {
        MemoryLearningHarvester harvester = harvester(new MemoryStore(dir),
                EventLog.over(new StringWriter(), "log"), LearningExtractor.NONE, LearningApprover.DENY_ALL);
        assertThrows(NullPointerException.class, () -> harvester.harvest(null));
    }

    @Test
    @DisplayName("LearningHarvester.NONE proposes nothing (the no-harvest baseline)")
    void noneHarvesterIsNoOp() {
        // Oracle: AC-18.5 / ADR-0006 — when no memory harvest is configured the Compactor uses
        // LearningHarvester.NONE, which proposes nothing. It must return 0 for any summary.
        assertEquals(0, LearningHarvester.NONE.harvest("any summary text"),
                "LearningHarvester.NONE harvests nothing (the default no-op seam)");
    }

    @Test
    @DisplayName("LearningExtractor.NONE surfaces no candidates (the empty-extraction baseline)")
    void noneExtractorIsEmpty() {
        // Oracle: INV-13 / AC-21.4 — the default extraction surfaces no learnings, so nothing is
        // proposed and nothing is persisted.
        assertTrue(LearningExtractor.NONE.extract("any summary").isEmpty(),
                "LearningExtractor.NONE surfaces no learning candidates");
    }
}
