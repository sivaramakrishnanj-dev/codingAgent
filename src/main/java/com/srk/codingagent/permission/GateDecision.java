package com.srk.codingagent.permission;

import com.srk.codingagent.config.PermissionMode;
import com.srk.codingagent.persistence.OperationClass;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import com.srk.codingagent.persistence.PermissionDecisionPayload;
import java.util.Objects;

/**
 * The gate's decision for one tool call — the value the agent loop (T-0.8) consumes to
 * authorize (or refuse) a tool before dispatch (ADR-0004 gate placement; state machine A,
 * T7/T8). It carries everything the loop needs:
 * <ul>
 *   <li>the {@link #outcome()} (approve/deny) — the loop turns {@code APPROVE} into an
 *       {@code S3 → S4} transition (run the tool) and {@code DENY} into {@code S3 → S2}
 *       (a {@code TOOL_RESULT(denied)} the model reacts to — CT-SM-2);</li>
 *   <li>the {@link #operationClass()} and {@link #mode()} that the loop records on the
 *       {@code PERMISSION_DECISION} event;</li>
 *   <li>the {@link #matchedGrant()} (the RD-1 match key of a remembered grant that
 *       auto-approved this call), or {@code null} when none applied;</li>
 *   <li>whether the loop should {@link #shouldPrompt() prompt} — the gate has already
 *       consulted the injected {@link Approver}, so this is informational provenance the
 *       loop can log, not a second prompt.</li>
 * </ul>
 *
 * <p>The gate is the single authorization point: a Class-X tool reaches the loop's
 * execute step only through an {@code APPROVE} decision here (INV-8, CT-INV-7). A
 * denylisted command never carries a {@code matchedGrant} (INV-9, CT-INV-8).
 *
 * @param outcome        approve or deny; must not be {@code null}.
 * @param operationClass the tool's read/side-effecting class; must not be {@code null}.
 * @param mode           the permission mode in effect; must not be {@code null}.
 * @param matchedGrant   the RD-1 match key of the grant that auto-approved this call, or
 *                       {@code null} when none applied (auto-approved by mode, prompted, or
 *                       denied).
 * @param prompted       whether the gate consulted the {@link Approver} for this decision.
 * @param denylisted     whether the command matched the destructive denylist (always
 *                       prompted, never remembered — INV-9).
 */
public record GateDecision(
        PermissionDecisionOutcome outcome,
        OperationClass operationClass,
        PermissionMode mode,
        String matchedGrant,
        boolean prompted,
        boolean denylisted) {

    /**
     * Validates the decision and the INV-9 invariant that a denylisted command carries no
     * matched grant.
     *
     * @throws NullPointerException     if {@code outcome}, {@code operationClass}, or
     *                                  {@code mode} is {@code null}.
     * @throws IllegalArgumentException if {@code denylisted} is {@code true} while a
     *                                  {@code matchedGrant} is set (INV-9 violation).
     */
    public GateDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(operationClass, "operationClass");
        Objects.requireNonNull(mode, "mode");
        if (denylisted && matchedGrant != null) {
            throw new IllegalArgumentException(
                    "a denylisted command must never carry a matched grant (INV-9)");
        }
    }

    /**
     * Whether the operation is authorized to run (the loop transitions to execute).
     *
     * @return {@code true} when the outcome is approve.
     */
    public boolean approved() {
        return outcome == PermissionDecisionOutcome.APPROVE;
    }

    /**
     * Whether the gate prompted the developer for this decision.
     *
     * @return {@code true} when the {@link Approver} was consulted.
     */
    public boolean shouldPrompt() {
        return prompted;
    }

    /**
     * Builds the {@link PermissionDecisionPayload} the loop appends as the
     * {@code PERMISSION_DECISION} event (state machine A, T7/T8). The gate decides; the
     * loop records.
     *
     * @param toolUseId the correlating tool-use id; non-blank.
     * @return the payload mirroring this decision.
     */
    public PermissionDecisionPayload toPayload(String toolUseId) {
        return new PermissionDecisionPayload(toolUseId, operationClass, mode, outcome, matchedGrant);
    }
}
