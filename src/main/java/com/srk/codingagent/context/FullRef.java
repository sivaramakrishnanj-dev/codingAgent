package com.srk.codingagent.context;

import java.util.Objects;
import java.util.Optional;

/**
 * The format of a {@code fullRef} pointer (AC-19.2/19.3): a session-relative reference from a
 * context-reduced tool result back to the full output persisted in the session event log. The
 * format is pinned in this one place so {@link OutputDisposer} (which writes it) and
 * {@link OutputRetrieval} (which resolves it) cannot drift.
 *
 * <p><b>Shape: {@code "evt:<seq>"}.</b> A reduced output points back to the TOOL_RESULT event
 * that carries its full copy, identified by that event's monotonic per-session {@code seq}
 * (INV-1). The session and repository are the resolution context the retriever already holds
 * (a {@code fullRef} is only ever resolved within the session whose log produced it), so the
 * pointer need only carry the {@code seq}. The {@code event.schema.json} {@code toolResult}
 * def constrains {@code fullRef} to a string with no format, so this shape honours the schema.
 *
 * <p>Non-instantiable: a holder for the format constant and its parse/format helpers.
 */
final class FullRef {

    /** The scheme prefix every {@code fullRef} carries. */
    static final String SCHEME = "evt:";

    private FullRef() {
        // Non-instantiable.
    }

    /**
     * Formats a {@code fullRef} pointing at the event log sequence number that holds the full
     * output.
     *
     * @param seq the TOOL_RESULT event's assigned {@code seq}; must be {@code >= 0}.
     * @return the pointer {@code "evt:<seq>"}.
     * @throws IllegalArgumentException if {@code seq < 0}.
     */
    static String forSeq(int seq) {
        if (seq < 0) {
            throw new IllegalArgumentException("seq must be >= 0 (was " + seq + ")");
        }
        return SCHEME + seq;
    }

    /**
     * Parses the event-log sequence number out of a {@code fullRef}, if it is a well-formed
     * {@code "evt:<seq>"} pointer.
     *
     * @param fullRef the pointer to parse; must not be {@code null}.
     * @return the parsed {@code seq}, or {@link Optional#empty()} when {@code fullRef} is not a
     *         well-formed {@code evt:} pointer (so a malformed reference is surfaced as a
     *         not-found, not a crash).
     * @throws NullPointerException if {@code fullRef} is {@code null}.
     */
    static Optional<Integer> seqOf(String fullRef) {
        Objects.requireNonNull(fullRef, "fullRef");
        if (!fullRef.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String digits = fullRef.substring(SCHEME.length());
        try {
            int seq = Integer.parseInt(digits);
            return seq < 0 ? Optional.empty() : Optional.of(seq);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
