package com.srk.codingagent.cli;

/**
 * Signals a bad CLI invocation: an unknown flag, a missing required flag value, or an
 * unexpected argument, detected at startup before any model or tool work.
 *
 * <p>Per the exit-code contract ({@code 06-formal/cli-exit-codes.md}: {@code 2}
 * usage/config — "bad invocation ... detected BEFORE doing work. Trigger: unknown flag")
 * and {@code 02-architecture.md} § 3.2 ("bad CLI args → exit 2"), the CLI maps this to
 * {@link ExitCode#USAGE_CONFIG} ({@code 2}). It is the argument-parsing sibling of
 * {@link com.srk.codingagent.config.ConfigException} (malformed config → also exit
 * {@code 2}); both are the usage/config faults the contract places ahead of any
 * model-backend code in the precedence order.
 *
 * <p>The {@link #offendingArgument()} carries the exact argument at fault so the CLI's
 * stderr line can name it (guarantee G2: every non-zero exit names its cause, including
 * the offending key/path).
 */
public final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String offendingArgument;

    /**
     * Creates a usage exception naming the offending argument.
     *
     * @param offendingArgument the exact CLI argument at fault (the unknown flag or the
     *                          flag missing its value); must not be {@code null}.
     * @param message           a human-readable description of the bad invocation, naming
     *                          the offending argument; must not be {@code null}.
     */
    public UsageException(String offendingArgument, String message) {
        super(message);
        this.offendingArgument = offendingArgument;
    }

    /**
     * The exact CLI argument at fault (the unknown flag or the flag missing its value).
     *
     * @return the offending argument; never {@code null}.
     */
    public String offendingArgument() {
        return offendingArgument;
    }
}
