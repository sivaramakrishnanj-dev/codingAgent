package com.srk.codingagent.memory;

/**
 * Signals a failure of the on-disk memory store (component C16, ADR-0007): a malformed
 * entry file, a slug that escapes the tier directory, or an I/O failure reading or writing
 * a tier's markdown / index. Carries a diagnostic message (and a cause when wrapping an
 * underlying fault) so the failure is surfaced rather than swallowed.
 */
public class MemoryStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a diagnostic message.
     *
     * @param message the diagnostic message.
     */
    public MemoryStoreException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a diagnostic message and an underlying cause.
     *
     * @param message the diagnostic message.
     * @param cause   the underlying failure; may be {@code null}.
     */
    public MemoryStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
