package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a {@code SUBAGENT_SPAWN} event: the lineage marker the orchestrator appends
 * to the <em>parent</em>'s log when it spawns a child sub-agent (ADR-0010, AC-17.5;
 * {@code 03-data-model.md} § 5, INV-11). Recording the spawn as an event keeps the child's
 * existence and its {@code SPAWNED_BY} lineage edge visible in the parent's append-only
 * audit trail without ever projecting the child's own transcript into it (INV-11): the
 * parent log carries only this spawn marker and a later {@code SUBAGENT_RESULT}; the child's
 * full turn-by-turn events live in the child's own session JSONL.
 *
 * <p>The event schema ({@code 06-formal/event.schema.json}) enumerates {@code SUBAGENT_SPAWN}
 * in its {@code type} set but pins its {@code payload} only as a generic object (it has no
 * dedicated {@code $defs.subagentSpawn}), so this shape is the code-level realization of the
 * documented child-session-edge fact: the {@code childSessionId} the spawn created, the
 * {@code edgeType} linking it to the parent (always {@link EdgeType#SPAWNED_BY} for a
 * sub-agent, INV-11), and the scoped prompt the child was given.
 *
 * @param childSessionId the spawned child's session id (boundary-captured, ADR-0005);
 *                       non-blank.
 * @param edgeType       the lineage edge to the parent; must not be {@code null} (always
 *                       {@link EdgeType#SPAWNED_BY} for a sub-agent, AC-17.5/INV-11).
 * @param modelId        the model id the child runs (the parent's unless overridden,
 *                       AC-17.2); non-blank.
 * @param prompt         the scoped prompt the child was spawned with (AC-17.1); non-blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentSpawnPayload(
        String childSessionId,
        EdgeType edgeType,
        String modelId,
        String prompt) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if {@code edgeType} is {@code null}.
     * @throws IllegalArgumentException if {@code childSessionId}, {@code modelId}, or
     *                                  {@code prompt} is blank.
     */
    public SubAgentSpawnPayload {
        Payloads.requireNonBlank(childSessionId, "childSessionId");
        java.util.Objects.requireNonNull(edgeType, "edgeType");
        Payloads.requireNonBlank(modelId, "modelId");
        Payloads.requireNonBlank(prompt, "prompt");
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_SPAWN;
    }
}
