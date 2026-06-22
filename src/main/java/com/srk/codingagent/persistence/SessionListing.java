package com.srk.codingagent.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * A lightweight listing entry for one session under a repository (AC-7.1 / AC-15.2):
 * the session's id, when its event log was last written, and any lineage edge recorded
 * on its {@code .meta.json} summary. Cheap to build — listing a directory of sessions
 * does not require reading every JSONL log in full (it stats the log file and reads the
 * small derived meta), so the {@code resume}/{@code sessions} lists are fast even with
 * many sessions.
 *
 * <p>The {@code lastModified} time is the session log's filesystem modification time;
 * it is the most-recent-activity signal the lister orders by (most-recent-first, AC-7.1).
 * The lineage fields ({@code parentSessionId}/{@code edgeType}) come from the session's
 * meta summary when present, so a continuation can be recognized for the
 * latest-continuation-default lineage walk (AC-7.4); they are {@code null} for a root
 * session or a session that has no meta summary yet.
 *
 * @param sessionId       the session id; non-blank.
 * @param lastModified    the session log's last-modified time (most-recent-activity
 *                        signal); must not be {@code null}.
 * @param parentSessionId the parent session id from the meta summary, or {@code null}
 *                        for a root / meta-less session.
 * @param edgeType        the lineage edge to the parent, or {@code null} when there is
 *                        no parent (INV-3: non-null iff {@code parentSessionId} is
 *                        non-null).
 */
public record SessionListing(
        String sessionId,
        Instant lastModified,
        String parentSessionId,
        EdgeType edgeType) {

    /**
     * Validates the listing and enforces INV-3 (an edge type is present exactly when a
     * parent is).
     *
     * @throws NullPointerException     if {@code sessionId} or {@code lastModified} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code sessionId} is blank, or the lineage edge
     *                                  violates INV-3.
     */
    public SessionListing {
        Payloads.requireNonBlank(sessionId, "sessionId");
        Objects.requireNonNull(lastModified, "lastModified");
        if ((parentSessionId == null) != (edgeType == null)) {
            throw new IllegalArgumentException(
                    "INV-3: edgeType must be non-null iff parentSessionId is non-null "
                            + "(parentSessionId=" + parentSessionId + ", edgeType=" + edgeType + ")");
        }
    }

    /**
     * Whether this session is a compaction-derived continuation of another session
     * (its lineage edge to its parent is {@link EdgeType#DERIVED_FROM}).
     *
     * @return {@code true} iff this session has a {@code DERIVED_FROM} parent edge.
     */
    public boolean isDerivedContinuation() {
        return edgeType == EdgeType.DERIVED_FROM;
    }
}
