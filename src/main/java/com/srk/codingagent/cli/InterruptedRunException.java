package com.srk.codingagent.cli;

/**
 * Signals that the run was interrupted by the user (SIGINT / Ctrl-C) and must terminate
 * with the interrupted exit status.
 *
 * <p>Per the CLI exit-code contract ({@code 06-formal/cli-exit-codes.md}: {@code 130}
 * interrupted, {@code 128 + SIGINT(2)}) and {@code 02-architecture.md} § 4 (Ctrl-C
 * interrupts the current step; the session remains resumable because the event log is
 * flushed per event — T-0.4; exit {@code 130}). The CLI maps this to
 * {@link ExitCode#INTERRUPTED} ({@code 130}), which by the precedence rule
 * (cli-exit-codes § 2: "{@code 130} SIGINT always wins") takes priority over every other
 * code.
 *
 * <p><b>M0 scope.</b> This is the clean, testable seam for the SIGINT → {@code 130}
 * mapping: the run path translates an observed interrupt into this exception so the
 * exit-code mapping and its precedence are asserted as logic, without depending on OS
 * signal delivery. The richer step-cancellation behaviour (cancelling an in-flight
 * Converse stream, killing an in-flight subprocess) belongs to the interactive REPL and
 * later tasks; at M0 the event log already flushes per event, so no special teardown is
 * load-bearing for resumability.
 *
 * <p>The originating {@link InterruptedException}, when present, is chained as the cause
 * so the interrupt origin is debuggable.
 */
public final class InterruptedRunException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an interrupted-run exception with a human-readable message.
     *
     * @param message a description that the run was interrupted; must not be {@code null}.
     */
    public InterruptedRunException(String message) {
        super(message);
    }

    /**
     * Creates an interrupted-run exception chaining the {@link InterruptedException} (or
     * other cause) that signalled the interrupt.
     *
     * @param message a description that the run was interrupted; must not be {@code null}.
     * @param cause   the interrupt cause to chain (for example a caught
     *                {@link InterruptedException}); may be {@code null}.
     */
    public InterruptedRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
