package com.srk.codingagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.PermissionMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EventCodec} — the JSONL line serializer/parser
 * (ADR-0005, NFR-LOG-FORMAT). Oracles:
 * <ul>
 *   <li>AC-13.3 (JSONL line-oriented): an encoded event is exactly one line — it
 *       contains no internal newline.</li>
 *   <li>AC-13.1 / § 3 taxonomy: the eight payload kinds present in the contract
 *       fixture round-trip through encode then decode unchanged.</li>
 *   <li>AC-13.2: a {@code MODEL_USAGE} event carries token counts, and the optional
 *       cache fields are omitted when absent.</li>
 * </ul>
 * The SUT (a real {@link EventCodec}) is never mocked.
 */
class EventCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    @DisplayName("an encoded event is a single JSONL line with no internal newline (AC-13.3)")
    void encode_isSingleLine() {
        // Oracle: AC-13.3 / NFR-LOG-FORMAT — JSONL is "one JSON object per line"; the
        // serialized form of one event must not contain an embedded line break.
        Event event = new Event(0, "2026-06-17T09:00:00Z",
                new OutcomePayload("adhoc", true, 1));

        String line = codec.encode(event);

        assertFalse(line.contains("\n"), "an encoded event must not contain a newline (one object per line)");
    }

    @Test
    @DisplayName("the type field is derived from the payload (envelope cannot drift from body)")
    void encode_typeDerivedFromPayload() {
        // Oracle: 03-data-model.md § 2.2 — type is determined by the payload; the
        // envelope's type field is the payload's type.
        Event event = new Event(3, "2026-06-17T09:00:06Z", ModelUsagePayload.of(1820, 47));

        String line = codec.encode(event);

        assertTrue(line.contains("\"type\":\"MODEL_USAGE\""),
                "the emitted type must be the payload's type (MODEL_USAGE)");
    }

    @Test
    @DisplayName("MODEL_USAGE carries token counts and omits absent cache fields (AC-13.2)")
    void encode_modelUsageTokens() {
        // Oracle: AC-13.2 — a usage event carries token counts; the schema's optional
        // cacheRead/WriteInputTokens are omitted when not reported.
        String line = codec.encode(new Event(3, "2026-06-17T09:00:06Z", ModelUsagePayload.of(1820, 47)));

        assertTrue(line.contains("\"inputTokens\":1820"), "input token count must be present (AC-13.2)");
        assertTrue(line.contains("\"outputTokens\":47"), "output token count must be present (AC-13.2)");
        assertFalse(line.contains("cacheReadInputTokens"),
                "absent optional cache fields must be omitted (schema optional semantics)");
    }

    @Test
    @DisplayName("a SESSION_START event round-trips through encode then decode (AC-13.1)")
    void roundTrip_sessionStart() {
        // Oracle: AC-13.1 — SESSION_START is one of the logged interaction-event kinds;
        // it must persist and read back unchanged.
        Event original = new Event(0, "2026-06-17T09:00:00Z",
                new SessionStartPayload(SessionMode.BROWNFIELD, "github.com/example/widget",
                        "anthropic.claude-opus-4-8", PermissionMode.ASK_EVERY_TIME));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded, "a SESSION_START event must round-trip unchanged");
    }

    @Test
    @DisplayName("a MODEL_RESPONSE with text + toolUse content round-trips (AC-13.1, fixture seq 2)")
    void roundTrip_modelResponseWithToolUse() {
        // Oracle: AC-13.1 / fixture seq 2 — a model response carrying a text block and a
        // toolUse block (the fixture's shape) must round-trip.
        Event original = new Event(2, "2026-06-17T09:00:06Z",
                new ModelResponsePayload(StopReason.TOOL_USE, List.of(
                        ContentBlock.text("I'll run the test suite."),
                        ContentBlock.toolUse("tu_01", "run_command", Map.of("command", "mvn -q test")))));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded, "a MODEL_RESPONSE with mixed content blocks must round-trip");
    }

    @Test
    @DisplayName("a TOOL_RESULT with a structured result round-trips (AC-13.1, fixture seq 6)")
    void roundTrip_toolResultStructured() {
        // Oracle: AC-13.1 / fixture seq 6 — a tool result with a CommandResult-shaped
        // result object must round-trip; result is arbitrary JSON.
        Map<String, Object> commandResult = Map.of(
                "command", "mvn -q test", "exitCode", 0, "timedOut", false);
        Event original = new Event(6, "2026-06-17T09:01:14Z",
                ToolResultPayload.of("tu_01", ToolResultStatus.OK, commandResult));

        Event decoded = codec.decode(codec.encode(original));

        assertInstanceOf(ToolResultPayload.class, decoded.payload());
        ToolResultPayload payload = (ToolResultPayload) decoded.payload();
        assertEquals("tu_01", payload.toolUseId());
        assertEquals(ToolResultStatus.OK, payload.status());
        assertNull(payload.truncated(), "an absent truncated flag round-trips as null (schema optional)");
    }

    @Test
    @DisplayName("a PERMISSION_DECISION serializes its decision as the lowercase wire value (schema enum)")
    void encode_permissionDecisionLowercase() {
        // Oracle: event.schema.json $defs.permissionDecision — the decision enum's wire
        // values are lowercase "approve"/"deny".
        Event event = new Event(5, "2026-06-17T09:00:09Z",
                new PermissionDecisionPayload("tu_01", OperationClass.SIDE_EFFECTING,
                        PermissionMode.ASK_EVERY_TIME, PermissionDecisionOutcome.APPROVE, null));

        String line = codec.encode(event);

        assertTrue(line.contains("\"decision\":\"approve\""),
                "the decision must serialize to the lowercase wire value 'approve'");
        assertFalse(line.contains("matchedGrant"),
                "an absent matchedGrant must be omitted (schema optional)");
    }

    @Test
    @DisplayName("decoding a line with an unknown type surfaces an EventSerializationException (CT-SCH-2 analogue)")
    void decode_unknownType_throws() {
        // Oracle: CT-SCH-2 — the type vocabulary is closed; an unknown type is not a
        // valid event. The codec surfaces it rather than producing a half-event.
        String line = "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"BOGUS\",\"payload\":{}}";

        assertThrows(EventSerializationException.class, () -> codec.decode(line),
                "an unknown event type must surface as a serialization fault");
    }

    @Test
    @DisplayName("decoding a taxonomy kind with no typed payload yet surfaces UnsupportedPayloadException")
    void decode_unmodelledKind_throws() {
        // Oracle: § 3 taxonomy — MEMORY_WRITE is a valid kind, but no typed payload is
        // modelled for it yet (T-2.4's lane); decoding must report that rather than
        // mis-parse. (COMPACTION and ERROR are now modelled by T-2.2, so a still-unmodelled
        // kind is used here.)
        String line = "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"MEMORY_WRITE\","
                + "\"payload\":{\"tier\":\"GLOBAL\",\"slug\":\"x\"}}";

        UnsupportedPayloadException ex = assertThrows(UnsupportedPayloadException.class,
                () -> codec.decode(line),
                "an unmodelled taxonomy kind must surface as UnsupportedPayloadException");
        assertEquals(EventType.MEMORY_WRITE, ex.eventType());
    }

    @Test
    @DisplayName("a COMPACTION event round-trips through encode then decode (T-2.2, schema $defs.compaction)")
    void roundTrip_compaction() {
        // Oracle: event.schema.json $defs.compaction — required fromSessionId/toSessionId/
        // triggerReason, optional summaryRef. A COMPACTION lineage marker (state-machine B
        // LT3 side effect) must persist and read back unchanged.
        Event original = new Event(8, "2026-06-22T09:05:00Z", new CompactionPayload(
                "sess-original", "sess-derived", "evt:0", CompactionTrigger.THRESHOLD));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded, "a COMPACTION event must round-trip unchanged");
    }

    @Test
    @DisplayName("COMPACTION serializes triggerReason as the lowercase wire token (schema enum)")
    void encode_compactionTriggerReasonWireValue() {
        // Oracle: event.schema.json $defs.compaction.triggerReason — the enum's wire values are
        // lowercase/underscore ("threshold","manual","context_window_exceeded").
        String line = codec.encode(new Event(0, "2026-06-22T09:05:00Z", new CompactionPayload(
                "from", "to", null, CompactionTrigger.CONTEXT_WINDOW_EXCEEDED)));

        assertTrue(line.contains("\"triggerReason\":\"context_window_exceeded\""),
                "triggerReason must serialize to its lowercase wire token");
        assertFalse(line.contains("summaryRef"),
                "an absent summaryRef must be omitted (schema optional)");
    }

    @Test
    @DisplayName("an ERROR event round-trips through encode then decode (T-2.2, LT4 failure marker)")
    void roundTrip_error() {
        // Oracle: EventType.ERROR Javadoc / § 3 taxonomy — an ERROR carries category, message,
        // optional exit code. The LT4 compaction-failure marker must persist and read back
        // unchanged.
        Event original = new Event(9, "2026-06-22T09:06:00Z",
                new ErrorPayload("compaction", "summarizer returned no usable summary text", 5));

        Event decoded = codec.decode(codec.encode(original));

        assertEquals(original, decoded, "an ERROR event must round-trip unchanged");
    }

    @Test
    @DisplayName("decoding malformed JSON surfaces an EventSerializationException (AC-7.5: corrupt log surfaced)")
    void decode_malformedJson_throws() {
        // Oracle: AC-7.5 — a corrupt/unreadable log is reported, not silently consumed.
        assertThrows(EventSerializationException.class, () -> codec.decode("{\"seq\":0,"),
                "malformed JSON must surface as a serialization fault");
    }

    @Test
    @DisplayName("decoding a line missing the type field surfaces a fault (CT-SCH-2 family: type required)")
    void decode_missingType_throws() {
        // Oracle: event.schema.json — type is a required envelope field; a line without
        // it is structurally invalid and must surface rather than parse.
        String line = "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"payload\":{}}";

        assertThrows(EventSerializationException.class, () -> codec.decode(line),
                "a line missing 'type' must surface as a serialization fault");
    }

    @Test
    @DisplayName("decoding a line missing the payload object surfaces a fault (payload required)")
    void decode_missingPayload_throws() {
        // Oracle: event.schema.json — payload is a required object; a line without an
        // object payload is structurally invalid.
        String line = "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"OUTCOME\"}";

        assertThrows(EventSerializationException.class, () -> codec.decode(line),
                "a line missing the object 'payload' must surface as a serialization fault");
    }

    @Test
    @DisplayName("decoding a payload that violates its type's shape surfaces a fault (AC-7.5)")
    void decode_payloadShapeMismatch_throws() {
        // Oracle: AC-7.5 / event.schema.json $defs.outcome (additionalProperties:false)
        // and the typed field shape — an OUTCOME payload whose iterations is not an
        // integer cannot bind to the typed payload; the corruption surfaces rather than
        // producing a half-event.
        String line = "{\"seq\":0,\"ts\":\"2026-06-17T09:00:00Z\",\"type\":\"OUTCOME\","
                + "\"payload\":{\"taskRef\":\"t\",\"success\":true,\"iterations\":\"not-a-number\"}}";

        assertThrows(EventSerializationException.class, () -> codec.decode(line),
                "a payload that does not match its type's shape must surface as a fault");
    }
}
