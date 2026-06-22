package com.srk.codingagent.context;

import com.srk.codingagent.persistence.CompactionTrigger;
import java.util.Objects;

/**
 * The inputs to one compaction-with-derivation (component C6, ADR-0006; state-machine B
 * LT2 &rarr; LT3): which session is being compacted, the boundary-captured id of the
 * derived successor to create, and why compaction fired.
 *
 * <p><b>Boundary-captured ids (ADR-0005).</b> Both session ids are supplied by the caller;
 * the {@link Compactor} never generates one with {@code UUID.randomUUID()}. The
 * {@code derivedSessionId} is captured at the boundary (the loop/REPL that decides to
 * compact) so a run is reproducible and the derived log path is deterministic, consistent
 * with the rest of the persistence layer (C14/C15).
 *
 * @param repoKey            the repository key both sessions are scoped to; non-blank.
 * @param originalSessionId  the session being compacted (preserved unchanged, INV-5);
 *                           non-blank.
 * @param derivedSessionId   the boundary-captured id of the derived successor to create
 *                           (must differ from {@code originalSessionId} — derivation never
 *                           mutates in place, INV-4); non-blank.
 * @param trigger            why compaction fired (threshold / manual /
 *                           context-window-exceeded); must not be {@code null}.
 */
public record CompactionRequest(
        String repoKey,
        String originalSessionId,
        String derivedSessionId,
        CompactionTrigger trigger) {

    /**
     * Validates the request.
     *
     * @throws NullPointerException     if {@code trigger} is {@code null}.
     * @throws IllegalArgumentException if any session id or {@code repoKey} is blank, or
     *                                  the derived id equals the original (INV-4 forbids
     *                                  in-place mutation).
     */
    public CompactionRequest {
        requireNonBlank(repoKey, "repoKey");
        requireNonBlank(originalSessionId, "originalSessionId");
        requireNonBlank(derivedSessionId, "derivedSessionId");
        Objects.requireNonNull(trigger, "trigger");
        if (originalSessionId.equals(derivedSessionId)) {
            throw new IllegalArgumentException(
                    "derivedSessionId must differ from originalSessionId (INV-4: compaction "
                            + "derives a new session, never mutates in place)");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
