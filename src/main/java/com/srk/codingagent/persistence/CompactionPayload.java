package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The body of a {@code COMPACTION} event: the lineage marker a compaction-with-derivation
 * appends to the original session's log when it produces a derived successor
 * ({@code 06-formal/event.schema.json}, {@code $defs.compaction}; state-machine B LT3).
 * Recording the edge as an event keeps the derivation visible in the source session's
 * append-only audit trail without mutating any prior turn (INV-4): the original is never
 * edited, only appended to.
 *
 * <p>The schema requires {@code fromSessionId}, {@code toSessionId}, and
 * {@code triggerReason}; {@code summaryRef} is optional (a pointer to where the summary
 * is recorded). The {@code from}/{@code to}/{@code summaryRef} triple is the
 * {@code COMPACTION(from,to,summaryRef)} side effect state-machine B LT3 pins.
 *
 * @param fromSessionId the original (parent) session being compacted; non-blank.
 * @param toSessionId   the derived (child) session created to continue work; non-blank.
 * @param summaryRef    a pointer to the recorded summary (e.g. {@code evt:<seq>} in the
 *                      child seed), or {@code null} when none is recorded.
 * @param triggerReason why compaction fired (threshold / manual / context-window-exceeded);
 *                      must not be {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompactionPayload(
        String fromSessionId,
        String toSessionId,
        String summaryRef,
        CompactionTrigger triggerReason) implements EventPayload {

    /**
     * Validates the payload.
     *
     * @throws NullPointerException     if {@code triggerReason} is {@code null}.
     * @throws IllegalArgumentException if {@code fromSessionId} or {@code toSessionId} is
     *                                  blank.
     */
    public CompactionPayload {
        Payloads.requireNonBlank(fromSessionId, "fromSessionId");
        Payloads.requireNonBlank(toSessionId, "toSessionId");
        Objects.requireNonNull(triggerReason, "triggerReason");
    }

    @Override
    public EventType type() {
        return EventType.COMPACTION;
    }
}
