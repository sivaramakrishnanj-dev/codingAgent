package com.srk.codingagent.model.converse;

/**
 * Signals that a Bedrock Converse call failed unrecoverably at the model backend.
 *
 * <p>This is the typed carrier the Model Client (C4) surfaces when the underlying
 * {@code BedrockRuntimeClient.converse(...)} throws — an SDK service exception
 * (validation, throttling, access-denied, internal-server, model-error, etc.) or a
 * client/transport exception. The CLI maps it to
 * {@link com.srk.codingagent.cli.ExitCode#MODEL_BACKEND} (exit {@code 4}): a
 * model-backend problem, deliberately distinct from a configuration fault
 * ({@link com.srk.codingagent.config.ConfigException} &rarr; exit {@code 2}) and from a
 * caller-side protocol fault ({@link ToolProtocolException}).
 *
 * <p>The originating SDK exception is always chained as the cause so the failure is
 * fully debuggable (the SDK exception carries the request id and service-side detail);
 * the {@link #getMessage() message} states that the Converse call failed without
 * re-deriving that detail.
 */
public final class ModelBackendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a model-backend exception chaining the underlying SDK failure.
     *
     * @param message a human-readable description that the Converse call failed; must
     *                not be {@code null}.
     * @param cause   the underlying SDK exception thrown by the Converse call; must not
     *                be {@code null} so the failure is debuggable.
     */
    public ModelBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
