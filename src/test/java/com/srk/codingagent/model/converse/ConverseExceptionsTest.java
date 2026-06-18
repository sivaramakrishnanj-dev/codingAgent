package com.srk.codingagent.model.converse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Model Client's typed exceptions: {@link ToolProtocolException}
 * (INV-6 / CT-INV-5 protocol fault) and {@link ModelBackendException} (the backend
 * failure mapped to exit 4, ADR-0001). The tests pin the contract each exception's
 * Javadoc states: the message is preserved, and the backend exception chains its cause.
 */
class ConverseExceptionsTest {

    @Test
    @DisplayName("INV-6: a ToolProtocolException preserves its describing message")
    void toolProtocolException_preservesMessage() {
        // Oracle: INV-6 — the rejection of an unpaired toolResult must name the offending
        // toolUseId; the exception must carry that message through.
        ToolProtocolException exception =
                new ToolProtocolException("toolResult references toolUseId 'tu-9'");

        assertEquals("toolResult references toolUseId 'tu-9'", exception.getMessage(),
                "INV-6: the protocol-violation message is preserved");
    }

    @Test
    @DisplayName("ADR-0001 / exit 4: a ModelBackendException chains its cause for debuggability")
    void modelBackendException_chainsCause() {
        // Oracle: ADR-0001 / debuggability tenet — a backend failure must chain the
        // originating SDK exception so the request id and service detail survive.
        RuntimeException cause = new RuntimeException("sdk failure");

        ModelBackendException exception =
                new ModelBackendException("Bedrock Converse call failed", cause);

        assertEquals("Bedrock Converse call failed", exception.getMessage(),
                "the backend-failure message is preserved");
        assertSame(cause, exception.getCause(),
                "the underlying SDK exception must be chained as the cause");
    }
}
