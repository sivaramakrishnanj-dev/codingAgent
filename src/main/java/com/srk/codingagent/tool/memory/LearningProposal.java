package com.srk.codingagent.tool.memory;

import com.srk.codingagent.memory.MemoryTier;
import java.util.Objects;

/**
 * One durable learning the agent proposes to the developer for approval (component C12,
 * US-21, ADR-0007): a mistake + fix it discovered in the loop, or a project convention it
 * noticed, or a learning harvested from a compaction summary (AC-18.5). The proposal is the
 * <em>candidate</em> for a curated memory entry — it carries the same identifying +
 * provenance shape a {@link com.srk.codingagent.memory.MemoryEntry} needs, minus the fields
 * the {@link LearningProposer} captures at the approval boundary ({@code created},
 * {@code originSession}, {@code status}).
 *
 * <p><b>Propose, never persist (INV-13, AC-21.4).</b> Constructing a proposal does
 * <em>not</em> write anything — it is an in-memory candidate. Persistence happens only when
 * the developer approves it, in {@link LearningProposer#propose(LearningProposal)}; a
 * rejected proposal is discarded and nothing is written (AC-21.2). This separation is what
 * keeps the no-auto-extract invariant clean: the harvest produces proposals, the developer
 * approves, the proposer writes.
 *
 * @param slug the kebab-case filename id the entry would get (must match the
 *             {@link com.srk.codingagent.memory.MemoryEntry} slug pattern when it is
 *             eventually built); non-blank.
 * @param tier the tier the learning would be classified into ({@code GLOBAL} /
 *             {@code PROJECT}, AC-12.3); must not be {@code null}.
 * @param why  the one-line provenance for the learning (AC-12.2 — why it is worth
 *             remembering); non-blank.
 * @param body the learning prose that would become the entry's markdown body (AC-14.1);
 *             non-blank (a proposal with no content is not a learning worth approving).
 */
public record LearningProposal(String slug, MemoryTier tier, String why, String body) {

    /**
     * Validates the proposal's required fields.
     *
     * @throws NullPointerException     if {@code tier} is {@code null}.
     * @throws IllegalArgumentException if {@code slug}, {@code why}, or {@code body} is blank.
     */
    public LearningProposal {
        requireNonBlank(slug, "slug");
        Objects.requireNonNull(tier, "tier");
        requireNonBlank(why, "why");
        requireNonBlank(body, "body");
    }

    /**
     * A one-line presentation of the proposed learning for the approval prompt (AC-21.1):
     * the tier, the slug, and the provenance the developer needs to decide.
     *
     * @return the presentation line; never {@code null}.
     */
    public String presentation() {
        return "remember (" + tier + ") '" + slug + "': " + why;
    }

    private static void requireNonBlank(String value, String field) {
        if (Objects.requireNonNull(value, field).isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
    }
}
