package com.srk.codingagent.model.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Unit tests for {@link CredentialResolver} — the SigV4 two-tier resolution
 * (named profile &rarr; default chain) with explicit bearer-token ignore.
 *
 * <p>Oracles (each test derives its expectation from the cited spec symbol, never
 * from the resolver's observed behavior):
 * <ul>
 *   <li><b>AC-8.6</b> — a configured profile resolves credentials from that named
 *       profile.</li>
 *   <li><b>AC-8.7</b> — no profile configured, or a configured profile not found /
 *       yielding no credentials, falls back to the default chain rather than
 *       failing.</li>
 *   <li><b>AC-8.8 / INV-16 / CT-INV-13</b> — authentication is SigV4 only; an
 *       ambient {@code AWS_BEARER_TOKEN_BEDROCK} is never used and is warned. Verified
 *       by asserting the selected provider is the exact SigV4 provider supplied
 *       (inspectable seam), not by relying on real SDK env-var pickup.</li>
 *   <li><b>AC-8.9 / CT-EX-2</b> — neither path yields usable SigV4 credentials &rarr;
 *       a {@link CredentialResolutionException} naming the paths attempted (mapped to
 *       exit {@code 4}).</li>
 * </ul>
 * The SUT (a real {@link CredentialResolver}) is never mocked; only the SDK provider
 * factories and the environment lookup — true external dependencies (the AWS SDK and
 * the process environment) — are stubbed with hand-written test doubles so the tests
 * touch no {@code ~/.aws} file, no real env var, and no network.
 */
class CredentialResolverTest {

    private static final AwsCredentials WORKING_CREDS =
            AwsBasicCredentials.create("AKIAEXAMPLE", "secretExample");

    /** A SigV4 provider that yields credentials when probed. */
    private static AwsCredentialsProvider working() {
        return () -> WORKING_CREDS;
    }

    /** A SigV4 provider that fails when probed, as the SDK does when none are found. */
    private static AwsCredentialsProvider failing(String why) {
        return () -> {
            throw SdkClientException.create(why);
        };
    }

    /** Environment with no bearer token set. */
    private static UnaryOperator<String> noBearer() {
        return name -> null;
    }

    @Nested
    @DisplayName("AC-8.6: a configured profile resolves from that named profile")
    class NamedProfile {

        @Test
        @DisplayName("a configured, usable profile yields its SigV4 provider (AC-8.6)")
        void configuredProfile_resolvesViaProfile() {
            // Oracle: AC-8.6 — where a profile name is configured, resolve from that
            // named profile. The selected provider must be the SigV4 profile provider.
            AwsCredentialsProvider profileProvider = working();
            List<String> profileNamesAskedFor = new ArrayList<>();
            Function<String, AwsCredentialsProvider> profileFactory = name -> {
                profileNamesAskedFor.add(name);
                return profileProvider;
            };

            CredentialResolver resolver = new CredentialResolver(
                    profileFactory, () -> working(), noBearer());

            BedrockCredentials resolved = resolver.resolve("awsBedRockProfile");

            assertSame(profileProvider, resolved.provider(),
                    "AC-8.6: the resolved provider must be the SigV4 provider for the named profile");
            assertEquals(List.of("awsBedRockProfile"), profileNamesAskedFor,
                    "AC-8.6: the named profile must be the one configured");
            assertTrue(resolved.resolvedVia().contains("awsBedRockProfile"),
                    "the resolved-tier label must name the profile for the startup log (ADR-0011)");
        }

        @Test
        @DisplayName("the configured profile is preferred over the default chain when usable (AC-8.6)")
        void usableProfile_preferredOverDefaultChain() {
            // Oracle: AC-8.6 precedence — the named profile is tier 1; the default chain
            // (tier 2) is not consulted when the profile yields credentials.
            AwsCredentialsProvider profileProvider = working();
            List<String> defaultChainBuilt = new ArrayList<>();
            Supplier<AwsCredentialsProvider> defaultChain = () -> {
                defaultChainBuilt.add("built");
                return working();
            };

            CredentialResolver resolver = new CredentialResolver(
                    name -> profileProvider, defaultChain, noBearer());

            BedrockCredentials resolved = resolver.resolve("awsBedRockProfile");

            assertSame(profileProvider, resolved.provider(),
                    "AC-8.6: profile is tier 1 and wins when usable");
            assertTrue(defaultChainBuilt.isEmpty(),
                    "AC-8.6: the default chain must not be consulted when the profile resolves");
        }
    }

    @Nested
    @DisplayName("AC-8.7: fall back to the default chain rather than failing")
    class DefaultChainFallback {

        @Test
        @DisplayName("no profile configured (null) resolves via the default chain (AC-8.7)")
        void noProfile_resolvesViaDefaultChain() {
            // Oracle: AC-8.7 — if no profile is configured, fall back to the AWS default
            // credential provider chain.
            AwsCredentialsProvider chainProvider = working();
            List<String> profileFactoryCalled = new ArrayList<>();
            Function<String, AwsCredentialsProvider> profileFactory = name -> {
                profileFactoryCalled.add(name);
                return working();
            };

            CredentialResolver resolver = new CredentialResolver(
                    profileFactory, () -> chainProvider, noBearer());

            BedrockCredentials resolved = resolver.resolve(null);

            assertSame(chainProvider, resolved.provider(),
                    "AC-8.7: with no profile, the default-chain provider is selected");
            assertEquals(CredentialResolver.DEFAULT_CHAIN_LABEL, resolved.resolvedVia(),
                    "AC-8.7: the tier label must be the default chain");
            assertTrue(profileFactoryCalled.isEmpty(),
                    "AC-8.7: no profile provider is built when no profile is configured");
        }

        @Test
        @DisplayName("a blank profile is treated as no profile and uses the default chain (AC-8.7)")
        void blankProfile_resolvesViaDefaultChain() {
            // Oracle: AC-8.7 — "no AWS profile configured" includes the empty/blank case;
            // ResolvedConfig.awsProfile() is null when unconfigured, but a blank string
            // is the same "unconfigured" condition and must not be looked up as a profile.
            AwsCredentialsProvider chainProvider = working();

            CredentialResolver resolver = new CredentialResolver(
                    name -> {
                        throw new AssertionError("a blank profile must not be looked up (AC-8.7)");
                    },
                    () -> chainProvider, noBearer());

            BedrockCredentials resolved = resolver.resolve("   ");

            assertSame(chainProvider, resolved.provider(),
                    "AC-8.7: a blank profile falls through to the default chain");
            assertEquals(CredentialResolver.DEFAULT_CHAIN_LABEL, resolved.resolvedVia());
        }

        @Test
        @DisplayName("a configured profile that is not found falls back to the default chain (AC-8.7)")
        void notFoundProfile_fallsBackToDefaultChain() {
            // Oracle: AC-8.7 — "or the configured profile is not found" -> fall back to
            // the default chain rather than failing. The profile probe failing simulates
            // a profile that does not exist / yields no credentials.
            AwsCredentialsProvider chainProvider = working();

            CredentialResolver resolver = new CredentialResolver(
                    name -> failing("profile [" + name + "] not found"),
                    () -> chainProvider, noBearer());

            BedrockCredentials resolved = resolver.resolve("missingProfile");

            assertSame(chainProvider, resolved.provider(),
                    "AC-8.7: a not-found profile must fall back to the default chain, not fail");
            assertEquals(CredentialResolver.DEFAULT_CHAIN_LABEL, resolved.resolvedVia());
        }
    }

    @Nested
    @DisplayName("AC-8.8 / INV-16 / CT-INV-13: SigV4 only; ambient bearer ignored and warned")
    class BearerIgnored {

        @Test
        @DisplayName("CT-INV-13: an ambient bearer token does not change the resolved SigV4 provider")
        void ct_inv_13_bearerPresent_stillUsesSigV4Provider() {
            // Oracle: CT-INV-13 / AC-8.8 / INV-16 — even when AWS_BEARER_TOKEN_BEDROCK is
            // present, the resolver selects the SigV4 provider it was given (profile or
            // chain), never a bearer provider. Verified by asserting the selected provider
            // is the exact SigV4 provider supplied (inspectable seam), not by SDK env
            // pickup. A bearer env value is simulated via the injected environment lookup.
            AwsCredentialsProvider sigV4Provider = working();
            UnaryOperator<String> bearerSet = name ->
                    CredentialResolver.BEARER_TOKEN_ENV_VAR.equals(name) ? "bearer-abc.def.ghi" : null;

            CredentialResolver resolver = new CredentialResolver(
                    name -> sigV4Provider, () -> working(), bearerSet);

            BedrockCredentials resolved = resolver.resolve("awsBedRockProfile");

            assertSame(sigV4Provider, resolved.provider(),
                    "CT-INV-13: the provider must be the supplied SigV4 provider, never bearer-derived");
        }

        @Test
        @DisplayName("CT-INV-13: bearer ignore also holds on the default-chain tier (INV-16)")
        void ct_inv_13_bearerPresent_defaultChainStillSigV4() {
            // Oracle: INV-16 — the SigV4-only invariant holds regardless of which tier
            // resolves; a present bearer token never displaces the default-chain SigV4
            // provider.
            AwsCredentialsProvider chainProvider = working();
            UnaryOperator<String> bearerSet = name ->
                    CredentialResolver.BEARER_TOKEN_ENV_VAR.equals(name) ? "bearer-token" : null;

            CredentialResolver resolver = new CredentialResolver(
                    name -> working(), () -> chainProvider, bearerSet);

            BedrockCredentials resolved = resolver.resolve(null);

            assertSame(chainProvider, resolved.provider(),
                    "INV-16: the default-chain SigV4 provider is used; bearer is never used");
            assertEquals(CredentialResolver.DEFAULT_CHAIN_LABEL, resolved.resolvedVia());
        }

        @Test
        @DisplayName("CT-INV-13: an absent bearer token resolves normally (no warn path obstruction)")
        void ct_inv_13_noBearer_resolvesNormally() {
            // Oracle: AC-8.8 — the SigV4 path is unaffected when no bearer token is set;
            // the bearer-ignore behavior must not perturb the normal resolution.
            AwsCredentialsProvider sigV4Provider = working();

            CredentialResolver resolver = new CredentialResolver(
                    name -> sigV4Provider, () -> working(), noBearer());

            BedrockCredentials resolved = resolver.resolve("awsBedRockProfile");

            assertSame(sigV4Provider, resolved.provider(),
                    "AC-8.8: with no bearer present, the SigV4 provider resolves unchanged");
        }
    }

    @Nested
    @DisplayName("AC-8.9 / CT-EX-2: no usable SigV4 credentials -> typed failure naming paths")
    class NoUsableCredentials {

        @Test
        @DisplayName("CT-EX-2: profile + default chain both unusable -> exception naming both paths")
        void ct_ex_2_bothPathsFail_throwsNamingPaths() {
            // Oracle: AC-8.9 / CT-EX-2 — no usable SigV4 credentials by either path ->
            // failure whose message names the paths attempted (profile name + default
            // chain). The CLI maps this to exit 4 (asserted separately, see
            // CredentialResolutionExceptionTest / ExitCode mapping).
            CredentialResolver resolver = new CredentialResolver(
                    name -> failing("profile [" + name + "] not found"),
                    () -> failing("Unable to load credentials from any of the providers"),
                    noBearer());

            CredentialResolutionException ex = assertThrows(CredentialResolutionException.class,
                    () -> resolver.resolve("awsBedRockProfile"),
                    "AC-8.9: neither path usable must throw CredentialResolutionException");

            assertTrue(ex.getMessage().contains("awsBedRockProfile"),
                    "AC-8.9: the message must name the profile path attempted; was: " + ex.getMessage());
            assertTrue(ex.getMessage().contains(CredentialResolver.DEFAULT_CHAIN_LABEL),
                    "AC-8.9: the message must name the default chain path attempted; was: "
                            + ex.getMessage());
        }

        @Test
        @DisplayName("CT-EX-2: no profile configured + default chain unusable -> exception names default chain")
        void ct_ex_2_noProfileAndChainFails_throwsNamingDefaultChain() {
            // Oracle: AC-8.9 — when no profile is configured, the only path attempted is
            // the default chain; the message must name it.
            CredentialResolver resolver = new CredentialResolver(
                    name -> working(),
                    () -> failing("Unable to load credentials"),
                    noBearer());

            CredentialResolutionException ex = assertThrows(CredentialResolutionException.class,
                    () -> resolver.resolve(null),
                    "AC-8.9: no profile and an unusable default chain must throw");

            assertTrue(ex.getMessage().contains(CredentialResolver.DEFAULT_CHAIN_LABEL),
                    "AC-8.9: the message must name the default chain; was: " + ex.getMessage());
        }

        @Test
        @DisplayName("CT-EX-2: the original SDK failure is chained as the cause (debuggability)")
        void ct_ex_2_chainsUnderlyingCause() {
            // Oracle: AC-8.9 names the paths; the debugging tenet (java.md error handling)
            // requires chaining the underlying SDK cause rather than swallowing it.
            SdkClientException chainFailure =
                    SdkClientException.create("Unable to load credentials from any of the providers");
            CredentialResolver resolver = new CredentialResolver(
                    name -> failing("profile not found"),
                    () -> {
                        throw chainFailure;
                    },
                    noBearer());

            CredentialResolutionException ex = assertThrows(CredentialResolutionException.class,
                    () -> resolver.resolve("p"));

            assertSame(chainFailure, ex.getCause(),
                    "the underlying SDK failure must be chained as the cause (debuggability)");
        }
    }

    @Test
    @DisplayName("the production no-arg constructor wires real SigV4 factories (smoke)")
    void noArgConstructor_isConstructible() {
        // Oracle: ADR-0011 — the resolver wires SigV4 (profile + default-chain) factories
        // by default; constructing it must not require AWS access. Resolution itself is
        // exercised hermetically by the injected-factory tests above; here we only assert
        // the production wiring is constructible (it must not eagerly touch ~/.aws).
        CredentialResolver resolver = new CredentialResolver();

        assertNotNull(resolver, "the default resolver must be constructible without AWS access");
    }
}
