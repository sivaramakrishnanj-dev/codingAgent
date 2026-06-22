package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompactionTrigger}. Oracle: event.schema.json
 * {@code $defs.compaction.triggerReason} — the enum's wire values are
 * {@code threshold}/{@code manual}/{@code context_window_exceeded}; a value outside
 * the set is rejected.
 */
class CompactionTriggerTest {

    @Test
    @DisplayName("each trigger maps to its lowercase wire token (schema triggerReason enum)")
    void wireValues() {
        // Oracle: event.schema.json $defs.compaction.triggerReason — the three pinned tokens.
        assertEquals("threshold", CompactionTrigger.THRESHOLD.wireValue());
        assertEquals("manual", CompactionTrigger.MANUAL.wireValue());
        assertEquals("context_window_exceeded", CompactionTrigger.CONTEXT_WINDOW_EXCEEDED.wireValue());
    }

    @Test
    @DisplayName("fromWire parses each pinned token back to its constant (round-trip)")
    void fromWireRoundTrips() {
        // Oracle: the wire value round-trips — fromWire(wireValue()) is the identity.
        for (CompactionTrigger trigger : CompactionTrigger.values()) {
            assertEquals(trigger, CompactionTrigger.fromWire(trigger.wireValue()),
                    "fromWire must round-trip " + trigger);
        }
    }

    @Test
    @DisplayName("fromWire rejects a token outside the schema enum")
    void fromWireRejectsUnknown() {
        // Oracle: event.schema.json — the triggerReason enum is closed; a token outside it has
        // no mapping and must be rejected.
        assertThrows(IllegalArgumentException.class, () -> CompactionTrigger.fromWire("auto"),
                "an unknown triggerReason token must be rejected");
    }
}
