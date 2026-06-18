package com.srk.codingagent.persistence;

/**
 * The lifecycle status of a session, recorded in its {@code .meta.json} summary
 * (ADR-0005: the meta file summarizes status, tokens, parent edge, outcome).
 *
 * <p>The {@code .meta.json} is a convenience cache derived from the JSONL log; on
 * any disagreement the JSONL log wins. T-0.4 models the statuses needed to summarize
 * a session; the lifecycle transitions (when a session becomes compacted, etc.)
 * belong to the tasks that drive them.
 */
public enum SessionStatus {

    /** The session is open and still being appended to. */
    ACTIVE,

    /** The session ended normally. */
    COMPLETED,

    /** The session was compacted into a successor (the original is retained). */
    COMPACTED
}
