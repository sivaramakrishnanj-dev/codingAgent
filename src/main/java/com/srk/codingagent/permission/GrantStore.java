package com.srk.codingagent.permission;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The in-memory store of remembered {@link Grant}s for one session lineage (ADR-0004 grant
 * lifecycle, INV-10, RD-5). It backs {@code ASK_ONCE_THEN_REMEMBER}: the gate records a
 * grant on the first approval of a Class-X match key and consults the store on later
 * operations so a matching one auto-approves (AC-9.5, AC-10.3).
 *
 * <p><b>Lineage scoping (INV-10).</b> Each store is bound to one {@code sessionLineage}
 * string. A grant for a different lineage is never recorded or matched, so a grant cannot
 * be read by a separate root session.
 *
 * <p><b>Sub-agents start fresh (AC-10.6, RD-5).</b> A sub-agent runs the configured mode
 * independently and does not inherit the parent's grants; {@link #forSubAgent(String)}
 * mints a brand-new, empty store for the child lineage rather than copying the parent's
 * grants.
 *
 * <p><b>No grant lives across sessions or for a denylisted command.</b> The store is
 * purely in-memory (RD-5: not persisted across separate sessions; the session's own
 * persistence is the loop's concern, T-0.8), and the gate never records a grant for a
 * denylisted command (INV-9, AC-10.4) — that rule lives in the gate, but the store also
 * refuses to record a write-grant or command-grant it was not asked to.
 *
 * <p>This class is not thread-safe; one session's gate consults it on a single loop thread.
 */
public final class GrantStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(GrantStore.class);

    private final String sessionLineage;
    private final Set<Grant> grants = new LinkedHashSet<>();

    private GrantStore(String sessionLineage) {
        this.sessionLineage = sessionLineage;
    }

    /**
     * Creates an empty store scoped to a root session lineage.
     *
     * @param sessionLineage the session lineage this store remembers grants for; non-blank.
     * @return a new empty store.
     * @throws NullPointerException     if {@code sessionLineage} is {@code null}.
     * @throws IllegalArgumentException if {@code sessionLineage} is blank.
     */
    public static GrantStore forSession(String sessionLineage) {
        if (Objects.requireNonNull(sessionLineage, "sessionLineage").isBlank()) {
            throw new IllegalArgumentException("sessionLineage must be non-blank");
        }
        return new GrantStore(sessionLineage);
    }

    /**
     * Creates a brand-new, empty store for a sub-agent lineage. The child does NOT inherit
     * this parent store's grants (AC-10.6, RD-5) — a sub-agent runs the configured mode
     * fresh.
     *
     * @param childLineage the sub-agent's session lineage; non-blank.
     * @return a new empty store for the child; it shares no grants with this store.
     * @throws NullPointerException     if {@code childLineage} is {@code null}.
     * @throws IllegalArgumentException if {@code childLineage} is blank.
     */
    public GrantStore forSubAgent(String childLineage) {
        return forSession(childLineage);
    }

    /**
     * The session lineage this store is scoped to.
     *
     * @return the lineage; non-blank.
     */
    public String sessionLineage() {
        return sessionLineage;
    }

    /**
     * Records a remembered approval for {@code matchKey} in this lineage. Idempotent: the
     * same key recorded twice is held once. Callers must not record a denylisted command's
     * key (INV-9); the gate enforces that before calling this.
     *
     * @param matchKey the RD-1 normalized match key to remember; non-blank.
     * @return the recorded (or already-present) grant.
     * @throws NullPointerException     if {@code matchKey} is {@code null}.
     * @throws IllegalArgumentException if {@code matchKey} is blank.
     */
    public Grant remember(String matchKey) {
        Grant grant = new Grant(matchKey, sessionLineage);
        if (grants.add(grant)) {
            LOGGER.debug("Recorded grant {} for lineage {}", matchKey, sessionLineage);
        }
        return grant;
    }

    /**
     * Finds an exact-key grant in this lineage (used for command and coarse tool keys,
     * which match by string equality after RD-1 normalization).
     *
     * @param matchKey the candidate key; non-blank.
     * @return the matching grant, or {@code null} when none is remembered.
     * @throws NullPointerException     if {@code matchKey} is {@code null}.
     * @throws IllegalArgumentException if {@code matchKey} is blank.
     */
    public Grant findExact(String matchKey) {
        Grant candidate = new Grant(matchKey, sessionLineage);
        return grants.contains(candidate) ? candidate : null;
    }

    /**
     * Finds a write-grant in this lineage whose subtree covers {@code resolvedFile} (the
     * file lies in the granted directory or any descendant — ADR-0004 subtree rule).
     *
     * @param resolvedFile the workspace-confined target of a later write; must not be
     *                     {@code null}.
     * @return the covering write-grant, or {@code null} when none covers the file.
     * @throws NullPointerException if {@code resolvedFile} is {@code null}.
     */
    public Grant findWriteCovering(Path resolvedFile) {
        Objects.requireNonNull(resolvedFile, "resolvedFile");
        for (Grant grant : grants) {
            if (MatchKey.writeGrantCovers(grant.matchKey(), resolvedFile)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * The number of grants remembered in this lineage.
     *
     * @return the grant count; {@code 0} for a fresh store.
     */
    public int size() {
        return grants.size();
    }
}
