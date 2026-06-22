package com.srk.codingagent.subagent;

import com.srk.codingagent.persistence.EdgeType;
import java.util.Objects;

/**
 * The summarized outcome of one sub-agent run, returned to the parent (ADR-0010, AC-17.4,
 * INV-11). This is the <em>only</em> thing that crosses from child to parent: the parent
 * incorporates this block into its context and never sees the child's transcript or event
 * stream (INV-11 — summary-only propagation is the load-bearing isolation property).
 *
 * <p>A {@link #success() successful} result carries the child loop's final answer as its
 * {@link #summary()}; a failed or over-budget run (AC-17.6) carries {@code success == false}
 * and a failure description so the parent can decide a next step rather than hanging. Either
 * way the parent receives one summary string — never the child's messages[].
 *
 * <p>The {@link #childSessionId()} and {@link #edgeType()} record the lineage the child was
 * spawned under ({@link EdgeType#SPAWNED_BY}, AC-17.5); the parent log's
 * {@code SUBAGENT_SPAWN}/{@code SUBAGENT_RESULT} events carry this lineage, while the child's
 * own JSONL holds its full transcript.
 *
 * @param childSessionId the child's session id (its own session, ADR-0005); non-blank.
 * @param edgeType       the lineage edge to the parent; always {@link EdgeType#SPAWNED_BY}
 *                       for a sub-agent (AC-17.5, INV-11); must not be {@code null}.
 * @param success        whether the child completed normally ({@code true}) or
 *                       failed/exceeded its budget ({@code false}, AC-17.6).
 * @param summary        the single summary block the parent incorporates (AC-17.4); non-blank.
 */
public record SubAgentResult(
        String childSessionId,
        EdgeType edgeType,
        boolean success,
        String summary) {

    /**
     * Validates the result.
     *
     * @throws NullPointerException     if {@code edgeType} is {@code null}.
     * @throws IllegalArgumentException if {@code childSessionId} or {@code summary} is blank.
     */
    public SubAgentResult {
        if (Objects.requireNonNull(childSessionId, "childSessionId").isBlank()) {
            throw new IllegalArgumentException("childSessionId must be non-blank");
        }
        Objects.requireNonNull(edgeType, "edgeType");
        if (Objects.requireNonNull(summary, "summary").isBlank()) {
            throw new IllegalArgumentException("summary must be non-blank");
        }
    }

    /**
     * Builds a successful result carrying the child's final answer as the summary.
     *
     * @param childSessionId the child's session id; non-blank.
     * @param summary        the child's final answer (the summary the parent incorporates);
     *                       non-blank.
     * @return a successful {@code SPAWNED_BY} result.
     */
    public static SubAgentResult completed(String childSessionId, String summary) {
        return new SubAgentResult(childSessionId, EdgeType.SPAWNED_BY, true, summary);
    }

    /**
     * Builds a failure result (AC-17.6): a child that failed, surfaced an edge condition, or
     * exceeded its wall-clock budget. The parent receives this and decides a next step rather
     * than hanging.
     *
     * @param childSessionId the child's session id; non-blank.
     * @param summary        a human-readable failure description; non-blank.
     * @return a failed {@code SPAWNED_BY} result.
     */
    public static SubAgentResult failed(String childSessionId, String summary) {
        return new SubAgentResult(childSessionId, EdgeType.SPAWNED_BY, false, summary);
    }
}
