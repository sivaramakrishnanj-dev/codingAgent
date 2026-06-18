package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The body of a {@code TOOL_RESULT} event: the outcome of a tool invocation
 * ({@code 06-formal/event.schema.json}, {@code $defs.toolResult}). The schema
 * requires {@code toolUseId} and {@code status} and allows an optional
 * {@code result} (arbitrary JSON — e.g. the {@code CommandResult}-shaped object in
 * the contract fixture), a {@code truncated} flag (schema default {@code false}),
 * and a {@code fullRef} pointer to spilled output.
 *
 * <p>The optional fields are nullable and omitted from JSON when {@code null}, so a
 * minimal result (just id + status) serializes to exactly those two fields and a
 * full result round-trips its {@code result} object unchanged.
 *
 * @param toolUseId the correlating tool-use id; non-blank.
 * @param status    the ok/error/denied status.
 * @param result    the result content (any JSON value), or {@code null}.
 * @param truncated whether the inlined result was truncated, or {@code null} to omit
 *                  (the schema treats absent as {@code false}).
 * @param fullRef   a reference to the full (un-truncated) output, or {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResultPayload(
        String toolUseId,
        ToolResultStatus status,
        Object result,
        Boolean truncated,
        String fullRef) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if {@code status} is {@code null}.
     * @throws IllegalArgumentException if {@code toolUseId} is blank.
     */
    public ToolResultPayload {
        Payloads.requireNonBlank(toolUseId, "toolUseId");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Creates a tool-result payload carrying only an id, status, and result body
     * (no truncation/ref), the shape the contract fixture exercises.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @param status    the ok/error/denied status.
     * @param result    the result content (any JSON value), or {@code null}.
     * @return a tool-result payload with {@code null} {@code truncated}/{@code fullRef}.
     */
    public static ToolResultPayload of(String toolUseId, ToolResultStatus status, Object result) {
        return new ToolResultPayload(toolUseId, status, result, null, null);
    }

    @Override
    public EventType type() {
        return EventType.TOOL_RESULT;
    }
}
