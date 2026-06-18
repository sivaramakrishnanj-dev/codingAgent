package com.srk.codingagent.model.credentials;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
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
 * <p>The wiring decision — which SigV4 provider and which {@link Region} the client is
 * pinned to — is exposed as the pure, inspectable {@link #wiring(BedrockCredentials,
 * String)} method. A test asserts the wiring selects the exact SigV4 provider supplied
 * and the configured region, verifying "not bearer" by inspecting the provider that is
 * actually wired rather than depending on real SDK env-var pickup behaviour
 * (CT-INV-13, INV-16). {@link #create(BedrockCredentials, String)} applies that wiring
 * onto the real SDK builder.
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
     * credentials, pinned to the given region.
     *
     * @param credentials the resolved SigV4 credentials (provider + tier label);
     *                    never {@code null}. The provider is set explicitly so no
     *                    ambient bearer token is used (AC-8.8, INV-16).
     * @param region      the AWS region for Bedrock (NFR-BEDROCK-REGION); never
     *                    {@code null} or blank.
     * @return a configured {@code BedrockRuntimeClient}; never {@code null}.
     * @throws NullPointerException     if {@code credentials} is {@code null}.
     * @throws IllegalArgumentException if {@code region} is {@code null} or blank.
     */
    public BedrockRuntimeClient create(BedrockCredentials credentials, String region) {
        Wiring wiring = wiring(credentials, region);
        LOGGER.info("Building Bedrock client in region {} with SigV4 credentials resolved via {}",
                wiring.region(), credentials.resolvedVia());
        return BedrockRuntimeClient.builder()
                .credentialsProvider(wiring.credentialsProvider())
                .region(wiring.region())
                .build();
    }

    /**
     * Computes the client wiring — the SigV4 credentials provider and the
     * {@link Region} — without touching the SDK builder. Pure and inspectable: a test
     * asserts the provider is the exact SigV4 provider supplied (never bearer) and the
     * region matches the configuration (CT-INV-13, INV-16, NFR-BEDROCK-REGION).
     *
     * @param credentials the resolved SigV4 credentials whose provider is wired; never
     *                    {@code null}.
     * @param region      the AWS region string to pin; never {@code null} or blank.
     * @return the wiring (SigV4 provider + region) the client will be built with.
     * @throws NullPointerException     if {@code credentials} is {@code null}.
     * @throws IllegalArgumentException if {@code region} is {@code null} or blank.
     */
    Wiring wiring(BedrockCredentials credentials, String region) {
        Objects.requireNonNull(credentials, "credentials");
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException("region must be non-blank");
        }
        return new Wiring(credentials.provider(), Region.of(region));
    }

    /**
     * The explicit client wiring: the SigV4 credentials provider and the region the
     * {@link BedrockRuntimeClient} is constructed with. The provider is always the
     * SigV4 provider resolved by {@link CredentialResolver} — never a bearer-token
     * provider (AC-8.8, INV-16).
     *
     * @param credentialsProvider the SigV4 credentials provider; never {@code null}.
     * @param region              the pinned AWS region; never {@code null}.
     */
    record Wiring(AwsCredentialsProvider credentialsProvider, Region region) {
    }
}
