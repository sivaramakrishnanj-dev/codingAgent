package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionMeta} validation (ADR-0005: the meta summarizes
 * status, tokens, parent edge, outcome). The oracle for each constraint is the meta's
 * role as a derived summary — required identity fields are non-blank, counts are
 * non-negative.
 */
class SessionMetaTest {

    @Test
    @DisplayName("a well-formed meta retains its fields, including the optional lineage edge")
    void wellFormed_retainsFields() {
        // Oracle: ADR-0005 — the meta carries status, tokens, parent edge, outcome.
        SessionMeta meta = new SessionMeta("sid", "repo", SessionStatus.COMPACTED,
                5, 100, 50, "parent-sid", EdgeType.SPAWNED_BY, Boolean.FALSE);

        assertEquals("sid", meta.sessionId());
        assertEquals(SessionStatus.COMPACTED, meta.status());
        assertEquals("parent-sid", meta.parentSessionId());
        assertEquals(EdgeType.SPAWNED_BY, meta.edgeType());
        assertEquals(Boolean.FALSE, meta.outcomeSuccess());
    }

    @Test
    @DisplayName("a blank sessionId is rejected (boundary-supplied id is required, ADR-0005)")
    void blankSessionId_rejected() {
        // Oracle: ADR-0005 — the session id is supplied at the boundary and required.
        assertThrows(IllegalArgumentException.class, () -> new SessionMeta(
                " ", "repo", SessionStatus.ACTIVE, 0, 0, 0, null, null, null));
    }

    @Test
    @DisplayName("a blank repoKey is rejected (sessions are keyed by repo, C15)")
    void blankRepoKey_rejected() {
        // Oracle: C15 — sessions are persisted keyed by repository; the key is required.
        assertThrows(IllegalArgumentException.class, () -> new SessionMeta(
                "sid", "", SessionStatus.ACTIVE, 0, 0, 0, null, null, null));
    }

    @Test
    @DisplayName("negative counts are rejected (counts summarize a non-negative log)")
    void negativeCounts_rejected() {
        // Oracle: ADR-0005 / AC-13.2 — event count and token totals are non-negative
        // aggregates of the log.
        assertThrows(IllegalArgumentException.class, () -> new SessionMeta(
                "sid", "repo", SessionStatus.ACTIVE, -1, 0, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionMeta(
                "sid", "repo", SessionStatus.ACTIVE, 0, -1, 0, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionMeta(
                "sid", "repo", SessionStatus.ACTIVE, 0, 0, -1, null, null, null));
    }

    @Test
    @DisplayName("a null status is rejected (a session always has a status)")
    void nullStatus_rejected() {
        // Oracle: ADR-0005 — the meta summarizes status; status is required.
        assertThrows(NullPointerException.class, () -> new SessionMeta(
                "sid", "repo", null, 0, 0, 0, null, null, null));
    }
}
