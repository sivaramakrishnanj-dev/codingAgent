package com.srk.codingagent.cli;

import com.srk.codingagent.permission.Approver;
import com.srk.codingagent.permission.GateRequest;
import com.srk.codingagent.persistence.PermissionDecisionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Approver} used on the one-shot ({@code -p}) path, where there is no terminal
 * to present an inline approval prompt.
 *
 * <p>The interactive approval prompt (presenting the exact operation and reading the
 * developer's answer, AC-10.1) is the REPL's job (T-1.1). A one-shot run is
 * non-interactive: when the permission gate must prompt for a gated operation it cannot
 * self-answer, that operation is one the run cannot proceed past. Per AC-10.2 and the
 * exit-code contract ({@code 06-formal/cli-exit-codes.md}: {@code 3} user-aborted —
 * "denial of a gated op the task cannot proceed without"), this approver treats a
 * required prompt as a blocking denial and aborts the run by throwing
 * {@link UserAbortedException}, which the CLI maps to {@link ExitCode#USER_ABORTED}
 * ({@code 3}).
 *
 * <p>This approver is only consulted by the gate when a prompt is actually required —
 * Class-R reads, mode-driven auto-approvals (for example {@code UNRESTRICTED}), and
 * grant-matched auto-approvals never reach an {@link Approver} (see
 * {@link com.srk.codingagent.permission.PermissionGate}), so a one-shot run whose tools
 * never need a prompt completes normally (exit {@code 0}). The abort fires only for the
 * operations the gate genuinely cannot decide without asking.
 */
public final class NonInteractiveApprover implements Approver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NonInteractiveApprover.class);

    /**
     * Aborts the one-shot run for any operation the gate must prompt for.
     *
     * @param request the gated operation the gate would otherwise present; never
     *                 {@code null}.
     * @return never returns normally.
     * @throws UserAbortedException always — a one-shot run cannot answer an interactive
     *                              prompt, so a required prompt is a blocking denial
     *                              (AC-10.2 → exit {@code 3}). The message names the
     *                              operation (G2).
     */
    @Override
    public PermissionDecisionOutcome confirm(GateRequest request) {
        LOGGER.warn("One-shot run cannot prompt for approval of {}; aborting (exit 3, AC-10.2)",
                request.presentation());
        throw new UserAbortedException(
                "one-shot run requires approval it cannot prompt for: " + request.presentation());
    }
}
