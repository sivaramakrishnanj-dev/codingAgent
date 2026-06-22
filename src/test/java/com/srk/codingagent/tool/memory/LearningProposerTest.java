package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryEntry;
import com.srk.codingagent.memory.MemoryStatus;
import com.srk.codingagent.memory.MemoryStore;
import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.EventLog;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LearningProposer} — the propose-and-approve write path (component C12,
 * US-21, ADR-0007, AC-21.1/21.2/21.3/21.4, INV-13). The SUT is a real {@link LearningProposer}
 * wired to a real {@link MemoryStore} over a {@link TempDir} and a real {@link EventLog} over a
 * {@link StringWriter}; the only injected stub is the {@link LearningApprover} — the developer
 * approve/deny boundary — so the approve and deny outcomes are exercised deterministically
 * (the developer is the sole external decision; no SUT is mocked).
 *
 * <p>Oracles trace to the cited spec, not to the SUT's code:
 * <ul>
 *   <li><b>AC-21.1:</b> a proposed learning is presented to the developer (the approver is
 *       consulted with the proposal).</li>
 *   <li><b>AC-21.3 / AC-12.4 (Verify cell: propose→approve→persist→recall):</b> on approval the
 *       learning is written with provenance and a MEMORY_WRITE event is logged, and it is
 *       recallable on a fresh read.</li>
 *   <li><b>AC-21.2 / AC-21.4 / INV-13 / CT-INV-11:</b> a not-approved proposal persists nothing
 *       — no file, no index line, no MEMORY_WRITE event.</li>
 * </ul>
 */
class LearningProposerTest {

    private static final String SESSION = "2026-06-22T09-00-00-sess";
    private static final String REPO_KEY = "github.com_srk_codingagent";
    private static final Supplier<String> CLOCK = () -> "2026-06-22T10:00:00Z";

    /** A learning approver that records each proposal it is shown and returns a fixed decision. */
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

    private LearningProposer proposer(MemoryStore store, EventLog log, LearningApprover approver) {
        return new LearningProposer(store, log, approver, CLOCK, SESSION, REPO_KEY);
    }

    @Test
    @DisplayName("AC-21.1: a proposed learning is presented to the developer for approval")
    void proposalIsPresentedToDeveloper(@TempDir Path dir) {
        // Oracle: AC-21.1 — "When the agent identifies a durable, reusable learning ... it shall
        // propose it to the developer for approval." The proposer must consult the approver with
        // the proposed learning before deciding anything.
        RecordingApprover approver = new RecordingApprover(PermissionDecisionOutcome.DENY);
        LearningProposer proposer =
                proposer(new MemoryStore(dir), EventLog.over(new StringWriter(), "log"), approver);
        LearningProposal proposal =
                new LearningProposal("avoid-rm-rf", MemoryTier.PROJECT, "destructive command", "never rm -rf /");

        proposer.propose(proposal);

        assertEquals(1, approver.shown.size(), "AC-21.1: the learning is proposed to the developer");
        assertEquals(proposal, approver.shown.get(0), "AC-21.1: the exact proposed learning is presented");
    }

    @Test
    @DisplayName("AC-21.3 + Verify cell: approve→persist→recall — an approved learning is written and recallable")
    void approvedLearningIsPersistedAndRecallable(@TempDir Path dir) {
        // Oracle: AC-21.3 — "When the developer approves a proposed learning, the agent shall write
        // it as a memory entry with provenance." + the Verify cell's propose→approve→persist→recall:
        // after approval the entry is written AND recallable on a subsequent fresh load (the same
        // store.readEntry path read_memory uses, INV-14).
        MemoryStore store = new MemoryStore(dir);
        LearningProposer proposer =
                proposer(store, EventLog.over(new StringWriter(), "log"),
                        new RecordingApprover(PermissionDecisionOutcome.APPROVE));

        Optional<MemoryEntry> written = proposer.propose(
                new LearningProposal("use-jitter", MemoryTier.GLOBAL, "the SDK jitters", "add jitter to retries"));

        assertTrue(written.isPresent(), "AC-21.3: an approved learning is persisted");
        // Recall: a FRESH store re-reads from disk (INV-14) — proves the round-trip, not a cache.
        MemoryEntry recalled = new MemoryStore(dir).readEntry("use-jitter", REPO_KEY).orElseThrow();
        assertEquals(MemoryTier.GLOBAL, recalled.tier(), "AC-12.3: the approved entry is in the chosen tier");
        assertEquals("add jitter to retries", recalled.body().strip(), "the recalled body is the learning prose");
    }

    @Test
    @DisplayName("AC-21.3: an approved write appears in the index so it is recallable from the index")
    void approvedLearningAppearsInIndex(@TempDir Path dir) {
        // Oracle: the Verify cell's "...→recall" — recall is via the always-loaded index plus the
        // read path. After approve→persist the learning must appear in the loaded index (AC-14.3),
        // which is the awareness surface a later load reads.
        MemoryStore store = new MemoryStore(dir);
        proposer(store, EventLog.over(new StringWriter(), "log"),
                new RecordingApprover(PermissionDecisionOutcome.APPROVE))
                .propose(new LearningProposal("recall-me", MemoryTier.PROJECT, "w", "b"));

        boolean inIndex = new MemoryStore(dir).loadIndexes(REPO_KEY).stream()
                .anyMatch(line -> "recall-me".equals(line.slug()));
        assertTrue(inIndex, "the approved learning appears in the index so it is recallable on a later load");
    }

    @Test
    @DisplayName("AC-21.3 / AC-12.4: an approved write carries provenance and logs a MEMORY_WRITE event")
    void approvedWriteLogsMemoryWriteWithProvenance(@TempDir Path dir) {
        // Oracle: AC-21.3 ("write it as a memory entry WITH provenance") + AC-12.4 ("record memory
        // writes as events in the session log"). The approved write logs a MEMORY_WRITE event
        // carrying the slug, tier, and the boundary-captured originSession provenance — the same
        // event the explicit write_memory path logs (ADR-0007: "every write logs a memory_write
        // event").
        StringWriter sink = new StringWriter();
        MemoryStore store = new MemoryStore(dir);
        MemoryEntry written = proposer(store, EventLog.over(sink, "log"),
                new RecordingApprover(PermissionDecisionOutcome.APPROVE))
                .propose(new LearningProposal("prov-learning", MemoryTier.GLOBAL, "why-prov", "body"))
                .orElseThrow();

        // Provenance on the entry (AC-12.2): created from the boundary clock, originSession injected.
        assertEquals("2026-06-22T10:00:00Z", written.created(), "AC-12.2: created is the boundary timestamp");
        assertEquals(SESSION, written.originSession(), "AC-12.2: originSession is the injected provenance");
        assertEquals(MemoryStatus.ACTIVE, written.status(), "an approved learning is written ACTIVE");

        // The MEMORY_WRITE provenance event (AC-12.4 / ADR-0007).
        String line = sink.toString();
        assertTrue(line.contains("\"type\":\"MEMORY_WRITE\""), "AC-12.4: a MEMORY_WRITE event is logged: " + line);
        assertTrue(line.contains("\"slug\":\"prov-learning\""), "the event records the slug");
        assertTrue(line.contains("\"tier\":\"GLOBAL\""), "the event records the tier");
        assertTrue(line.contains("\"originSession\":\"" + SESSION + "\""), "the event records the provenance session");
    }

    @Test
    @DisplayName("AC-12.4: exactly one MEMORY_WRITE event is logged per approved learning")
    void approvedWriteLogsExactlyOneEvent(@TempDir Path dir) {
        // Oracle: AC-12.4 / ADR-0007 — one write, one provenance event (the audit/rollback handle).
        StringWriter sink = new StringWriter();
        proposer(new MemoryStore(dir), EventLog.over(sink, "log"),
                new RecordingApprover(PermissionDecisionOutcome.APPROVE))
                .propose(new LearningProposal("once-learning", MemoryTier.PROJECT, "w", "b"));

        long count = sink.toString().lines().filter(l -> l.contains("MEMORY_WRITE")).count();
        assertEquals(1, count, "AC-12.4: exactly one MEMORY_WRITE event per approved write");
    }

    @Test
    @DisplayName("AC-21.2 / INV-13 / CT-INV-11: a not-approved learning persists nothing (no file, index, or event)")
    void notApprovedLearningPersistsNothing(@TempDir Path dir) {
        // Oracle: AC-21.2 — "If the developer does not approve a proposed learning, then the agent
        // shall not persist it to memory." + INV-13 / CT-INV-11 (T-2.5 extension): the rejected
        // proposal writes NOTHING — no entry file, no index line, no MEMORY_WRITE event.
        StringWriter sink = new StringWriter();
        MemoryStore store = new MemoryStore(dir);
        LearningProposer proposer = proposer(store, EventLog.over(sink, "log"),
                new RecordingApprover(PermissionDecisionOutcome.DENY));

        Optional<MemoryEntry> written = proposer.propose(
                new LearningProposal("rejected", MemoryTier.PROJECT, "w", "b"));

        assertTrue(written.isEmpty(), "AC-21.2: a not-approved proposal returns nothing persisted");
        assertTrue(new MemoryStore(dir).readEntry("rejected", REPO_KEY).isEmpty(),
                "AC-21.2/INV-13: no entry file is written for a rejected proposal");
        assertTrue(new MemoryStore(dir).loadIndexes(REPO_KEY).isEmpty(),
                "AC-21.2: no index line is written for a rejected proposal");
        assertFalse(sink.toString().contains("MEMORY_WRITE"),
                "AC-21.2/AC-21.4: no MEMORY_WRITE event is logged for a rejected proposal");
    }

    @Test
    @DisplayName("AC-21.2: rejecting one proposal but approving another persists only the approved one")
    void onlyApprovedProposalsPersist(@TempDir Path dir) {
        // Oracle: AC-21.2 + AC-21.3 — approval is per-proposal: a deny persists nothing, an approve
        // persists. An approver that approves only a specific slug must leave the denied one absent
        // and the approved one present.
        MemoryStore store = new MemoryStore(dir);
        LearningApprover selective = proposal ->
                "keep".equals(proposal.slug()) ? PermissionDecisionOutcome.APPROVE : PermissionDecisionOutcome.DENY;
        LearningProposer proposer = proposer(store, EventLog.over(new StringWriter(), "log"), selective);

        proposer.propose(new LearningProposal("drop", MemoryTier.GLOBAL, "w", "b"));
        proposer.propose(new LearningProposal("keep", MemoryTier.GLOBAL, "w", "b"));

        assertTrue(new MemoryStore(dir).readEntry("keep", REPO_KEY).isPresent(), "the approved learning persists");
        assertTrue(new MemoryStore(dir).readEntry("drop", REPO_KEY).isEmpty(), "the denied learning does not persist");
    }

    @Test
    @DisplayName("the constructor rejects null collaborators and a blank session/repo key")
    void constructorValidates(@TempDir Path dir) {
        MemoryStore store = new MemoryStore(dir);
        EventLog log = EventLog.over(new StringWriter(), "log");
        LearningApprover approver = LearningApprover.APPROVE_ALL;
        assertThrows(NullPointerException.class,
                () -> new LearningProposer(null, log, approver, CLOCK, SESSION, REPO_KEY));
        assertThrows(NullPointerException.class,
                () -> new LearningProposer(store, null, approver, CLOCK, SESSION, REPO_KEY));
        assertThrows(NullPointerException.class,
                () -> new LearningProposer(store, log, null, CLOCK, SESSION, REPO_KEY));
        assertThrows(NullPointerException.class,
                () -> new LearningProposer(store, log, approver, null, SESSION, REPO_KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProposer(store, log, approver, CLOCK, " ", REPO_KEY));
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProposer(store, log, approver, CLOCK, SESSION, " "));
    }

    @Test
    @DisplayName("propose rejects a null proposal (defensive contract)")
    void proposeRejectsNull(@TempDir Path dir) {
        LearningProposer proposer = proposer(new MemoryStore(dir),
                EventLog.over(new StringWriter(), "log"), LearningApprover.APPROVE_ALL);
        assertThrows(NullPointerException.class, () -> proposer.propose(null));
    }
}
