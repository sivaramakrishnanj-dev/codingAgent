package com.srk.codingagent.model.credentials;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

/**
 * The resolved SigV4 credentials for Bedrock: the {@link AwsCredentialsProvider} the
 * Model Client (C4) must hand to the {@code BedrockRuntimeClient}, plus the
 * human-readable label of the tier that resolved it.
 *
 * <p>Per ADR-0011 the agent authenticates Bedrock with SigV4 only, via one of two
 * tiers — a named AWS profile or the AWS SDK v2 default provider chain — and never
 * with an ambient {@code AWS_BEARER_TOKEN_BEDROCK} (AC-8.8, INV-16). This value
 * object makes the chosen provider <em>inspectable</em>: it exposes the exact
 * SigV4 provider that resolution selected (so a test can assert it is the SigV4
 * provider supplied, satisfying CT-INV-13 without depending on real SDK env-var
 * pickup) and the resolved-tier label for the clear startup log line ADR-0011
 * requires (the profile name, or {@code "default chain"} — never a secret).
 *
 * @param provider    the SigV4 credentials provider the client must use; never
 *                    {@code null} and never a bearer-token provider (ADR-0011,
 *                    INV-16).
 * @param resolvedVia the human-readable tier label that resolved these credentials
 *                    (for example {@code "profile 'awsBedRockProfile'"} or
 *                    {@code "default chain"}); never {@code null} or blank. Used for
 *                    the startup log line; carries no secret material.
 */
public record BedrockCredentials(AwsCredentialsProvider provider, String resolvedVia) {

    /**
     * Validates the invariants: a non-null SigV4 provider and a non-blank tier
     * label. A {@code BedrockCredentials} that violates them cannot exist.
     *
     * @throws NullPointerException     if {@code provider} is {@code null}.
     * @throws IllegalArgumentException if {@code resolvedVia} is {@code null} or
     *                                  blank.
     */
    public BedrockCredentials {
        Objects.requireNonNull(provider, "provider");
        if (resolvedVia == null || resolvedVia.isBlank()) {
            throw new IllegalArgumentException("resolvedVia must be non-blank");
        }
    }
}
