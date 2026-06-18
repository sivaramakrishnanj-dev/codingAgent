package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Event} envelope (03-data-model.md § 2.2). Oracles:
 * <ul>
 *   <li>§ 2.2 / schema: seq is {@code >= 0} (INV-1), ts is a required string, type is
 *       derived from the payload so envelope and body cannot drift.</li>
 *   <li>ADR-0005: {@code withSeq} re-stamps the sequence without mutating the event
 *       (append-only).</li>
 * </ul>
 */
class EventTest {

    private static final EventPayload PAYLOAD = new OutcomePayload("t", true, 0);

    @Test
    @DisplayName("type is derived from the payload (envelope cannot disagree with body, § 2.2)")
    void type_derivedFromPayload() {
        // Oracle: 03-data-model.md § 2.2 — an event's type is determined by its payload.
        Event event = new Event(0, "2026-06-17T09:00:00Z", new ModelUsagePayload(1, 1, null, null));

        assertEquals(EventType.MODEL_USAGE, event.type(), "type must equal the payload's type");
    }

    @Test
    @DisplayName("withSeq returns a re-stamped copy without mutating the original (append-only, INV-1)")
    void withSeq_returnsCopy() {
        // Oracle: INV-1 / append-only — re-stamping seq must not mutate the event; it
        // returns a new value with the new seq and the same ts/payload.
        Event original = new Event(0, "2026-06-17T09:00:00Z", PAYLOAD);

        Event restamped = original.withSeq(5);

        assertEquals(5, restamped.seq(), "the copy must carry the new seq");
        assertEquals(0, original.seq(), "the original must be unchanged (immutable)");
        assertEquals(original.ts(), restamped.ts(), "ts must be carried over unchanged");
        assertEquals(original.payload(), restamped.payload(), "payload must be carried over unchanged");
        assertNotSame(original, restamped, "withSeq must return a distinct instance");
    }

    @Test
    @DisplayName("a negative seq is rejected (INV-1, schema minimum 0)")
    void negativeSeq_rejected() {
        // Oracle: INV-1 / schema — seq is monotonic and gap-free from 0; the schema pins
        // minimum 0, so a negative seq cannot construct an Event.
        assertThrows(IllegalArgumentException.class,
                () -> new Event(-1, "2026-06-17T09:00:00Z", PAYLOAD),
                "a negative seq must be rejected (INV-1)");
    }

    @Test
    @DisplayName("a blank ts is rejected (every event carries a timestamp, AC-13.2)")
    void blankTs_rejected() {
        // Oracle: AC-13.2 — each logged event carries a timestamp; a blank ts is not a
        // timestamp.
        assertThrows(IllegalArgumentException.class, () -> new Event(0, " ", PAYLOAD),
                "a blank ts must be rejected (AC-13.2)");
    }

    @Test
    @DisplayName("a null payload is rejected (every event has a body, § 2.2)")
    void nullPayload_rejected() {
        // Oracle: § 2.2 / schema required payload — an event must have a payload.
        assertThrows(NullPointerException.class, () -> new Event(0, "2026-06-17T09:00:00Z", null),
                "a null payload must be rejected");
    }
}
