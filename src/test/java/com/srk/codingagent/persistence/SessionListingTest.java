package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionListing} — the listing entry validation and the
 * continuation predicate.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>INV-3</b>: "edgeType is non-null iff parentSessionId is non-null." A listing
 *       with a parent but no edge (or an edge but no parent) is invalid.</li>
 *   <li><b>AC-7.4</b>: a {@code DERIVED_FROM} listing is a compaction continuation; a root
 *       or a {@code SPAWNED_BY} listing is not.</li>
 * </ul>
 */
class SessionListingTest {

    private static final Instant T = Instant.parse("2026-06-17T09:00:00Z");

    @Test
    @DisplayName("INV-3: a root listing (no parent, no edge) is valid and not a continuation")
    void root_valid_notContinuation() {
        SessionListing root = new SessionListing("root", T, null, null);

        assertFalse(root.isDerivedContinuation(), "a root is not a continuation");
        assertEquals(null, root.parentSessionId());
    }

    @Test
    @DisplayName("AC-7.4: a DERIVED_FROM listing is a continuation")
    void derived_isContinuation() {
        SessionListing derived = new SessionListing("child", T, "parent", EdgeType.DERIVED_FROM);

        assertTrue(derived.isDerivedContinuation(),
                "AC-7.4: a DERIVED_FROM listing is a compaction continuation");
    }

    @Test
    @DisplayName("INV-4/INV-11: a SPAWNED_BY listing is not a (compaction) continuation")
    void spawned_notContinuation() {
        SessionListing spawned = new SessionListing("sub", T, "parent", EdgeType.SPAWNED_BY);

        assertFalse(spawned.isDerivedContinuation(),
                "a SPAWNED_BY sub-agent edge is not a compaction continuation");
    }

    @Test
    @DisplayName("INV-3: a parent without an edge type is rejected")
    void parentWithoutEdge_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionListing("child", T, "parent", null),
                "INV-3: edgeType must be present when parentSessionId is");
    }

    @Test
    @DisplayName("INV-3: an edge type without a parent is rejected")
    void edgeWithoutParent_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionListing("child", T, null, EdgeType.DERIVED_FROM),
                "INV-3: parentSessionId must be present when edgeType is");
    }

    @Test
    @DisplayName("a blank session id and a null modification time are rejected")
    void invalidFields_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SessionListing("  ", T, null, null),
                "a blank session id is rejected");
        assertThrows(NullPointerException.class,
                () -> new SessionListing("id", null, null, null),
                "a null modification time is rejected");
    }
}
