package com.srk.codingagent.workflow;

import java.util.Objects;

/**
 * Formats the timestamped approval line recorded into a greenfield artifact when the developer
 * confirms a phase (component C3, ADR-0012 greenfield side; AC-1.5). Kept as a single tested unit so
 * the recorded-approval shape is pinned in one place and the {@link ArtifactApprovalGate} only
 * decides <em>when</em> to stamp, not <em>how</em> the line reads.
 *
 * <p>The line names that the artifact was approved and carries the approval timestamp verbatim from
 * the boundary clock (ADR-0005), so a reader (and a resumed session) can see the moment of approval
 * (AC-1.5 — "record the approval with a timestamp in the … artifact").
 */
final class ApprovalStamp {

    /** The marker that opens the approval line, so the recorded approval is findable in the artifact. */
    static final String MARKER = "Approved:";

    private ApprovalStamp() {
        // Static formatting utility; not instantiable.
    }

    /**
     * Builds the approval line for an artifact approved at the given timestamp.
     *
     * @param artifact  the artifact being approved; must not be {@code null}.
     * @param timestamp the approval timestamp from the boundary clock; non-blank.
     * @return the approval line (without a trailing newline), e.g.
     *         {@code "Approved: Requirements approved by the developer at 2026-06-23T09:00:00Z."}.
     * @throws NullPointerException     if {@code artifact} or {@code timestamp} is {@code null}.
     * @throws IllegalArgumentException if {@code timestamp} is blank.
     */
    static String line(GreenfieldArtifact artifact, String timestamp) {
        Objects.requireNonNull(artifact, "artifact");
        if (Objects.requireNonNull(timestamp, "timestamp").isBlank()) {
            throw new IllegalArgumentException("timestamp must be non-blank");
        }
        return MARKER + " " + artifact.heading() + " approved by the developer at " + timestamp + ".";
    }
}
