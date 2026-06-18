package com.srk.codingagent.model.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.cli.ExitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CredentialResolutionException} — the typed carrier of the
 * no-usable-credentials failure (AC-8.9), and its mapping to exit {@code 4}.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>CT-EX-2 / AC-8.9</b> — no usable SigV4 credentials maps to exit {@code 4}
 *       (model-backend). The numeric exit code is pinned by the exit-code contract
 *       ({@link ExitCode#MODEL_BACKEND} = {@code 4}), distinct from a config fault
 *       (exit {@code 2}).</li>
 *   <li><b>AC-8.9</b> — the message names the paths attempted; the exception chains
 *       the underlying cause for debuggability.</li>
 * </ul>
 *
 * <p>This test pins the exit-code mapping at the type boundary (the same pattern
 * {@code ExitCodeTest} uses for the shared exit-code value type): the
 * {@link CredentialResolutionException} is the contract carrier that the CLI dispatch
 * (a later task) maps to {@link ExitCode#MODEL_BACKEND}. Asserting
 * {@code MODEL_BACKEND.code() == 4} ties CT-EX-2's "exit 4" to the contract symbol,
 * not to dispatch code that does not yet exist.
 */
class CredentialResolutionExceptionTest {

    @Test
    @DisplayName("CT-EX-2: the no-credentials failure maps to exit 4 (MODEL_BACKEND)")
    void ct_ex_2_mapsToModelBackendExitFour() {
        // Oracle: CT-EX-2 / AC-8.9 — no usable SigV4 credentials -> exit 4. The numeric
        // value 4 is the exit-code contract's model-backend code.
        assertEquals(4, ExitCode.MODEL_BACKEND.code(),
                "CT-EX-2: the credential-resolution failure exit code is 4 (model-backend)");
    }

    @Test
    @DisplayName("AC-8.9: the message is carried verbatim (it names the attempted paths)")
    void message_isCarried() {
        // Oracle: AC-8.9 — the failure names the paths attempted; the exception carries
        // that message for the CLI's stderr line.
        String message = "no usable SigV4 credentials for Bedrock; paths attempted: "
                + "profile 'awsBedRockProfile' and the default chain";

        CredentialResolutionException ex = new CredentialResolutionException(message);

        assertEquals(message, ex.getMessage(),
                "AC-8.9: the paths-attempted message must be carried");
        assertTrue(ex.getMessage().contains("awsBedRockProfile") && ex.getMessage().contains("default chain"),
                "AC-8.9: the message names both attempted paths");
    }

    @Test
    @DisplayName("the underlying cause is chained (debuggability, java.md error handling)")
    void cause_isChained() {
        // Oracle: java.md error-handling — custom exceptions chain their cause rather
        // than swallowing it.
        Throwable cause = new IllegalStateException("Unable to load credentials");

        CredentialResolutionException ex =
                new CredentialResolutionException("no usable SigV4 credentials", cause);

        assertSame(cause, ex.getCause(), "the underlying cause must be chained");
    }
}
