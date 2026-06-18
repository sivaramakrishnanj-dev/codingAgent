package com.srk.codingagent.config;

/**
 * Signals that configuration could not be resolved into a valid
 * {@link ResolvedConfig}: a malformed value, an unknown key, or a missing
 * required field.
 *
 * <p>Per ADR-0009 and AC-8.5 / AC-6.4, every such failure must name the offending
 * key (or missing field). The {@link #key()} accessor carries that name as
 * structured data so callers can surface it without parsing the message string;
 * the human-readable {@link #getMessage() message} also embeds it for the
 * stderr line the CLI prints (contract guard G2). A configuration failure is
 * mapped by the CLI to exit code {@code 2} (USAGE_CONFIG) and is detected before
 * any model call or tool execution (fail-fast).
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String key;

    /**
     * Creates a configuration exception naming the offending key.
     *
     * @param key     the configuration key (or required-field name) at fault;
     *                must not be {@code null}.
     * @param message a human-readable description of the problem; must already
     *                name the key so the CLI's stderr line satisfies G2.
     */
    public ConfigException(String key, String message) {
        super(message);
        this.key = key;
    }

    /**
     * Creates a configuration exception naming the offending key and chaining the
     * underlying cause (for example a number-parse failure).
     *
     * @param key     the configuration key at fault; must not be {@code null}.
     * @param message a human-readable description of the problem, naming the key.
     * @param cause   the underlying cause to chain.
     */
    public ConfigException(String key, String message, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    /**
     * Returns the configuration key (or required-field name) at fault.
     *
     * @return the offending key; never {@code null}.
     */
    public String key() {
        return key;
    }
}
