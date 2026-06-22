package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code MEMORY_WRITE} payload and its {@link EventCodec} round-trip
 * (T-2.4, ADR-0007, AC-12.4; {@code 06-formal/event.schema.json}). The SUT — a real
 * {@link EventCodec} and the real {@link MemoryWritePayload} record — is never mocked.
 *
 * <p>Oracles:
 * <ul>
 *   <li>AC-13.1 / § 3 taxonomy: a {@code MEMORY_WRITE} event round-trips through encode then
 *       decode unchanged (the codec must now map this type to a typed payload rather than
 *       throwing {@code UnsupportedPayloadException}).</li>
 *   <li>AC-13.3: an encoded event is exactly one JSONL line.</li>
 *   <li>AC-12.4: the marker records the write's provenance — slug, tier, originating session,
 *       why.</li>
 *   <li>The schema enumerates {@code MEMORY_WRITE} but pins its payload only as a generic
 *       object (no dedicated {@code $defs}), so a typed record that serializes to an object is
 *       schema-valid (the same arrangement T-2.2 used for {@code ERROR}).</li>
 * </ul>
 */
class MemoryWritePayloadCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    @DisplayName("AC-13.1 / AC-12.4: a MEMORY_WRITE event round-trips through encode then decode")
    void roundTrip() {
        // Oracle: AC-13.1 / AC-12.4 — a memory-write event is a logged interaction-event kind;
        // it must persist and read back unchanged, carrying slug, tier, originSession, why.
        Event original = new Event(5, "2026-06-22T10:00:00Z",
                new MemoryWritePayload("use-jitter", "GLOBAL", "2026-06-22T09-00-00-sess",
                        "approved durable learning"));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(EventType.MEMORY_WRITE, decoded.type(), "the type round-trips");
        MemoryWritePayload payload = assertInstanceOf(MemoryWritePayload.class, decoded.payload(),
                "a MEMORY_WRITE decodes to a typed MemoryWritePayload (no longer unsupported)");
        assertEquals("use-jitter", payload.slug());
        assertEquals("GLOBAL", payload.tier());
        assertEquals("2026-06-22T09-00-00-sess", payload.originSession());
        assertEquals("approved durable learning", payload.why());
    }

    @Test
    @DisplayName("AC-13.3: a MEMORY_WRITE event encodes to a single JSONL line")
    void encodesToSingleLine() {
        // Oracle: AC-13.3 / NFR-LOG-FORMAT — one JSON object per line; no embedded newline.
        String line = codec.encode(new Event(1, "2026-06-22T10:00:00Z",
                new MemoryWritePayload("s-slug", "PROJECT", "sess", "why")));

        assertFalse(line.contains("\n"), "an encoded event must not contain a newline");
        assertTrue(line.contains("\"type\":\"MEMORY_WRITE\""),
                "the emitted type is the payload's type (MEMORY_WRITE)");
    }

    @Test
    @DisplayName("the payload rejects a blank field")
    void validates() {
        // Oracle: the payload is self-validating (the writer never repairs a payload).
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryWritePayload(" ", "GLOBAL", "sess", "why"), "blank slug rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryWritePayload("s-slug", " ", "sess", "why"), "blank tier rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryWritePayload("s-slug", "GLOBAL", " ", "why"), "blank originSession rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryWritePayload("s-slug", "GLOBAL", "sess", " "), "blank why rejected");
    }
}
