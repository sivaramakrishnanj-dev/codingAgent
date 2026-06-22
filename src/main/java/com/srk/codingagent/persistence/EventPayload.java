package com.srk.codingagent.persistence;

/**
 * The type-specific body of an {@link Event}. The JSONL line's {@code payload}
 * object is the serialized form of one of these records; its shape is determined by
 * the event's {@link EventType} (ADR-0005, {@code 03-data-model.md} § 2.2/§ 3).
 *
 * <p>This is a closed (sealed) hierarchy: each permitted record corresponds to one
 * payload shape in the formal event schema's {@code $defs}
 * ({@code 06-formal/event.schema.json}). T-0.4 modelled the payloads carried by the
 * event kinds present in the contract fixture {@code session-tool-use-cycle.jsonl};
 * T-2.2 adds the compaction and error payloads (the compaction-with-derivation flow
 * appends a {@code COMPACTION} marker on success and an {@code ERROR} on the
 * summary/derive-failure path). T-2.3 adds the sub-agent edge payloads (the orchestrator
 * appends a {@code SUBAGENT_SPAWN} marker and, on completion, a {@code SUBAGENT_RESULT}
 * carrying only the child's summary, INV-11). The remaining payload records (model-request
 * digest, memory-write) are added by the tasks that emit them.
 *
 * <p>Each payload is immutable and self-validating in its canonical constructor, so
 * an invalid payload cannot be appended (the writer never repairs a payload).
 */
public sealed interface EventPayload
        permits SessionStartPayload, UserMessagePayload, ModelResponsePayload,
                ModelUsagePayload, ToolUsePayload, PermissionDecisionPayload,
                ToolResultPayload, OutcomePayload, CompactionPayload, ErrorPayload,
                SubAgentSpawnPayload, SubAgentResultPayload {

    /**
     * The {@link EventType} this payload is the body of. An {@link Event} pairs a
     * payload with a matching type; this accessor lets the event derive its type
     * from the payload so the two cannot drift apart.
     *
     * @return the owning event type; never {@code null}.
     */
    EventType type();
}
