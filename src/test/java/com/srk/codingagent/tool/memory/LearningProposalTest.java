package com.srk.codingagent.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.memory.MemoryTier;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LearningProposal} (the propose candidate, US-21, AC-21.1) and the
 * {@link LearningApprover} convenience constants. A proposal is an in-memory candidate that
 * carries the identifying + provenance shape a curated entry needs and renders an approval-prompt
 * line; constructing one persists nothing (INV-13/AC-21.4 — that is the proposer's job after
 * approval). Oracles trace to AC-21.1 (the proposal is presented to the developer) and to the
 * required-field contract the data model pins for a memory entry (AC-12.2/AC-12.3).
 */
class LearningProposalTest {

    @Test
    @DisplayName("AC-21.1: presentation() renders the tier, slug, and provenance for the approval prompt")
    void presentationCarriesTierSlugAndWhy() {
        // Oracle: AC-21.1 — the agent "shall propose it to the developer for approval". The
        // presentation line is what the developer is shown to decide; it must carry the tier (so
        // the developer knows the scope, AC-12.3), the slug (the entry id), and the why (the
        // provenance that justifies remembering it, AC-12.2).
        LearningProposal proposal =
                new LearningProposal("avoid-rm-rf", MemoryTier.PROJECT, "destructive in CI", "never rm -rf /");

        String line = proposal.presentation();

        assertTrue(line.contains("PROJECT"), "AC-21.1/AC-12.3: the proposed tier is shown: " + line);
        assertTrue(line.contains("avoid-rm-rf"), "AC-21.1: the proposed slug is shown: " + line);
        assertTrue(line.contains("destructive in CI"), "AC-21.1/AC-12.2: the provenance (why) is shown: " + line);
    }

    @Test
    @DisplayName("AC-12.2/AC-12.3: a proposal requires a tier, slug, why, and body")
    void requiredFieldsAreValidated() {
        // Oracle: a proposal becomes a MemoryEntry with provenance on approval (AC-12.2) classified
        // into a tier (AC-12.3); the identifying + provenance fields must be present. A blank slug,
        // why, or body, or a null tier, is not a proposable learning.
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProposal(" ", MemoryTier.GLOBAL, "why", "body"), "blank slug rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProposal("slug", MemoryTier.GLOBAL, " ", "body"), "blank why rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new LearningProposal("slug", MemoryTier.GLOBAL, "why", " "), "blank body rejected");
        assertThrows(NullPointerException.class,
                () -> new LearningProposal("slug", null, "why", "body"), "null tier rejected");
        assertThrows(NullPointerException.class,
                () -> new LearningProposal(null, MemoryTier.GLOBAL, "why", "body"), "null slug rejected");
    }

    @Test
    @DisplayName("a proposal exposes its components verbatim (the candidate shape)")
    void componentsAreExposedVerbatim() {
        // Oracle: AC-12.2/AC-12.3 — the proposal carries exactly the candidate fields the proposer
        // copies into a MemoryEntry on approval; assert each component is the constructed value.
        LearningProposal proposal = new LearningProposal("a-slug", MemoryTier.GLOBAL, "the why", "the body");
        assertEquals("a-slug", proposal.slug());
        assertEquals(MemoryTier.GLOBAL, proposal.tier());
        assertEquals("the why", proposal.why());
        assertEquals("the body", proposal.body());
    }

    @Test
    @DisplayName("AC-21.1/AC-21.2: the approve-all / deny-all convenience approvers return their fixed decision")
    void approverConstantsReturnFixedDecision() {
        // Oracle: AC-21.1 (APPROVE leads to a write) / AC-21.2 (a non-approval persists nothing).
        // APPROVE_ALL is the test/wiring convenience; DENY_ALL is the safe default when no developer
        // is present. Both must return their named decision for any proposal.
        LearningProposal any = new LearningProposal("slug", MemoryTier.PROJECT, "w", "b");
        assertEquals(PermissionDecisionOutcome.APPROVE, LearningApprover.APPROVE_ALL.approve(any),
                "APPROVE_ALL approves every proposal");
        assertEquals(PermissionDecisionOutcome.DENY, LearningApprover.DENY_ALL.approve(any),
                "AC-21.2: DENY_ALL denies every proposal (the safe default)");
    }
}
