package com.srk.codingagent.model.credentials;

import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Constructs the {@link BedrockRuntimeClient} the Model Client (C4) uses, wiring it
 * explicitly to the resolved SigV4 credentials and the configured region.
 *
 * <p>This is the build-time enforcement of ADR-0011's load-bearing safety property
 * (AC-8.8, INV-16): the client is constructed with an <em>explicit</em> SigV4
 * {@code AwsCredentialsProvider} (the one {@link CredentialResolver} selected, carried
 * by {@link BedrockCredentials}). It never enables bearer-token auth, so an ambient
 * {@code AWS_BEARER_TOKEN_BEDROCK} cannot take effect even if the SDK would otherwise
 * honour it natively.
 *
 * <p>The wiring decision — which SigV4 provider, which {@link Region} the client is pinned
 * to, and the NFR-BEDROCK-CALL-TIMEOUT connect/response budget — is exposed as the pure,
 * inspectable {@link #wiring(BedrockCredentials, String, int, int)} method. A test asserts
 * the wiring selects the exact SigV4 provider supplied and the configured region, verifying
 * "not bearer" by inspecting the provider that is actually wired rather than depending on
 * real SDK env-var pickup behaviour (CT-INV-13, INV-16), and asserts the configured connect
 * and response timeouts. {@link #create(BedrockCredentials, String, int, int)} applies that
 * wiring onto the real SDK builder.
 *
 * <p>Call timeouts (NFR-BEDROCK-CALL-TIMEOUT; DCR-4, ADR-0001). The client is built with
 * {@code apiCallTimeout} = the response budget (the end-to-end Converse budget, bounding the
 * whole call incl. retries/streaming at the SDK layer) and an Apache {@code httpClientBuilder}
 * whose {@code socketTimeout} = the response budget (idle-between-bytes / overall response,
 * sized to cover streaming incl. extended thinking) and {@code connectionTimeout} = the connect
 * budget (TCP/TLS establishment). Both budgets are read from
 * {@link com.srk.codingagent.config.ResolvedConfig} ({@code bedrockCallResponseTimeoutSeconds}
 * default 300, {@code bedrockCallConnectTimeoutSeconds} default 10). A timed-out call is a
 * retryable failure that counts toward NFR-BEDROCK-MAX-RETRIES.
 *
 * <p>Scope: this factory provides the configured client seam only. It does
 * <em>not</em> make Converse calls or map request/response wire formats — that is the
 * Model Client adapter built in a later task (T-0.5). The agent issues read/invoke
 * Bedrock calls only; no AWS write/create/delete verbs (ADR-0011, component C4).
 */
public final class BedrockClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockClientFactory.class);

    /**
     * Builds a {@link BedrockRuntimeClient} authenticated with the resolved SigV4
     * credentials, pinned to the given region, and bounded by the
     * NFR-BEDROCK-CALL-TIMEOUT connect/response budget (ADR-0001).
     *
     * @param credentials            the resolved SigV4 credentials (provider + tier
     *                               label); never {@code null}. The provider is set
     *                               explicitly so no ambient bearer token is used
     *                               (AC-8.8, INV-16).
     * @param region                 the AWS region for Bedrock (NFR-BEDROCK-REGION);
     *                               never {@code null} or blank.
     * @param connectTimeoutSeconds  the connect (TCP/TLS establishment) timeout in
     *                               seconds; {@code >= 1}
     *                               ({@code bedrockCallConnectTimeoutSeconds}, AC-8.10).
     * @param responseTimeoutSeconds the overall-response timeout in seconds (the
     *                               end-to-end Converse budget); {@code >= 1}
     *                               ({@code bedrockCallResponseTimeoutSeconds}, AC-8.10).
     * @return a configured {@code BedrockRuntimeClient}; never {@code null}.
     * @throws NullPointerException     if {@code credentials} is {@code null}.
     * @throws IllegalArgumentException if {@code region} is {@code null} or blank, or a
     *                                  timeout is {@code < 1}.
     */
    public BedrockRuntimeClient create(BedrockCredentials credentials, String region,
            int connectTimeoutSeconds, int responseTimeoutSeconds) {
        Wiring wiring = wiring(credentials, region, connectTimeoutSeconds, responseTimeoutSeconds);
        LOGGER.info("Building Bedrock client in region {} with SigV4 credentials resolved via {}; "
                + "connectTimeout={}, responseTimeout={}",
                wiring.region(), credentials.resolvedVia(), wiring.connectTimeout(),
                wiring.responseTimeout());
        return BedrockRuntimeClient.builder()
                .credentialsProvider(wiring.credentialsProvider())
                .region(wiring.region())
                // ADR-0001: apiCallTimeout = the response budget, bounding the whole call
                // (incl. retries/streaming) at the SDK layer.
                .overrideConfiguration(o -> o.apiCallTimeout(wiring.responseTimeout()))
                // ADR-0001: Apache httpClient with socketTimeout = the response budget
                // (covers streaming incl. extended thinking) and connectionTimeout = the
                // connect budget (TCP/TLS establishment).
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(wiring.connectTimeout())
                        .socketTimeout(wiring.responseTimeout()))
                .build();
    }

    /**
     * Computes the client wiring — the SigV4 credentials provider, the {@link Region},
     * and the NFR-BEDROCK-CALL-TIMEOUT connect/response budget — without touching the SDK
     * builder. Pure and inspectable: a test asserts the provider is the exact SigV4
     * provider supplied (never bearer), the region matches the configuration, and the
     * timeouts match the configured budget (CT-INV-13, INV-16, NFR-BEDROCK-REGION,
     * NFR-BEDROCK-CALL-TIMEOUT, AC-8.10/8.11, ADR-0001).
     *
     * @param credentials            the resolved SigV4 credentials whose provider is
     *                               wired; never {@code null}.
     * @param region                 the AWS region string to pin; never {@code null} or
     *                               blank.
     * @param connectTimeoutSeconds  the connect timeout in seconds; {@code >= 1}.
     * @param responseTimeoutSeconds the overall-response timeout in seconds; {@code >= 1}.
     * @return the wiring (SigV4 provider + region + connect/response timeouts) the client
     *         will be built with.
     * @throws NullPointerException     if {@code credentials} is {@code null}.
     * @throws IllegalArgumentException if {@code region} is {@code null} or blank, or a
     *                                  timeout is {@code < 1}.
     */
    Wiring wiring(BedrockCredentials credentials, String region, int connectTimeoutSeconds,
            int responseTimeoutSeconds) {
        Objects.requireNonNull(credentials, "credentials");
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region must be non-blank");
        }
        if (connectTimeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "connectTimeoutSeconds must be >= 1 (was " + connectTimeoutSeconds + ")");
        }
        if (responseTimeoutSeconds < 1) {
            throw new IllegalArgumentException(
                    "responseTimeoutSeconds must be >= 1 (was " + responseTimeoutSeconds + ")");
        }
        return new Wiring(credentials.provider(), Region.of(region),
                Duration.ofSeconds(connectTimeoutSeconds), Duration.ofSeconds(responseTimeoutSeconds));
    }

    /**
     * The explicit client wiring: the SigV4 credentials provider, the region, and the
     * NFR-BEDROCK-CALL-TIMEOUT connect/response budget the {@link BedrockRuntimeClient} is
     * constructed with. The provider is always the SigV4 provider resolved by
     * {@link CredentialResolver} — never a bearer-token provider (AC-8.8, INV-16).
     *
     * @param credentialsProvider the SigV4 credentials provider; never {@code null}.
     * @param region              the pinned AWS region; never {@code null}.
     * @param connectTimeout      the connect (TCP/TLS establishment) timeout, wired to the
     *                            Apache httpClient {@code connectionTimeout} (ADR-0001);
     *                            never {@code null}.
     * @param responseTimeout     the overall-response timeout, wired to {@code apiCallTimeout}
     *                            and the Apache httpClient {@code socketTimeout} (ADR-0001);
     *                            never {@code null}.
     */
    record Wiring(AwsCredentialsProvider credentialsProvider, Region region,
            Duration connectTimeout, Duration responseTimeout) {
    }
}
