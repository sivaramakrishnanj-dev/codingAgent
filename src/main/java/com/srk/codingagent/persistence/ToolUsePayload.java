package com.srk.codingagent.persistence;

import java.util.Map;
import java.util.Objects;

/**
 * The body of a {@code TOOL_USE} event: the tool invocation the agent decided to
 * make ({@code 06-formal/event.schema.json}, {@code $defs.toolUse}). All three
 * fields are required by the schema; {@code toolUseId} and {@code name} are
 * non-blank ({@code minLength 1}) and {@code input} is an object.
 *
 * @param toolUseId the correlating tool-use id; non-blank.
 * @param name      the tool name; non-blank.
 * @param input     the tool input object; must not be {@code null}. Defensively
 *                  copied.
 */
public record ToolUsePayload(String toolUseId, String name, Map<String, Object> input)
        implements EventPayload {

    /**
     * Validates the payload and defensively copies {@code input}.
     *
     * @throws NullPointerException     if any field is {@code null}.
     * @throws IllegalArgumentException if {@code toolUseId} or {@code name} is
     *                                  blank.
     */
    public ToolUsePayload {
        Payloads.requireNonBlank(toolUseId, "toolUseId");
        Payloads.requireNonBlank(name, "name");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
    }

    @Override
    public EventType type() {
        return EventType.TOOL_USE;
    }
}
