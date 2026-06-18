package com.srk.codingagent.model.converse;

/**
 * Signals a violation of the toolUse&harr;toolResult protocol the agent owns
 * (ADR-0001 — "we own correctness of the toolUse/toolResult protocol"; INV-6;
 * CT-INV-5).
 *
 * <p>The single condition this carries today is INV-6: every {@code ToolResultBlock}'s
 * {@code toolUseId} must match a {@code toolUseId} produced by an earlier
 * {@code ToolUseBlock} in the same transcript. A {@code toolResult} whose
 * {@code toolUseId} has no prior {@code toolUse} is rejected at the wire boundary
 * before any request is sent to Bedrock (CT-INV-5, the negative test). Detecting this
 * here — rather than letting Bedrock reject a malformed request — keeps the
 * protocol-correctness property the agent guarantees local and testable.
 *
 * <p>This is a protocol/programming fault in the caller's transcript assembly, not a
 * model-backend failure: it is unchecked and distinct from
 * {@link ModelBackendException} (which carries an actual backend call failure mapped to
 * exit {@code 4}). The {@link #getMessage() message} names the offending
 * {@code toolUseId} so the fault is debuggable.
 */
public final class ToolProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a tool-protocol exception describing the violation.
     *
     * @param message a human-readable description naming the offending
     *                {@code toolUseId} (INV-6); must not be {@code null}.
     */
    public ToolProtocolException(String message) {
        super(message);
    }
}
