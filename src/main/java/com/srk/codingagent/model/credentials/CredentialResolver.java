package com.srk.codingagent.model.credentials;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;

/**
 * Resolves the SigV4 credentials Bedrock authenticates with, in the two-tier order
 * ADR-0011 pins: a named AWS profile first, then the AWS SDK v2 default credential
 * provider chain — and never an ambient bearer token.
 *
 * <p>Behaviour (ADR-0011, AC-8.6&ndash;8.9, INV-16):
 * <ol>
 *   <li><b>Named profile (AC-8.6).</b> If a profile name is configured, resolve via
 *       a {@link ProfileCredentialsProvider} for that profile. The provider is
 *       <em>probed</em> ({@link AwsCredentialsProvider#resolveCredentials()}) so a
 *       profile that is configured but cannot yield credentials does not silently
 *       win.</li>
 *   <li><b>Default chain (AC-8.7).</b> If no profile is configured, <em>or</em> the
 *       configured profile is not found / yields no credentials, fall back to the
 *       {@link DefaultCredentialsProvider} (env vars &rarr; SSO/SDK token cache
 *       &rarr; container/instance role) rather than failing.</li>
 *   <li><b>Neither path usable (AC-8.9).</b> If neither tier yields usable SigV4
 *       credentials, throw {@link CredentialResolutionException} with a message that
 *       names the paths attempted; the CLI maps it to exit {@code 4}.</li>
 * </ol>
 *
 * <p><b>Bearer token explicitly ignored (AC-8.8, INV-16 — the load-bearing safety
 * property).</b> Resolution never authenticates with {@code AWS_BEARER_TOKEN_BEDROCK}.
 * The {@link AwsCredentialsProvider} this resolver returns is always a SigV4 provider
 * (profile or default chain) — the inspectable seam CT-INV-13 asserts against. When a
 * bearer token <em>is</em> present in the environment, the resolver logs a one-line
 * {@code WARN} so the ignore is observable and debuggable, then proceeds with SigV4.
 * Construction of the {@code BedrockRuntimeClient} from the returned provider lives in
 * {@link BedrockClientFactory}, which never enables bearer auth.
 *
 * <p>The provider factories and the bearer-environment lookup are injected so the
 * SigV4-selection and bearer-ignore behaviour are unit-testable without touching
 * {@code ~/.aws}, reading a real environment variable, or making any network call.
 * The {@link #CredentialResolver() default constructor} wires the production
 * factories.
 */
public final class CredentialResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialResolver.class);

    /**
     * The environment variable the AWS SDK v2 Bedrock client may natively honour for
     * bearer-token auth. ADR-0011/AC-8.8 require this tool to ignore it even when set.
     */
    static final String BEARER_TOKEN_ENV_VAR = "AWS_BEARER_TOKEN_BEDROCK";

    /** Stable tier label for the default credential chain (ADR-0011 startup log). */
    static final String DEFAULT_CHAIN_LABEL = "default chain";

    private final Function<String, AwsCredentialsProvider> profileProviderFactory;
    private final Supplier<AwsCredentialsProvider> defaultChainProviderFactory;
    private final UnaryOperator<String> environment;

    /**
     * Creates a resolver wired to the production SigV4 provider factories and the
     * real process environment. Equivalent to passing
     * {@link ProfileCredentialsProvider#create(String)},
     * {@link DefaultCredentialsProvider#create()}, and {@link System#getenv(String)}.
     */
    public CredentialResolver() {
        this(ProfileCredentialsProvider::create, DefaultCredentialsProvider::create, System::getenv);
    }

    /**
     * Creates a resolver with injected SigV4 provider factories and environment
     * lookup. Used by tests to exercise SigV4 selection, profile fallback, the
     * bearer-ignore warning, and the no-credentials path hermetically — no
     * {@code ~/.aws} access, no real env var, no network call.
     *
     * @param profileProviderFactory      builds a SigV4 provider for a named profile
     *                                    given the profile name; never {@code null}.
     * @param defaultChainProviderFactory builds the SigV4 default-chain provider;
     *                                    never {@code null}.
     * @param environment                 looks up an environment variable by name,
     *                                    returning {@code null} when unset; never
     *                                    {@code null}.
     */
    CredentialResolver(
            Function<String, AwsCredentialsProvider> profileProviderFactory,
            Supplier<AwsCredentialsProvider> defaultChainProviderFactory,
            UnaryOperator<String> environment) {
        this.profileProviderFactory = profileProviderFactory;
        this.defaultChainProviderFactory = defaultChainProviderFactory;
        this.environment = environment;
    }

    /**
     * Resolves the SigV4 credentials for Bedrock from the configured profile, falling
     * back to the default credential chain, ignoring any ambient bearer token.
     *
     * @param awsProfile the configured AWS profile name, or {@code null} / blank to
     *                   go straight to the default chain (matches
     *                   {@link com.srk.codingagent.config.ResolvedConfig#awsProfile()},
     *                   which is {@code null} when unconfigured).
     * @return the resolved SigV4 credentials and the tier label that produced them;
     *         never {@code null}.
     * @throws CredentialResolutionException if neither the named profile nor the
     *                                       default chain yields usable SigV4
     *                                       credentials (AC-8.9 &rarr; exit {@code 4}).
     */
    public BedrockCredentials resolve(String awsProfile) {
        warnIfBearerTokenPresent();

        boolean profileConfigured = awsProfile != null && !awsProfile.isBlank();
        if (profileConfigured) {
            BedrockCredentials fromProfile = tryProfile(awsProfile);
            if (fromProfile != null) {
                return fromProfile;
            }
        }

        return resolveFromDefaultChain(awsProfile, profileConfigured);
    }

    private BedrockCredentials tryProfile(String awsProfile) {
        AwsCredentialsProvider provider = profileProviderFactory.apply(awsProfile);
        try {
            provider.resolveCredentials();
        } catch (RuntimeException probeFailure) {
            // AC-8.7: a configured profile that is not found / yields no credentials
            // must fall back to the default chain rather than fail.
            LOGGER.warn(
                    "AWS profile '{}' did not yield SigV4 credentials; falling back to the {} (AC-8.7)",
                    awsProfile, DEFAULT_CHAIN_LABEL, probeFailure);
            return null;
        }
        String resolvedVia = "profile '" + awsProfile + "'";
        LOGGER.info("Resolved Bedrock SigV4 credentials via {} (AC-8.6)", resolvedVia);
        return new BedrockCredentials(provider, resolvedVia);
    }

    private BedrockCredentials resolveFromDefaultChain(String awsProfile, boolean profileConfigured) {
        AwsCredentialsProvider provider;
        try {
            // AC-8.9: a default chain that yields no usable credentials is a failure of
            // "either path"; treat any failure of the build-or-probe as unusable, so the
            // caller always sees the typed CredentialResolutionException (exit 4), never a
            // raw SDK exception leaking through.
            provider = defaultChainProviderFactory.get();
            provider.resolveCredentials();
        } catch (RuntimeException chainFailure) {
            throw new CredentialResolutionException(noUsableCredentialsMessage(awsProfile, profileConfigured),
                    chainFailure);
        }
        LOGGER.info("Resolved Bedrock SigV4 credentials via {} (AC-8.7)", DEFAULT_CHAIN_LABEL);
        return new BedrockCredentials(provider, DEFAULT_CHAIN_LABEL);
    }

    private void warnIfBearerTokenPresent() {
        String bearer = environment.apply(BEARER_TOKEN_ENV_VAR);
        if (bearer != null && !bearer.isBlank()) {
            // AC-8.8 / INV-16: never authenticate with a bearer token; make the
            // ignore observable. Never log the token value.
            LOGGER.warn("ignoring {}; this tool authenticates via SigV4 profile/chain only",
                    BEARER_TOKEN_ENV_VAR);
        }
    }

    private static String noUsableCredentialsMessage(String awsProfile, boolean profileConfigured) {
        // AC-8.9: name the paths attempted. Include the profile name only when one
        // was configured (otherwise the only path tried was the default chain).
        String profilePath = profileConfigured ? "profile '" + awsProfile + "'" : "no profile configured";
        return "no usable SigV4 credentials for Bedrock; paths attempted: " + profilePath
                + " and the " + DEFAULT_CHAIN_LABEL;
    }
}
