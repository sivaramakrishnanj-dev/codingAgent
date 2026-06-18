package com.srk.codingagent.model.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * Unit tests for {@link BedrockCredentials} — the inspectable value object that
 * carries the resolved SigV4 provider plus the resolved-tier label.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>CT-INV-13 / INV-16</b> — the chosen SigV4 provider is inspectable through
 *       this value object (so a test can assert it is the SigV4 provider supplied).</li>
 *   <li><b>ADR-0011</b> — the resolved-tier label is present and non-blank for the
 *       startup log line; the value object refuses an invalid (null/blank) state.</li>
 * </ul>
 * The SUT (a real record) is never mocked.
 */
class BedrockCredentialsTest {

    private static AwsCredentialsProvider sigV4Provider() {
        return () -> AwsBasicCredentials.create("AKIAEXAMPLE", "secretExample");
    }

    @Test
    @DisplayName("CT-INV-13: the SigV4 provider is exposed unchanged (inspectable seam)")
    void exposesProviderForInspection() {
        // Oracle: CT-INV-13 — "verify not-used by asserting the constructed provider is
        // the SigV4 one supplied". The value object must hand back the exact provider.
        AwsCredentialsProvider provider = sigV4Provider();

        BedrockCredentials creds = new BedrockCredentials(provider, "default chain");

        assertSame(provider, creds.provider(),
                "CT-INV-13: the exposed provider must be the supplied SigV4 provider");
    }

    @Test
    @DisplayName("the resolved-tier label is carried for the startup log (ADR-0011)")
    void exposesResolvedViaLabel() {
        // Oracle: ADR-0011 — a clear startup log records which tier resolved (profile
        // name or "default chain"). The label is carried verbatim.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "profile 'awsBedRockProfile'");

        assertEquals("profile 'awsBedRockProfile'", creds.resolvedVia(),
                "ADR-0011: the tier label must be carried for the startup log");
    }

    @Test
    @DisplayName("a null provider is rejected (the provider is load-bearing, INV-16)")
    void nullProvider_rejected() {
        // Oracle: INV-16 — a Bedrock credential set with no SigV4 provider is not a valid
        // state; the value object refuses it.
        assertThrows(NullPointerException.class,
                () -> new BedrockCredentials(null, "default chain"),
                "INV-16: a BedrockCredentials with no provider must not exist");
    }

    @Test
    @DisplayName("a blank resolved-tier label is rejected (a log line needs a tier, ADR-0011)")
    void blankResolvedVia_rejected() {
        // Oracle: ADR-0011 — the startup log must state the resolved tier; a blank label
        // would defeat that, so it is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new BedrockCredentials(sigV4Provider(), "  "),
                "ADR-0011: a blank tier label must be rejected");
    }
}
