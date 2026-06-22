package com.srk.codingagent.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Objects;

/**
 * Serializes an {@link Event} to a single JSONL line and parses a line back into a
 * typed {@code Event} (ADR-0005, NFR-LOG-FORMAT = JSONL: one JSON object per line).
 *
 * <p>This is the one place Jackson is configured for the event log, so the writer
 * ({@link EventLog}) and reader ({@link SessionStore}) share identical encoding. The
 * envelope's {@code type} discriminator lives a level above the {@code payload}, so
 * decoding reads {@code type} from the envelope and routes the {@code payload}
 * sub-tree to the matching {@link EventPayload} record. Only the kinds with a modelled
 * payload (the contract-fixture kinds from T-0.4, {@code COMPACTION}/{@code ERROR} from
 * T-2.2, the {@code SUBAGENT_SPAWN}/{@code SUBAGENT_RESULT} sub-agent edges from
 * T-2.3, and the {@code MEMORY_WRITE} provenance marker from T-2.4) decode to a typed
 * payload; an event of any other taxonomy kind ({@code MODEL_REQUEST}) is reported via
 * {@link UnsupportedPayloadException} rather than silently mis-parsed.
 *
 * <p>A single line never contains a literal newline: Jackson's default writer emits
 * no internal line breaks, so each encoded event is exactly one line of the log.
 */
public final class EventCodec {

    private final ObjectMapper mapper;

    /**
     * Creates a codec with a fresh, log-appropriate {@link ObjectMapper}.
     */
    public EventCodec() {
        this.mapper = JsonMapper.builder().build();
    }

    /**
     * Encodes an event as a single JSONL line (no trailing newline).
     *
     * @param event the event to encode; must not be {@code null}.
     * @return the event as one line of JSON.
     * @throws NullPointerException     if {@code event} is {@code null}.
     * @throws EventSerializationException if the event cannot be serialized.
     */
    public String encode(Event event) {
        Objects.requireNonNull(event, "event");
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("failed to serialize event seq=" + event.seq(), e);
        }
    }

    /**
     * Decodes a single JSONL line into a typed {@link Event}.
     *
     * @param line one line of a session log; must not be {@code null}.
     * @return the parsed event.
     * @throws NullPointerException         if {@code line} is {@code null}.
     * @throws EventSerializationException  if the line is not valid event JSON.
     * @throws UnsupportedPayloadException  if the event's {@code type} is a taxonomy
     *                                      kind T-0.4 does not yet decode to a typed
     *                                      payload.
     */
    public Event decode(String line) {
        Objects.requireNonNull(line, "line");
        JsonNode root = readTree(line);
        EventType type = readType(root);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw new EventSerializationException("event line missing object 'payload': " + line);
        }
        EventPayload payload = decodePayload(type, payloadNode);
        int seq = root.path("seq").asInt(-1);
        String ts = root.path("ts").asText(null);
        return new Event(seq, ts, payload);
    }

    private JsonNode readTree(String line) {
        try {
            return mapper.readTree(line);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException("event line is not valid JSON: " + line, e);
        }
    }

    private EventType readType(JsonNode root) {
        JsonNode typeNode = root.get("type");
        if (typeNode == null || !typeNode.isTextual()) {
            throw new EventSerializationException("event line missing string 'type': " + root);
        }
        try {
            return EventType.valueOf(typeNode.asText());
        } catch (IllegalArgumentException e) {
            throw new EventSerializationException("unknown event type '" + typeNode.asText() + "'", e);
        }
    }

    private EventPayload decodePayload(EventType type, JsonNode payloadNode) {
        Class<? extends EventPayload> target = payloadClassFor(type);
        try {
            return mapper.treeToValue(payloadNode, target);
        } catch (JsonProcessingException e) {
            throw new EventSerializationException(
                    "failed to deserialize " + type + " payload: " + payloadNode, e);
        }
    }

    private static Class<? extends EventPayload> payloadClassFor(EventType type) {
        return switch (type) {
            case SESSION_START -> SessionStartPayload.class;
            case USER_MESSAGE -> UserMessagePayload.class;
            case MODEL_RESPONSE -> ModelResponsePayload.class;
            case MODEL_USAGE -> ModelUsagePayload.class;
            case TOOL_USE -> ToolUsePayload.class;
            case PERMISSION_DECISION -> PermissionDecisionPayload.class;
            case TOOL_RESULT -> ToolResultPayload.class;
            case OUTCOME -> OutcomePayload.class;
            case COMPACTION -> CompactionPayload.class;
            case ERROR -> ErrorPayload.class;
            case SUBAGENT_SPAWN -> SubAgentSpawnPayload.class;
            case SUBAGENT_RESULT -> SubAgentResultPayload.class;
            case MEMORY_WRITE -> MemoryWritePayload.class;
            case MODEL_REQUEST -> throw new UnsupportedPayloadException(type);
        };
    }
}
