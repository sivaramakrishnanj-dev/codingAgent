package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code SUBAGENT_SPAWN} / {@code SUBAGENT_RESULT} payloads and their
 * {@link EventCodec} round-trip (ADR-0010, AC-17.5; {@code 06-formal/event.schema.json}). The
 * SUT (a real {@link EventCodec} and the real payload records) is never mocked.
 *
 * <p><b>Oracles.</b>
 * <ul>
 *   <li>AC-13.1 / § 3 taxonomy: a {@code SUBAGENT_SPAWN}/{@code SUBAGENT_RESULT} event
 *       round-trips through encode then decode unchanged (the codec must now map these
 *       types to a typed payload rather than throwing {@code UnsupportedPayloadException}).</li>
 *   <li>AC-13.3: an encoded event is exactly one JSONL line.</li>
 *   <li>INV-11: a {@code SUBAGENT_RESULT} payload carries only a summary + success flag —
 *       no transcript field — so the parent log can never accrue the child's stream.</li>
 *   <li>The schema enumerates these types but pins their payload only as a generic object
 *       (no dedicated {@code $defs}), so a typed record that serializes to an object is
 *       schema-valid (the same arrangement T-2.2 used for {@code ERROR}).</li>
 * </ul>
 */
class SubAgentPayloadCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    @DisplayName("AC-13.1/AC-17.5: a SUBAGENT_SPAWN event round-trips through encode then decode")
    void roundTrip_subagentSpawn() {
        // Oracle: AC-13.1 / AC-17.5 — a spawn event is a logged interaction-event kind; it must
        // persist and read back unchanged, carrying the child session id, the SPAWNED_BY edge,
        // the child model, and the scoped prompt.
        Event original = new Event(7, "2026-06-22T09:00:00Z",
                new SubAgentSpawnPayload("child-1", EdgeType.SPAWNED_BY,
                        "anthropic.claude-haiku-4-8", "do the subtask"));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(EventType.SUBAGENT_SPAWN, decoded.type(), "the type round-trips");
        SubAgentSpawnPayload payload = assertInstanceOf(SubAgentSpawnPayload.class, decoded.payload(),
                "a SUBAGENT_SPAWN decodes to a typed SubAgentSpawnPayload (no longer unsupported)");
        assertEquals("child-1", payload.childSessionId());
        assertEquals(EdgeType.SPAWNED_BY, payload.edgeType(),
                "AC-17.5/INV-11: the spawn edge is SPAWNED_BY");
        assertEquals("anthropic.claude-haiku-4-8", payload.modelId());
        assertEquals("do the subtask", payload.prompt());
    }

    @Test
    @DisplayName("AC-13.1/AC-17.5: a SUBAGENT_RESULT event round-trips through encode then decode")
    void roundTrip_subagentResult() {
        // Oracle: AC-13.1 / AC-17.5 — a result event persists and reads back unchanged, carrying
        // the child session id, the success flag, and the summary.
        Event original = new Event(9, "2026-06-22T09:01:00Z",
                new SubAgentResultPayload("child-1", true, "subtask done: 3 files updated"));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(EventType.SUBAGENT_RESULT, decoded.type(), "the type round-trips");
        SubAgentResultPayload payload = assertInstanceOf(SubAgentResultPayload.class, decoded.payload(),
                "a SUBAGENT_RESULT decodes to a typed SubAgentResultPayload");
        assertEquals("child-1", payload.childSessionId());
        assertTrue(payload.success(), "the success flag round-trips");
        assertEquals("subtask done: 3 files updated", payload.summary());
    }

    @Test
    @DisplayName("AC-13.3: a SUBAGENT_SPAWN event encodes to a single JSONL line")
    void encode_subagentSpawn_isSingleLine() {
        // Oracle: AC-13.3 / NFR-LOG-FORMAT — one JSON object per line; no embedded newline.
        String line = codec.encode(new Event(1, "2026-06-22T09:00:00Z",
                new SubAgentSpawnPayload("child-1", EdgeType.SPAWNED_BY, "m", "p")));

        assertFalse(line.contains("\n"), "an encoded event must not contain a newline");
        assertTrue(line.contains("\"type\":\"SUBAGENT_SPAWN\""),
                "the emitted type is the payload's type (SUBAGENT_SPAWN)");
    }

    @Test
    @DisplayName("INV-11: a SUBAGENT_RESULT payload carries only a summary, never a transcript field")
    void subagentResult_carriesSummaryOnly() {
        // Oracle: INV-11 — "the parent context receives only its summary, never its event
        // stream." The result payload's serialized form must carry the summary but no transcript
        // / events / messages field, so the parent log can never accrue the child's stream.
        String line = codec.encode(new Event(2, "2026-06-22T09:01:00Z",
                new SubAgentResultPayload("child-1", false, "sub-agent exceeded its budget")));

        assertTrue(line.contains("\"summary\":\"sub-agent exceeded its budget\""),
                "INV-11: the result carries the summary block");
        assertFalse(line.contains("transcript"),
                "INV-11: the result carries NO transcript field");
        assertFalse(line.contains("messages"),
                "INV-11: the result carries NO messages/event-stream field");
        assertFalse(line.contains("events"),
                "INV-11: the result carries NO event-stream field");
    }

    @Test
    @DisplayName("SubAgentSpawnPayload rejects a blank child session id and a null edge type")
    void spawnPayload_validates() {
        // Oracle: the payload is self-validating (the writer never repairs a payload). A blank
        // id or null edge must be rejected at construction.
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentSpawnPayload(" ", EdgeType.SPAWNED_BY, "m", "p"),
                "a blank childSessionId is rejected");
        assertThrows(NullPointerException.class,
                () -> new SubAgentSpawnPayload("c", null, "m", "p"),
                "a null edgeType is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentSpawnPayload("c", EdgeType.SPAWNED_BY, "m", " "),
                "a blank prompt is rejected");
    }

    @Test
    @DisplayName("SubAgentResultPayload rejects a blank child session id and a blank summary")
    void resultPayload_validates() {
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentResultPayload(" ", true, "ok"),
                "a blank childSessionId is rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new SubAgentResultPayload("c", true, " "),
                "a blank summary is rejected");
    }
}
