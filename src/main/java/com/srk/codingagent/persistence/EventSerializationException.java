package com.srk.codingagent.persistence;

/**
 * Signals that an {@link Event} could not be serialized to its JSONL line, or that a
 * line could not be parsed back into a typed {@code Event} (malformed JSON, a missing
 * required envelope field, or a payload that does not match its type's shape).
 *
 * <p>A subtype of {@link PersistenceException} so a caller can treat any persistence
 * fault — serialization or I/O — uniformly when honouring AC-13.4 (surface, don't
 * silently continue). A corrupt or unreadable log surfaces as this exception so the
 * caller can report it and offer a new session (AC-7.5) rather than crash.
 */
public final class EventSerializationException extends PersistenceException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a serialization exception with a human-readable message.
     *
     * @param message a description of the serialization or parse failure.
     */
    public EventSerializationException(String message) {
        super(message);
    }

    /**
     * Creates a serialization exception chaining the underlying Jackson failure.
     *
     * @param message a description of the serialization or parse failure.
     * @param cause   the underlying cause to chain.
     */
    public EventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
