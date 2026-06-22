package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of an {@code ERROR} event: a recorded failure
 * ({@code 06-formal/event.schema.json} {@code type} enum includes {@code ERROR};
 * {@link EventType#ERROR} Javadoc — "category, message, optional exit code"). The event
 * schema pins {@code ERROR}'s {@code payload} only as a generic object (it has no
 * dedicated {@code $defs.error}), so this shape is the code-level realization of the
 * documented {@code category} / {@code message} / optional {@code exitCode} triple.
 *
 * <p>T-2.2 appends an {@code ERROR} on the compaction failure path (state-machine B LT4):
 * when the summary/derive cannot recover context, the failure is recorded with a category,
 * a human-readable message, and the {@code exitCode} the failure maps to (5,
 * context-exhausted) so the audit trail carries the unrecoverable signal that drives
 * machine A T15 (LT7 → exit 5).
 *
 * @param category the failure category (e.g. {@code "compaction"}); non-blank.
 * @param message  a human-readable failure message; non-blank.
 * @param exitCode the process exit code this failure maps to, or {@code null} when the
 *                 error does not terminate the process. When present, {@code >= 0}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorPayload(String category, String message, Integer exitCode)
        implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws IllegalArgumentException if {@code category} or {@code message} is blank,
     *                                  or {@code exitCode} is present and negative.
     */
    public ErrorPayload {
        Payloads.requireNonBlank(category, "category");
        Payloads.requireNonBlank(message, "message");
        if (exitCode != null) {
            Payloads.requireAtLeast(exitCode, 0, "exitCode");
        }
    }

    /**
     * Creates an error payload carrying a category and message with no exit code.
     *
     * @param category the failure category; non-blank.
     * @param message  the failure message; non-blank.
     * @return an error payload with a {@code null} exit code.
     */
    public static ErrorPayload of(String category, String message) {
        return new ErrorPayload(category, message, null);
    }

    @Override
    public EventType type() {
        return EventType.ERROR;
    }
}
