package com.srk.codingagent.tool;

/**
 * Thrown by a {@link ToolHandler} when a tool invocation fails in a way the model should
 * see and react to — invalid input, a missing file, a refused write. The
 * {@link ToolRegistry} catches it and returns an {@code error}-status
 * {@link com.srk.codingagent.persistence.ContentBlock.ToolResult} carrying the message,
 * so the model can correct its next move rather than the agent crashing (04-apis § 3
 * Notes: tool errors return as {@code toolResult {status: error}}).
 */
public class ToolInvocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a model-facing message.
     *
     * @param message the diagnostic message surfaced to the model in the error tool
     *                result.
     */
    public ToolInvocationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a model-facing message and an underlying cause.
     *
     * @param message the diagnostic message surfaced to the model.
     * @param cause   the underlying failure; may be {@code null}.
     */
    public ToolInvocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
