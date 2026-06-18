package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The outcome of a permission check on a tool invocation, recorded on a
 * {@code PERMISSION_DECISION} event ({@code 06-formal/event.schema.json},
 * {@code $defs.permissionDecision}, {@code decision} enum). The schema pins the wire
 * values as the lowercase strings {@code "approve"} / {@code "deny"}; each constant
 * carries its wire value and serializes to it via {@link #wireValue()}.
 */
public enum PermissionDecisionOutcome {

    /** The tool invocation was approved. */
    APPROVE("approve"),

    /** The tool invocation was denied. */
    DENY("deny");

    private final String wireValue;

    PermissionDecisionOutcome(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The lowercase wire value emitted in JSON ({@code "approve"} / {@code "deny"}),
     * matching the schema's {@code decision} enum.
     *
     * @return the wire value; never {@code null}.
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
