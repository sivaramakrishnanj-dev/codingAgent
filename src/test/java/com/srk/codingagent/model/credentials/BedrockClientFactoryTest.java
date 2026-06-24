package com.srk.codingagent.model.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
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
 *   <li><b>NFR-BEDROCK-CALL-TIMEOUT / AC-8.10 / AC-8.11 / ADR-0001 (DCR-4)</b> — the
 *       wiring carries the connect/response budget. ADR-0001 maps {@code apiCallTimeout}
 *       and the Apache {@code socketTimeout} to the response budget and the Apache
 *       {@code connectionTimeout} to the connect budget; {@code create(...)} reads both
 *       from the wiring, so asserting the wiring's two durations equal the configured
 *       budget verifies that mapping by inspection (the CT-INV-13 SUT-not-mocked pattern —
 *       no SDK client built, no live Bedrock call). Defaults: connect 10 s, response 300 s
 *       (AC-8.11); a non-default config is honoured (AC-8.10).</li>
 * </ul>
 * The SUT (a real {@link BedrockClientFactory}) is never mocked; the wiring is computed
 * by the real factory and inspected directly (no SDK client is constructed, so no
 * network or credential access occurs).
 */
class BedrockClientFactoryTest {

    /** NFR-BEDROCK-CALL-TIMEOUT default connect budget (AC-8.11). */
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;

    /** NFR-BEDROCK-CALL-TIMEOUT default response budget (AC-8.11). */
    private static final int DEFAULT_RESPONSE_TIMEOUT_SECONDS = 300;

    private final BedrockClientFactory factory = new BedrockClientFactory();

    private static AwsCredentialsProvider sigV4Provider() {
        return () -> AwsBasicCredentials.create("AKIAEXAMPLE", "secretExample");
    }

    /** The factory wiring under the NFR-BEDROCK-CALL-TIMEOUT defaults (connect 10, response 300). */
    private BedrockClientFactory.Wiring wiringWithDefaultTimeouts(BedrockCredentials creds,
            String region) {
        return factory.wiring(creds, region, DEFAULT_CONNECT_TIMEOUT_SECONDS,
                DEFAULT_RESPONSE_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("CT-INV-13: the wiring selects the exact SigV4 provider supplied (never bearer)")
    void ct_inv_13_wiresSuppliedSigV4Provider() {
        // Oracle: CT-INV-13 / AC-8.8 / INV-16 — the client must be wired with the SigV4
        // provider the resolver selected. Asserting the wired provider is the same
        // instance verifies "not bearer" by inspection, per the CT-INV-13 directive.
        AwsCredentialsProvider provider = sigV4Provider();
        BedrockCredentials creds = new BedrockCredentials(provider, "profile 'awsBedRockProfile'");

        BedrockClientFactory.Wiring wiring = wiringWithDefaultTimeouts(creds, "us-east-1");

        assertSame(provider, wiring.credentialsProvider(),
                "CT-INV-13: the client must be wired with the supplied SigV4 provider, never bearer");
    }

    @Test
    @DisplayName("NFR-BEDROCK-REGION: the client is pinned to the configured region")
    void nfr_bedrockRegion_pinsConfiguredRegion() {
        // Oracle: NFR-BEDROCK-REGION — region comes from configuration (here us-east-1,
        // the default). The wiring must pin exactly that region.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = wiringWithDefaultTimeouts(creds, "us-east-1");

        assertEquals(Region.of("us-east-1"), wiring.region(),
                "NFR-BEDROCK-REGION: the client must be pinned to the configured region");
    }

    @Test
    @DisplayName("a non-default configured region is honoured (NFR-BEDROCK-REGION configurable)")
    void nfr_bedrockRegion_honoursNonDefaultRegion() {
        // Oracle: NFR-BEDROCK-REGION — the region is configurable (ADR-0009); a non-default
        // region must bind, not be forced to the default.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = wiringWithDefaultTimeouts(creds, "us-west-2");

        assertEquals(Region.of("us-west-2"), wiring.region(),
                "NFR-BEDROCK-REGION: a configured non-default region must be honoured");
    }

    @Test
    @DisplayName("null credentials are rejected (the SigV4 provider is load-bearing, INV-16)")
    void nullCredentials_rejected() {
        // Oracle: INV-16 — a Bedrock client cannot be wired without a SigV4 provider.
        assertThrows(NullPointerException.class,
                () -> factory.wiring(null, "us-east-1", DEFAULT_CONNECT_TIMEOUT_SECONDS,
                        DEFAULT_RESPONSE_TIMEOUT_SECONDS),
                "INV-16: wiring a client without SigV4 credentials must fail");
    }

    @Test
    @DisplayName("a blank region is rejected (NFR-BEDROCK-REGION requires a region)")
    void blankRegion_rejected() {
        // Oracle: NFR-BEDROCK-REGION — a region is required; a blank one is invalid.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        assertThrows(IllegalArgumentException.class,
                () -> factory.wiring(creds, "  ", DEFAULT_CONNECT_TIMEOUT_SECONDS,
                        DEFAULT_RESPONSE_TIMEOUT_SECONDS),
                "NFR-BEDROCK-REGION: a blank region must be rejected");
    }

    @Test
    @DisplayName("NFR-BEDROCK-CALL-TIMEOUT: the wiring carries the default connect 10 s / "
            + "response 300 s budget (AC-8.11, ADR-0001)")
    void nfr_bedrockCallTimeout_wiresDefaultBudget() {
        // Oracle: NFR-BEDROCK-CALL-TIMEOUT / AC-8.11 — connect 10 s, overall response 300 s
        // when unconfigured. ADR-0001 wires apiCallTimeout = response, Apache socketTimeout
        // = response, Apache connectionTimeout = connect; create(...) reads both durations
        // from the wiring, so asserting the wiring's two durations equal the default budget
        // pins the apiCall/socket = 300 s and connect = 10 s mapping by inspection.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = factory.wiring(creds, "us-east-1",
                DEFAULT_CONNECT_TIMEOUT_SECONDS, DEFAULT_RESPONSE_TIMEOUT_SECONDS);

        assertEquals(Duration.ofSeconds(10), wiring.connectTimeout(),
                "NFR-BEDROCK-CALL-TIMEOUT: default connect budget is 10 s -> Apache "
                        + "connectionTimeout (AC-8.11, ADR-0001)");
        assertEquals(Duration.ofSeconds(300), wiring.responseTimeout(),
                "NFR-BEDROCK-CALL-TIMEOUT: default response budget is 300 s -> apiCallTimeout "
                        + "and Apache socketTimeout (AC-8.11, ADR-0001)");
    }

    @Test
    @DisplayName("NFR-BEDROCK-CALL-TIMEOUT: a configured non-default connect/response budget is "
            + "honoured (AC-8.10, ADR-0001)")
    void nfr_bedrockCallTimeout_honoursConfiguredBudget() {
        // Oracle: AC-8.10 — both timeouts are configurable via the two config keys. A
        // non-default budget must bind to the wiring, not be forced to the NFR default.
        // ADR-0001 mapping unchanged: the response duration is apiCallTimeout + socketTimeout,
        // the connect duration is connectionTimeout.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        BedrockClientFactory.Wiring wiring = factory.wiring(creds, "us-east-1", 25, 600);

        assertEquals(Duration.ofSeconds(25), wiring.connectTimeout(),
                "AC-8.10: a configured connect budget must be honoured");
        assertEquals(Duration.ofSeconds(600), wiring.responseTimeout(),
                "AC-8.10: a configured response budget must be honoured");
    }

    @Test
    @DisplayName("a connect timeout below 1 is rejected (NFR-BEDROCK-CALL-TIMEOUT requires >= 1)")
    void connectTimeoutBelowOne_rejected() {
        // Oracle: the schema pins bedrockCallConnectTimeoutSeconds minimum 1; the factory
        // seam rejects a sub-1 budget rather than constructing a degenerate client.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        assertThrows(IllegalArgumentException.class,
                () -> factory.wiring(creds, "us-east-1", 0, DEFAULT_RESPONSE_TIMEOUT_SECONDS),
                "a connect timeout below 1 must be rejected (NFR-BEDROCK-CALL-TIMEOUT min 1)");
    }

    @Test
    @DisplayName("a response timeout below 1 is rejected (NFR-BEDROCK-CALL-TIMEOUT requires >= 1)")
    void responseTimeoutBelowOne_rejected() {
        // Oracle: the schema pins bedrockCallResponseTimeoutSeconds minimum 1; the factory
        // seam rejects a sub-1 budget rather than constructing a degenerate client.
        BedrockCredentials creds = new BedrockCredentials(sigV4Provider(), "default chain");

        assertThrows(IllegalArgumentException.class,
                () -> factory.wiring(creds, "us-east-1", DEFAULT_CONNECT_TIMEOUT_SECONDS, 0),
                "a response timeout below 1 must be rejected (NFR-BEDROCK-CALL-TIMEOUT min 1)");
    }
}
