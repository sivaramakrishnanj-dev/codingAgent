package com.srk.codingagent.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The {@code <session-id>.meta.json} summary of a session (ADR-0005): a fast,
 * convenience cache of a session's status, token totals, parent-edge lineage, and
 * outcome, so a reader gets a summary without scanning the whole JSONL log.
 *
 * <p>The meta file is <em>derived</em> from the JSONL event log, which remains the
 * single source of truth; on any disagreement the JSONL wins. Optional fields are
 * omitted from the JSON when {@code null}.
 *
 * @param sessionId         the session this summarizes; non-blank. Captured at the
 *                          boundary (sortable, timestamp-prefixed), never generated
 *                          in-process.
 * @param repoKey           the repository key the session is scoped to; non-blank.
 * @param status            the session lifecycle status.
 * @param eventCount        the number of events in the log; {@code >= 0}.
 * @param inputTokens       total input tokens summed across the log's usage events;
 *                          {@code >= 0}.
 * @param outputTokens      total output tokens summed across the log's usage events;
 *                          {@code >= 0}.
 * @param parentSessionId   the parent session id, or {@code null} for a root session.
 * @param edgeType          the lineage edge to the parent, or {@code null} for a root.
 * @param outcomeSuccess    the last recorded outcome's success flag, or {@code null}
 *                          if the session recorded no outcome.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionMeta(
        String sessionId,
        String repoKey,
        SessionStatus status,
        int eventCount,
        int inputTokens,
        int outputTokens,
        String parentSessionId,
        EdgeType edgeType,
        Boolean outcomeSuccess) {

    /**
     * Validates the summary.
     *
     * @throws NullPointerException     if {@code status} is {@code null}.
     * @throws IllegalArgumentException if {@code sessionId}/{@code repoKey} is blank
     *                                  or a count is negative.
     */
    public SessionMeta {
        Payloads.requireNonBlank(sessionId, "sessionId");
        Payloads.requireNonBlank(repoKey, "repoKey");
        Objects.requireNonNull(status, "status");
        Payloads.requireAtLeast(eventCount, 0, "eventCount");
        Payloads.requireAtLeast(inputTokens, 0, "inputTokens");
        Payloads.requireAtLeast(outputTokens, 0, "outputTokens");
    }
}
