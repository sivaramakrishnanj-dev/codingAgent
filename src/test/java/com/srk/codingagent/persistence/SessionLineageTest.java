package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionLineage} — the latest-continuation default (AC-7.4).
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>AC-7.4</b>: "When resuming a session that has compaction-derived continuations,
 *       the agent shall resume the latest continuation in the lineage by default." A
 *       {@code DERIVED_FROM} chain resolves to its tip; a session with no continuation
 *       resolves to itself.</li>
 *   <li><b>INV-4</b>: a compaction continuation has {@code edgeType = DERIVED_FROM}; only
 *       those edges are followed (a {@code SPAWNED_BY} sub-agent edge is not a
 *       continuation).</li>
 *   <li><b>INV-3</b>: at most one parent — the walk terminates (a fabricated cycle is broken
 *       defensively).</li>
 * </ul>
 *
 * <p>The SUT (a real {@link SessionLineage}) is never mocked; the lineage is described with
 * real {@link SessionListing} records (most-recent-first, as the store returns them).
 */
class SessionLineageTest {

    private final SessionLineage lineage = new SessionLineage();

    @Test
    @DisplayName("AC-7.4: a session with no continuation resolves to itself")
    void latestContinuation_noContinuation_returnsSelf() {
        // Oracle: AC-7.4 — the default only diverts when the session HAS compaction-derived
        // continuations. A root with no DERIVED_FROM child resolves to itself. (Until compaction
        // lands in M2, every session is this case.)
        List<SessionListing> listings = List.of(root("solo"));

        assertEquals("solo", lineage.latestContinuation("solo", listings),
                "AC-7.4: a session with no continuation resumes itself");
    }

    @Test
    @DisplayName("AC-7.4: a single DERIVED_FROM continuation resolves to the continuation")
    void latestContinuation_oneHop_returnsChild() {
        // Oracle: AC-7.4 — resuming a session that has a compaction-derived continuation defaults
        // to the continuation, not the (now read-only) original (INV-5).
        List<SessionListing> listings = List.of(
                derived("continuation", "original"),
                root("original"));

        assertEquals("continuation", lineage.latestContinuation("original", listings),
                "AC-7.4: resume defaults to the DERIVED_FROM continuation, not the original");
    }

    @Test
    @DisplayName("AC-7.4: a chain of continuations resolves to the latest (the tip)")
    void latestContinuation_chain_returnsTip() {
        // Oracle: AC-7.4 — "the LATEST continuation in the lineage." Two successive compactions
        // (original -> c1 -> c2) resolve from the original all the way to c2, the tip.
        List<SessionListing> listings = List.of(
                derived("c2", "c1"),
                derived("c1", "original"),
                root("original"));

        assertEquals("c2", lineage.latestContinuation("original", listings),
                "AC-7.4: a chain resolves to the latest continuation (the tip of the derived chain)");
    }

    @Test
    @DisplayName("AC-7.4: resuming from a mid-chain continuation still resolves forward to the tip")
    void latestContinuation_fromMidChain_returnsTip() {
        // Oracle: AC-7.4 — the default is the latest continuation in the lineage regardless of
        // which node was named; resuming c1 (itself a continuation) still defaults to c2.
        List<SessionListing> listings = List.of(
                derived("c2", "c1"),
                derived("c1", "original"),
                root("original"));

        assertEquals("c2", lineage.latestContinuation("c1", listings),
                "resuming a mid-chain continuation still defaults to the tip");
    }

    @Test
    @DisplayName("INV-4: a SPAWNED_BY sub-agent edge is not a continuation and is not followed")
    void latestContinuation_spawnedByEdge_notFollowed() {
        // Oracle: INV-4/INV-11 — a continuation is DERIVED_FROM; a SPAWNED_BY edge is a sub-agent
        // session, NOT a compaction continuation, so the latest-continuation default must not
        // follow it. The parent with only a spawned child resolves to itself.
        List<SessionListing> listings = List.of(
                spawned("subagent", "parent"),
                root("parent"));

        assertEquals("parent", lineage.latestContinuation("parent", listings),
                "INV-4: a SPAWNED_BY edge is not a continuation; resume stays on the parent");
    }

    @Test
    @DisplayName("AC-7.4: among forked continuations of one parent, the most-recent is chosen")
    void latestContinuation_fork_choosesMostRecent() {
        // Oracle: AC-7.4 "latest" — if two sessions derive from the same parent (a re-compaction
        // fork), the most-recently-active one is the default. The listings are most-recent-first
        // (AC-7.1), so the first matching child is the latest.
        List<SessionListing> listings = List.of(
                derivedAt("newer-fork", "original", "2026-06-17T12:00:00Z"),
                derivedAt("older-fork", "original", "2026-06-17T10:00:00Z"),
                root("original"));

        assertEquals("newer-fork", lineage.latestContinuation("original", listings),
                "AC-7.4: the most-recent continuation wins when a parent has forked continuations");
    }

    @Test
    @DisplayName("INV-3: a fabricated lineage cycle is broken so the walk terminates")
    void latestContinuation_cycle_terminates() {
        // Oracle: INV-3 — at most one parent (no cycles). A hand-corrupted meta could fabricate
        // a cycle (a <- b, b <- a); the walk must still terminate (defensive visited-set), not
        // loop forever. The exact tip is implementation-defined under corruption; termination is
        // the contract.
        List<SessionListing> listings = List.of(
                derived("a", "b"),
                derived("b", "a"));

        // Must return (not hang) and return one of the two nodes.
        String tip = lineage.latestContinuation("a", listings);
        assertEquals(true, tip.equals("a") || tip.equals("b"),
                "INV-3: a fabricated cycle is broken; the walk terminates");
    }

    @Test
    @DisplayName("latestContinuation rejects a blank session id and a null listings list")
    void latestContinuation_rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> lineage.latestContinuation("  ", List.of()),
                "a blank session id is rejected");
        assertThrows(NullPointerException.class,
                () -> lineage.latestContinuation("x", null),
                "a null listings list is rejected");
    }

    private static SessionListing root(String id) {
        return new SessionListing(id, Instant.parse("2026-06-17T09:00:00Z"), null, null);
    }

    private static SessionListing derived(String id, String parent) {
        return new SessionListing(id, Instant.parse("2026-06-17T10:00:00Z"), parent, EdgeType.DERIVED_FROM);
    }

    private static SessionListing derivedAt(String id, String parent, String at) {
        return new SessionListing(id, Instant.parse(at), parent, EdgeType.DERIVED_FROM);
    }

    private static SessionListing spawned(String id, String parent) {
        return new SessionListing(id, Instant.parse("2026-06-17T10:00:00Z"), parent, EdgeType.SPAWNED_BY);
    }
}
