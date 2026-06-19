package com.srk.codingagent.permission;

import com.srk.codingagent.persistence.PermissionDecisionOutcome;

/**
 * The seam through which the permission gate collects a developer's approve/deny answer
 * when an operation must be confirmed (AC-10.1, AC-10.2). The gate presents the exact
 * operation (via {@link GateRequest#presentation()}) and the approver returns the
 * developer's decision.
 *
 * <p>This is the boundary between the gate's decision logic (T-0.7, this task) and the
 * REPL/CLI prompt UI (T-1.1). A real implementation renders the operation to the terminal
 * and reads the developer's answer; tests inject a stub that returns a fixed decision so
 * the gate's logic is exercised without a live terminal.
 */
@FunctionalInterface
public interface Approver {

    /**
     * Presents an operation that requires confirmation and returns the developer's
     * decision.
     *
     * @param request the exact operation to present (AC-10.1); never {@code null}.
     * @return {@link PermissionDecisionOutcome#APPROVE} to run the operation, or
     *         {@link PermissionDecisionOutcome#DENY} to refuse it; never {@code null}.
     */
    PermissionDecisionOutcome confirm(GateRequest request);
}
