package com.srk.codingagent.tool.memory;

import com.srk.codingagent.persistence.PermissionDecisionOutcome;

/**
 * The seam through which the propose-and-approve flow collects the developer's approve/deny
 * answer on a proposed durable learning (component C12, US-21, AC-21.1/AC-21.2). The
 * {@link LearningProposer} presents the proposed learning (via
 * {@link LearningProposal#presentation()}) and this approver returns the developer's
 * decision; only an {@link PermissionDecisionOutcome#APPROVE} leads to a write.
 *
 * <p>This mirrors the shape of {@link com.srk.codingagent.permission.Approver} (the gate's
 * developer approve/deny boundary) but takes a {@link LearningProposal} rather than a
 * {@link com.srk.codingagent.permission.GateRequest}: a proposed learning is not a tool call
 * (it has no {@code toolUseId}, no command, no file path), so a parallel learning-shaped
 * seam reads cleaner than forcing a learning through {@code GateRequest}. The real
 * implementation renders the proposal to the REPL and reads the developer's answer (T-1.1
 * owns the prompt UI); tests inject a stub that returns a fixed decision so the
 * propose-and-approve logic is exercised deterministically.
 */
@FunctionalInterface
public interface LearningApprover {

    /** An approver that approves every proposal — a test/wiring convenience, never the default. */
    LearningApprover APPROVE_ALL = proposal -> PermissionDecisionOutcome.APPROVE;

    /** An approver that denies every proposal — the safe default when no developer is present. */
    LearningApprover DENY_ALL = proposal -> PermissionDecisionOutcome.DENY;

    /**
     * Presents a proposed learning for the developer's approval and returns the decision
     * (AC-21.1).
     *
     * @param proposal the learning being proposed; never {@code null}.
     * @return {@link PermissionDecisionOutcome#APPROVE} to persist the learning, or
     *         {@link PermissionDecisionOutcome#DENY} to discard it (AC-21.2); never
     *         {@code null}.
     */
    PermissionDecisionOutcome approve(LearningProposal proposal);
}
