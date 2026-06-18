package com.srk.codingagent.persistence;

/**
 * Signals that an event log or session-store operation could not be persisted to or
 * read from disk.
 *
 * <p>Per AC-13.4, when an event cannot be persisted the agent must <em>surface the
 * failure rather than continue as if it were logged</em>. This exception is the
 * typed carrier of that failure: the writer throws it from
 * {@link EventLog#append(Event)} when the append-and-flush cannot complete, so a
 * caller cannot mistake an un-persisted event for a logged one (INV-2,
 * log-before-act). {@link EventSerializationException} and
 * {@link UnsupportedPayloadException} extend it so every persistence fault is
 * catchable as one type.
 */
public sealed class PersistenceException extends RuntimeException
        permits EventSerializationException, UnsupportedPayloadException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a persistence exception with a human-readable message.
     *
     * @param message a description of what could not be persisted or read.
     */
    public PersistenceException(String message) {
        super(message);
    }

    /**
     * Creates a persistence exception chaining the underlying cause (typically an
     * {@link java.io.IOException} from the failed write or read).
     *
     * @param message a description of what could not be persisted or read.
     * @param cause   the underlying cause to chain.
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
