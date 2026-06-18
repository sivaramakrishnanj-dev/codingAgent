package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The status of a tool result recorded on a {@code TOOL_RESULT} event
 * ({@code 06-formal/event.schema.json}, {@code $defs.toolResult}, {@code status}
 * enum). The schema pins the wire values as the lowercase strings {@code "ok"} /
 * {@code "error"} / {@code "denied"}; each constant serializes to its wire value via
 * {@link #wireValue()}.
 */
public enum ToolResultStatus {

    /** The tool ran and succeeded. */
    OK("ok"),

    /** The tool ran but failed. */
    ERROR("error"),

    /** The tool was not run because the permission check denied it. */
    DENIED("denied");

    private final String wireValue;

    ToolResultStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The lowercase wire value emitted in JSON ({@code "ok"} / {@code "error"} /
     * {@code "denied"}), matching the schema's {@code status} enum.
     *
     * @return the wire value; never {@code null}.
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
