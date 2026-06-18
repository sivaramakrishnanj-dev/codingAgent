package com.srk.codingagent.persistence;

/**
 * Signals that an event's {@link EventType} is a valid taxonomy kind whose typed
 * {@link EventPayload} is not yet modelled (the kinds outside the T-0.4 contract
 * fixture — model-request digest, sub-agent edges, compaction, memory-write, error).
 *
 * <p>Decoding such a kind is reported rather than silently mis-parsed, so a later
 * task that emits one of these kinds learns immediately that the reader needs
 * extending instead of reading a half-populated payload. A subtype of
 * {@link PersistenceException} so it is catchable alongside other persistence faults.
 */
public final class UnsupportedPayloadException extends PersistenceException {

    private static final long serialVersionUID = 1L;

    private final transient EventType eventType;

    /**
     * Creates an exception naming the unsupported event type.
     *
     * @param eventType the taxonomy kind whose typed payload is not yet modelled;
     *                  must not be {@code null}.
     */
    public UnsupportedPayloadException(EventType eventType) {
        super("event type " + eventType + " has no typed payload modelled yet (T-0.4 scope)");
        this.eventType = eventType;
    }

    /**
     * Returns the event type that could not be decoded to a typed payload.
     *
     * @return the unsupported event type; never {@code null}.
     */
    public EventType eventType() {
        return eventType;
    }
}
