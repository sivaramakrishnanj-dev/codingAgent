package com.srk.codingagent.model.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Unit tests for {@link BedrockClientFactory} — the SigV4 client-construction seam.
 *
 * <p>Oracles:
 * <ul>
 *   <li><b>CT-INV-13 / AC-8.8 / INV-16</b> — the client builder selects the SigV4
 *       provider supplied; an ambient bearer token cannot displace it. Verified by
 *       inspecting the wiring the factory computes (the exact provider set), per the
 *       CT-INV-13 directive to make the chosen provider inspectable rather than relying
 *       on SDK env-var pickup.</li>
 *   <li><b>NFR-BEDROCK-REGION</b> — the client is pinned to the configured region.</li>
 * </ul>
 * The SUT (a real {@link BedrockClientFactory}) is never mocked; the wiring is computed
 * by the real factory and inspected directly (no SDK client is constructed, so no
 * network or credential access occurs).
 */
class BedrockClientFactoryTest {

    private final BedrockClientFactory factory = new BedrockClientFactory();

    private static AwsCredentialsProvider sigV4Provider() {
        return () -> AwsBasicCredentials.create("AKIAEXAMPLE", "secretExample");
    }

    @Test
    @DisplayName("CT-INV-13: the wiring selects the exact SigV4 provider supplied (never bearer)")
    void ct_inv_13_wiresSuppliedSigV4Provider() {
        // Oracle: CT-INV-13 / AC-8.8 / INV-16 — the client must be wired with the SigV4
        // provider the resolver selected. Asserting the wired provider is the same
        // instance verifies "not bearer" by inspection, per the CT-INV-13 directive.
        AwsCredentialsProvider provider = sigV4Provider();
        BedrockCredentials creds = new BedrockCredentials(provider, "profile 'awsBedRockProfile'");

        BedrockClientFactory.Wiring wiring = factory.wiring(creds, "us-east-1");

        assertSame(provider, wiring.credentialsProvider(),
                "CT-INV-13: the client must be wired with the supplied SigV4 provider, never bearer");
    }

    @Test
    @DisplayName("NFR-BEDROCK-REGION: the client is pinned to the configured region")
    void nfr_bedrockRegion_pinsConfiguredRegion() {
        // Oracle: NFR-BEDROCK-REGION — region comes from configuration (here us-east-1,
        // the default). The wiring must pin exactly that region.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = factory.wiring(creds, "us-east-1");

        assertEquals(Region.of("us-east-1"), wiring.region(),
                "NFR-BEDROCK-REGION: the client must be pinned to the configured region");
    }

    @Test
    @DisplayName("a non-default configured region is honoured (NFR-BEDROCK-REGION configurable)")
    void nfr_bedrockRegion_honoursNonDefaultRegion() {
        // Oracle: NFR-BEDROCK-REGION — the region is configurable (ADR-0009); a non-default
        // region must bind, not be forced to the default.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = factory.wiring(creds, "us-west-2");

        assertEquals(Region.of("us-west-2"), wiring.region(),
                "NFR-BEDROCK-REGION: a configured non-default region must be honoured");
    }

    @Test
    @DisplayName("null credentials are rejected (the SigV4 provider is load-bearing, INV-16)")
    void nullCredentials_rejected() {
        // Oracle: INV-16 — a Bedrock client cannot be wired without a SigV4 provider.
        assertThrows(NullPointerException.class,
                () -> factory.wiring(null, "us-east-1"),
                "INV-16: wiring a client without SigV4 credentials must fail");
    }

    @Test
    @DisplayName("a blank region is rejected (NFR-BEDROCK-REGION requires a region)")
    void blankRegion_rejected() {
        // Oracle: NFR-BEDROCK-REGION — a region is required; a blank one is invalid.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        assertThrows(IllegalArgumentException.class,
                () -> factory.wiring(creds, "  "),
                "NFR-BEDROCK-REGION: a blank region must be rejected");
    }
}
