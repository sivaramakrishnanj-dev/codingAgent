package com.srk.codingagent.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the latest compaction-continuation in a session's lineage (AC-7.4): when a
 * session has {@code DERIVED_FROM} continuations, resume should default to the tip of the
 * derived chain, not the (now read-only) original.
 *
 * <p><b>The lineage edge (ADR-0005, INV-4).</b> Compaction creates a <em>new</em> session
 * with {@code edgeType = DERIVED_FROM} pointing at its parent and never mutates the
 * parent's events (INV-4/INV-5). The edge is recorded on the child's {@code .meta.json}
 * summary ({@link SessionMeta#parentSessionId()}/{@link SessionMeta#edgeType()}), which is
 * what {@link SessionStore#listSessions} surfaces on each {@link SessionListing}. This
 * resolver walks those edges; it does not itself create them (that is compaction's lane,
 * T-2.1/T-2.2 — until then no session carries a {@code DERIVED_FROM} edge and every
 * session resolves to itself).
 *
 * <p><b>The walk.</b> From the requested session, repeatedly follow a child whose
 * {@code DERIVED_FROM} parent is the current session, advancing to that child; when no
 * further continuation exists, the current session is the tip. When more than one session
 * derives from the same parent (a re-compaction fork), the most-recently-active child is
 * chosen — the {@code listings} are ordered most-recent-first (AC-7.1), so the first
 * matching child is the latest. A cycle (which INV-3's at-most-one-parent rule forbids,
 * but which a hand-corrupted meta could fabricate) is broken defensively by tracking
 * visited ids so the walk always terminates.
 *
 * <p>Stateless: holds no per-repo state; one instance can resolve any number of lineages.
 */
public final class SessionLineage {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionLineage.class);

    /**
     * Resolves the latest continuation to resume by default (AC-7.4): walks
     * {@code DERIVED_FROM} continuations from {@code sessionId} to the tip of the derived
     * chain.
     *
     * @param sessionId the session the developer asked to resume; non-blank.
     * @param listings  the repository's session listings, most-recent-first (as
     *                  {@link SessionStore#listSessions} returns them); must not be
     *                  {@code null}.
     * @return the latest continuation's session id — {@code sessionId} itself when it has
     *         no {@code DERIVED_FROM} continuation. Never {@code null}.
     * @throws NullPointerException     if {@code listings} (or an element) is {@code null}.
     * @throws IllegalArgumentException if {@code sessionId} is blank.
     */
    public String latestContinuation(String sessionId, List<SessionListing> listings) {
        Payloads.requireNonBlank(sessionId, "sessionId");
        Objects.requireNonNull(listings, "listings");
        String tip = sessionId;
        Set<String> visited = new HashSet<>();
        visited.add(tip);
        Optional<String> next = derivedChildOf(tip, listings);
        while (next.isPresent() && visited.add(next.get())) {
            tip = next.get();
            next = derivedChildOf(tip, listings);
        }
        if (!tip.equals(sessionId)) {
            LOGGER.debug("resolved latest continuation of {} to {}", sessionId, tip);
        }
        return tip;
    }

    /** The most-recent session whose {@code DERIVED_FROM} parent is {@code parentId}, if any. */
    private static Optional<String> derivedChildOf(String parentId, List<SessionListing> listings) {
        for (SessionListing listing : listings) {
            Objects.requireNonNull(listing, "listing");
            if (listing.isDerivedContinuation() && parentId.equals(listing.parentSessionId())) {
                return Optional.of(listing.sessionId());
            }
        }
        return Optional.empty();
    }
}
