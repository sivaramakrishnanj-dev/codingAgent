package com.srk.codingagent.tool;

/**
 * Thrown when a command cannot be executed at the infrastructure level — the subprocess
 * fails to start, or the capturing thread is interrupted (ADR-0003). This is distinct
 * from a command that <em>runs</em> and exits non-zero: that outcome is a normal
 * {@link CommandResult} with a non-zero {@code exitCode} (the verification signal,
 * RD-10), not an exception. Only the failure to obtain a result at all is exceptional.
 */
public class CommandExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message and an underlying cause.
     *
     * @param message the diagnostic message.
     * @param cause   the underlying failure (e.g. {@link java.io.IOException},
     *                {@link InterruptedException}); may be {@code null}.
     */
    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
