package com.srk.codingagent.persistence;

/**
 * The kind of an interaction {@link Event} recorded in a session's append-only
 * JSONL log (ADR-0005, {@code 03-data-model.md} § 3). Every event the agent
 * appends — user input, model traffic, tool activity, sub-agent edges, compaction
 * markers, memory writes, outcomes, errors — is one of these kinds (AC-13.1).
 *
 * <p>The constant set and their wire names are the validation vocabulary pinned by
 * the formal event schema ({@code 06-formal/event.schema.json}): an event whose
 * {@code type} is outside this enum is rejected (CT-SCH-2). The names are emitted
 * verbatim as the JSONL {@code type} field.
 *
 * <p>T-0.4 needs only a subset of these to carry a strongly-typed
 * {@link EventPayload} (the kinds present in the contract fixture). The remaining
 * kinds are declared so the taxonomy is complete and the enum can validate the full
 * vocabulary; their typed payloads are added by the tasks that emit them.
 */
public enum EventType {

    /** Session bootstrap marker: mode, repo key, model id, permission mode. */
    SESSION_START,

    /** A user turn's content blocks. */
    USER_MESSAGE,

    /** A request sent to the model (digest of messages/system/toolConfig). */
    MODEL_REQUEST,

    /** A model turn: stop reason plus the response content blocks. */
    MODEL_RESPONSE,

    /** Token accounting for a model turn (input/output, optional cache tokens). */
    MODEL_USAGE,

    /** A tool invocation the agent decided to make. */
    TOOL_USE,

    /** The authorization decision recorded for a tool invocation. */
    PERMISSION_DECISION,

    /** The result of a tool invocation. */
    TOOL_RESULT,

    /** A sub-agent was spawned (child session edge). */
    SUBAGENT_SPAWN,

    /** A spawned sub-agent returned its summary. */
    SUBAGENT_RESULT,

    /** A compaction occurred, linking a source session to its summarized successor. */
    COMPACTION,

    /** A memory entry was written (tier, slug, provenance). */
    MEMORY_WRITE,

    /** A task outcome signal (taskRef, success, iterations). */
    OUTCOME,

    /** An error was recorded (category, message, optional exit code). */
    ERROR
}
