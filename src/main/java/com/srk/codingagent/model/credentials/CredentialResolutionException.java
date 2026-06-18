package com.srk.codingagent.model.credentials;

/**
 * Signals that no usable SigV4 credentials could be resolved for Bedrock by either
 * configured path — a named AWS profile or the AWS SDK v2 default credential
 * provider chain.
 *
 * <p>Per ADR-0011 and AC-8.9, when neither path yields usable SigV4 credentials the
 * agent exits {@code 4} (model-backend) with a message that <em>names the paths
 * attempted</em> (the profile name, when one was configured, and the default chain).
 * This exception is the typed carrier of that failure: the CLI maps it to
 * {@link com.srk.codingagent.cli.ExitCode#MODEL_BACKEND} (exit {@code 4}), which is
 * deliberately distinct from a configuration fault
 * ({@link com.srk.codingagent.config.ConfigException} &rarr; exit {@code 2}). A
 * missing-credentials condition is a model-backend problem, not a malformed-config
 * problem.
 *
 * <p>The {@link #getMessage() message} already enumerates the attempted paths so the
 * CLI's stderr line satisfies AC-8.9 without re-deriving them.
 */
public final class CredentialResolutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a credential-resolution exception whose message names the paths
     * attempted (AC-8.9).
     *
     * @param message a human-readable description that names the attempted paths
     *                (profile name, when configured, and the default chain); must
     *                not be {@code null}.
     */
    public CredentialResolutionException(String message) {
        super(message);
    }

    /**
     * Creates a credential-resolution exception naming the paths attempted and
     * chaining the underlying SDK failure that prevented resolution.
     *
     * @param message a human-readable description naming the attempted paths.
     * @param cause   the underlying cause to chain (for example the SDK exception
     *                thrown when the default chain found no credentials).
     */
    public CredentialResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
