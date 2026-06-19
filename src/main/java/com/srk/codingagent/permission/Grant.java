package com.srk.codingagent.permission;

import com.srk.codingagent.persistence.PermissionDecisionPayload;
import java.util.Objects;

/**
 * A remembered approval under {@code ASK_ONCE_THEN_REMEMBER} (03-data-model § 2.7,
 * ADR-0004 grant lifecycle). Once the developer approves a Class-X operation, the gate
 * records a {@code Grant} keyed by the operation's RD-1 {@link MatchKey normalized match
 * key}, scoped to the {@code sessionLineage}; a later operation whose key matches an active
 * grant auto-approves without re-prompting (AC-9.5, AC-10.3).
 *
 * <p><b>Scope (INV-10, RD-5, AC-10.6).</b> The {@code sessionLineage} is the session and
 * its compaction-derived continuations. A grant is <em>not</em> read by a separate root
 * session and <em>not</em> inherited by a sub-agent — the {@link GrantStore} enforces both
 * by keying on the lineage and starting a child store empty.
 *
 * <p><b>Never for a denylisted command (INV-9, AC-10.4).</b> A destructive command never
 * produces a grant; the gate records nothing on a denylisted prompt, so no grant can ever
 * carry a destructive match key.
 *
 * @param matchKey       the RD-1 normalized match key
 *                       ({@code run_command:<exe>[ <subcmd>]} | {@code write:<subtree>} |
 *                       {@code <tool>}); non-blank. Mirrors
 *                       {@link PermissionDecisionPayload#matchedGrant()}.
 * @param sessionLineage the session lineage the grant is scoped to; non-blank.
 */
public record Grant(String matchKey, String sessionLineage) {

    /**
     * Validates the grant.
     *
     * @throws NullPointerException     if {@code matchKey} or {@code sessionLineage} is
     *                                  {@code null}.
     * @throws IllegalArgumentException if {@code matchKey} or {@code sessionLineage} is
     *                                  blank.
     */
    public Grant {
        requireNonBlank(matchKey, "matchKey");
        requireNonBlank(sessionLineage, "sessionLineage");
    }

    private static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
