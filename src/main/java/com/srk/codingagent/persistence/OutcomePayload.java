package com.srk.codingagent.persistence;

/**
 * The body of an {@code OUTCOME} event: a task outcome signal
 * ({@code 06-formal/event.schema.json}, {@code $defs.outcome}). All three fields are
 * required by the schema; {@code iterations} is {@code >= 0}.
 *
 * @param taskRef    the task this outcome reports on; non-blank.
 * @param success    whether the task succeeded.
 * @param iterations how many iterations the task took; {@code >= 0}.
 */
public record OutcomePayload(String taskRef, boolean success, int iterations)
        implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws IllegalArgumentException if {@code taskRef} is blank or
     *                                  {@code iterations} is negative.
     */
    public OutcomePayload {
        Payloads.requireNonBlank(taskRef, "taskRef");
        Payloads.requireAtLeast(iterations, 0, "iterations");
    }

    @Override
    public EventType type() {
        return EventType.OUTCOME;
    }
}
