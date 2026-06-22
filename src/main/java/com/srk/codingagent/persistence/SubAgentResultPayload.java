package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a {@code SUBAGENT_RESULT} event: the marker the orchestrator appends to the
 * <em>parent</em>'s log when a spawned child completes or fails (ADR-0010, AC-17.5/AC-17.6;
 * {@code 03-data-model.md} § 5, INV-11). It records only the child's <em>summary</em> and a
 * success flag — never the child's transcript or event stream (INV-11): summary-only
 * propagation is the load-bearing isolation property, so this payload deliberately carries
 * the single summary block the parent incorporates and nothing more.
 *
 * <p>The event schema ({@code 06-formal/event.schema.json}) enumerates {@code SUBAGENT_RESULT}
 * in its {@code type} set but pins its {@code payload} only as a generic object (it has no
 * dedicated {@code $defs.subagentResult}), so this shape is the code-level realization of the
 * documented spawn-result fact. A successful child carries its final summary text and
 * {@code success == true}; a failed or over-budget child (AC-17.6) carries a failure summary
 * and {@code success == false} so the parent can decide a next step rather than hanging.
 *
 * @param childSessionId the child's session id this result is for; non-blank.
 * @param success        whether the child completed normally ({@code true}) or
 *                       failed/exceeded its budget ({@code false}, AC-17.6).
 * @param summary        the summary block the parent incorporates (AC-17.4) — the child's
 *                       final answer on success, or a failure description on failure; non-blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubAgentResultPayload(
        String childSessionId,
        boolean success,
        String summary) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws IllegalArgumentException if {@code childSessionId} or {@code summary} is blank.
     */
    public SubAgentResultPayload {
        Payloads.requireNonBlank(childSessionId, "childSessionId");
        Payloads.requireNonBlank(summary, "summary");
    }

    @Override
    public EventType type() {
        return EventType.SUBAGENT_RESULT;
    }
}
