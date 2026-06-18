package com.srk.codingagent.persistence;

/**
 * The working mode a session runs in, fixed for the session's life
 * ({@code 03-data-model.md} § 2.1). Recorded in the {@code SESSION_START} event's
 * {@link SessionStartPayload}; the formal event schema pins the two values as the
 * {@code mode} enum ({@code 06-formal/event.schema.json}, {@code $defs.sessionStart}).
 *
 * <p>Introduced here because T-0.4 is the first task to serialize a
 * {@code SESSION_START} event and needs the value type. The mode's behavioural
 * meaning (how greenfield vs. brownfield steers the agent) belongs to later tasks;
 * T-0.4 only needs it as the persisted enum.
 */
public enum SessionMode {

    /** A new project being built from scratch. */
    GREENFIELD,

    /** An existing codebase being modified. */
    BROWNFIELD
}
