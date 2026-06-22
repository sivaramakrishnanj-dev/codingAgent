package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why a compaction was triggered, carried by a {@code COMPACTION} event's
 * {@link CompactionPayload#triggerReason()} ({@code 06-formal/event.schema.json},
 * {@code $defs.compaction.triggerReason}). The three triggers are the three entry
 * points ADR-0006 / state-machine B LT2 names:
 *
 * <ul>
 *   <li>{@link #THRESHOLD} — auto-compaction at {@code 0.85 x window} (AC-18.1,
 *       NFR-CONTEXT-COMPACT-THRESHOLD; the T-2.1 {@code TokenBudgetGuard} returned
 *       {@code COMPACT}).</li>
 *   <li>{@link #MANUAL} — the developer issued the {@code /compact} command at any
 *       utilization (AC-18.2).</li>
 *   <li>{@link #CONTEXT_WINDOW_EXCEEDED} — the {@code model_context_window_exceeded}
 *       backstop (ADR-0006).</li>
 * </ul>
 *
 * <p>The {@link #wireValue()} is the lowercase/underscore token the schema's enum
 * pins; it is emitted as the JSON {@code triggerReason} and parsed back from it, so the
 * persisted compaction marker validates against the formal schema.
 */
public enum CompactionTrigger {

    /** Auto-compaction at the 0.85-of-window threshold (AC-18.1). */
    THRESHOLD("threshold"),

    /** The manual {@code /compact} command, at any utilization (AC-18.2). */
    MANUAL("manual"),

    /** The {@code model_context_window_exceeded} backstop (ADR-0006). */
    CONTEXT_WINDOW_EXCEEDED("context_window_exceeded");

    private final String wireValue;

    CompactionTrigger(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The lowercase/underscore token the schema's {@code triggerReason} enum pins, and
     * the value serialized to / parsed from the JSON.
     *
     * @return the wire value; never {@code null}.
     */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses a wire {@code triggerReason} token into its enum constant.
     *
     * @param wireValue the token (e.g. {@code "threshold"}); must match one of the
     *                  schema's values.
     * @return the matching trigger.
     * @throws IllegalArgumentException if {@code wireValue} is not a known trigger.
     */
    @JsonCreator
    public static CompactionTrigger fromWire(String wireValue) {
        for (CompactionTrigger trigger : values()) {
            if (trigger.wireValue.equals(wireValue)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("unknown compaction triggerReason: '" + wireValue + "'");
    }
}
