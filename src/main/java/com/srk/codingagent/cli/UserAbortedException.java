package com.srk.codingagent.cli;

/**
 * Signals that a required, gated action was denied by the user in a way that blocks
 * progress, so the run cannot proceed without that side effect.
 *
 * <p>Per the CLI exit-code contract ({@code 06-formal/cli-exit-codes.md}: {@code 3}
 * user-aborted) and the error/exit matrix ({@code 02-architecture.md} § 3.2 — "required
 * approval denied, blocking progress" is detected by the permission gate → exit {@code 3}),
 * this is the typed carrier of a blocking denial (AC-10.2). The CLI maps it to
 * {@link ExitCode#USER_ABORTED} ({@code 3}), deliberately distinct from a configuration
 * fault ({@link com.srk.codingagent.config.ConfigException} → exit {@code 2}) and from a
 * model-backend failure ({@code ModelBackendException} → exit {@code 4}).
 *
 * <p>In one-shot ({@code -p}) mode there is no terminal to present an inline approval
 * prompt (that is the interactive REPL, T-1.1): when the gate must prompt for a gated
 * operation the one-shot run cannot self-answer, the run is aborted with this exception.
 * AC-10.2 — the agent does not execute the denied operation; in one-shot the "next step"
 * the agent chooses is to stop, and exit {@code 3} reports that to the caller (G3: a
 * one-shot caller can branch on the code).
 *
 * <p>The {@link #getMessage() message} names the operation that could not proceed so the
 * CLI's stderr line satisfies guarantee G2 (every non-zero exit names its cause).
 */
public final class UserAbortedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a user-aborted exception whose message names the blocking operation.
     *
     * @param message a human-readable description naming the gated operation that was
     *                denied / could not proceed; must not be {@code null}.
     */
    public UserAbortedException(String message) {
        super(message);
    }
}
