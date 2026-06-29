package com.srk.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.ConfigDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelCapabilityRegistry} — the C5 prefix-keyed static resolution seam
 * (ADR-0002, OQ-J). The SUT is the real registry + the real {@link ModelCapabilityProfile} it
 * produces; nothing is mocked (resolution is OFFLINE by design — a pure function of model id +
 * fallback window, ADR-0002 rejected runtime Bedrock capability queries).
 *
 * <p><b>Oracles (each expectation traces to a cited spec symbol, never to the SUT's code):</b>
 * <ul>
 *   <li><b>ADR-0002</b> — resolution is a static registry keyed by model-id <em>prefix</em>; v1
 *       ships only Claude (ANTHROPIC) profiles populated; an unknown id yields a conservative
 *       default (provider OTHER, no extended thinking, no prompt cache, tool-use assumed,
 *       no image/document input, safe-minimum window); feature-detection over assumption with
 *       graceful degradation; the model-specific prompt-cache checkpoint minimum is "Opus 4.5/4.6
 *       need &ge; 4096 tokens/checkpoint, others 1024"; {@code top_k} is the Claude-only
 *       inference-param passthrough.</li>
 *   <li><b>03-data-model.md &sect; 2.6</b> — the field shape + the conservative-default semantics
 *       ("no thinking, no cache, no image/document input, tool-use assumed, safe window").</li>
 *   <li><b>NFR-MODEL-DEFAULT / ADR-0001</b> — the live default is {@code us.anthropic.claude-opus-4-8}
 *       and must resolve to a real Claude profile (not the fallback).</li>
 *   <li><b>NFR-MODEL-SUBAGENT</b> — a non-default configured model id (e.g. a sub-agent's cheaper
 *       override) resolves its own profile independently through the same registry.</li>
 * </ul>
 */
class ModelCapabilityRegistryTest {

    /**
     * A fallback window distinct from the real Claude window, so "fallback used" vs "real Claude
     * window used" cannot pass by coincidence. Not a spec literal — the spec only requires the
     * fallback be the supplied safe-minimum, so the oracle is "equals what we passed in".
     */
    private static final int DISTINCT_FALLBACK_WINDOW = 12_345;

    @Nested
    @DisplayName("ADR-0002: v1 Claude (ANTHROPIC) profile is populated for a known Claude id")
    class ClaudeProfilePopulated {

        @Test
        @DisplayName("the live default model id resolves to the ANTHROPIC family, not the fallback "
                + "(ADR-0001 / ADR-0002)")
        void liveDefault_resolvesToAnthropicFamily() {
            // Oracle: ADR-0001/NFR-MODEL-DEFAULT pins the live default to the us. inference-profile
            // id; ADR-0002 ships Claude profiles populated and tags them ANTHROPIC (§ 2.6). The
            // default must hit the Claude entry, so the family is ANTHROPIC and the window is not
            // the conservative fallback.
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.ANTHROPIC, profile.providerFamily(),
                    "the live Claude default resolves to the ANTHROPIC family (ADR-0002 § 2.6)");
            assertNotEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "a known Claude id must not use the conservative fallback window (ADR-0002)");
            assertTrue(profile.contextWindowTokens() > 0,
                    "a resolved window is a positive token count (03-data-model § 2.6)");
        }

        @Test
        @DisplayName("the Claude profile feature-detects extended thinking, tool use, and multimodal "
                + "input as supported (ADR-0002 / § 2.6)")
        void claudeProfile_reportsOptionalCapabilitiesTrue() {
            // Oracle: ADR-0002 — extended thinking is a Claude (model-gated) capability; tool-use
            // is supported; § 2.3/§ 2.6 — Claude accepts image + document input. The Claude profile
            // reports each true so the loop may use the feature.
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);

            assertTrue(profile.supportsExtendedThinking(),
                    "ADR-0002: Claude supports extended thinking");
            assertTrue(profile.thinkingBudgetConfigurable(),
                    "ADR-0002: Claude's thinking budget is configurable");
            assertTrue(profile.supportsToolUse(),
                    "ADR-0002: Claude supports tool use");
            assertTrue(profile.supportsImageInput(),
                    "§ 2.6: Claude accepts image input");
            assertTrue(profile.supportsDocumentInput(),
                    "§ 2.6: Claude accepts document input");
        }

        @Test
        @DisplayName("the Claude profile carries a prompt cache with top_k inference-param "
                + "passthrough (ADR-0002)")
        void claudeProfile_carriesPromptCacheAndTopKPassthrough() {
            // Oracle: ADR-0002 — Claude supports prompt caching (promptCache present, not null =
            // unsupported, § 2.6) and exposes top_k via additionalModelRequestFields ("inference
            // params (top_k via additionalModelRequestFields, Claude, not universal)").
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);

            assertNotNull(profile.promptCache(),
                    "ADR-0002: Claude supports prompt caching (promptCache not null)");
            assertTrue(profile.inferenceParamPassthrough().contains("top_k"),
                    "ADR-0002: top_k is a valid Claude additionalModelRequestFields key");
        }

        @Test
        @DisplayName("a Claude OPUS id requires >= 4096 tokens/checkpoint; a non-Opus Claude id "
                + "requires 1024 (ADR-0002 model-specific minimum)")
        void claudePromptCache_checkpointMinimumIsModelSpecific() {
            // Oracle: ADR-0002 Context — "prompt-caching token minimums ... Opus 4.5/4.6 need
            // >= 4096 tokens/checkpoint, others 1024". The Opus default and a Sonnet/Haiku id
            // resolve to the respective minima (the 4096/1024 figures ARE pinned by ADR-0002).
            ModelCapabilityProfile opus = ModelCapabilityRegistry.resolve(
                    "us.anthropic.claude-opus-4-8", DISTINCT_FALLBACK_WINDOW);
            ModelCapabilityProfile haiku = ModelCapabilityRegistry.resolve(
                    "us.anthropic.claude-haiku-4-5-20251001-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertEquals(4096, opus.promptCache().minTokensPerCheckpoint(),
                    "ADR-0002: Claude Opus requires >= 4096 tokens per cache checkpoint");
            assertEquals(1024, haiku.promptCache().minTokensPerCheckpoint(),
                    "ADR-0002: non-Opus Claude requires 1024 tokens per cache checkpoint");
        }

        @Test
        @DisplayName("the bare and us. Claude Opus forms resolve to the same profile (ADR-0002 "
                + "prefix keying)")
        void bareAndInferenceProfileForms_resolveSameProfile() {
            // Oracle: ADR-0002 keys on the model-id prefix; both the bare anthropic.claude... and
            // the us.anthropic.claude... forms share the infix, so both resolve to the same Claude
            // profile (same family + window).
            ModelCapabilityProfile bare = ModelCapabilityRegistry.resolve(
                    "anthropic.claude-opus-4-8", DISTINCT_FALLBACK_WINDOW);
            ModelCapabilityProfile inferenceProfile = ModelCapabilityRegistry.resolve(
                    "us.anthropic.claude-opus-4-8", DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.ANTHROPIC, bare.providerFamily(),
                    "the bare Claude form keys to the Claude (ANTHROPIC) entry (ADR-0002 prefix)");
            assertEquals(inferenceProfile.contextWindowTokens(), bare.contextWindowTokens(),
                    "bare and us. Claude Opus forms resolve to the same window (ADR-0002 prefix)");
            assertEquals(inferenceProfile.promptCache().minTokensPerCheckpoint(),
                    bare.promptCache().minTokensPerCheckpoint(),
                    "bare and us. Claude Opus forms resolve to the same prompt-cache minimum");
        }
    }

    @Nested
    @DisplayName("ADR-0002 / § 2.6: an unknown id degrades to the conservative default profile")
    class ConservativeDefault {

        @Test
        @DisplayName("an unknown id resolves to provider OTHER with the supplied safe-minimum window "
                + "(ADR-0002 conservative default)")
        void unknownId_resolvesToOtherFamilyWithFallbackWindow() {
            // Oracle: ADR-0002 — unknown ids get a conservative default profile (provider OTHER,
            // § 2.6) whose window is "a safe minimum context window" (the supplied fallback here).
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    "meta.llama3-70b-instruct-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.OTHER, profile.providerFamily(),
                    "an unknown id resolves to the OTHER family (ADR-0002 / § 2.6)");
            assertEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "an unknown id falls back to the supplied safe-minimum window (ADR-0002)");
        }

        @Test
        @DisplayName("the conservative default reports NO extended thinking, NO prompt cache, NO "
                + "image/document input — graceful degradation (ADR-0002 / § 2.6)")
        void conservativeDefault_optionalCapabilitiesAbsent() {
            // Oracle: § 2.6 — "Unknown modelId -> a conservative default profile (no thinking, no
            // cache, no image/document input ...)". The absent capabilities are reported off so the
            // loop uses none of them (graceful degradation) — the "degrade when capability absent"
            // Verify criterion for this task.
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    "meta.llama3-70b-instruct-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertFalse(profile.supportsExtendedThinking(),
                    "§ 2.6: the conservative default has no extended thinking");
            assertFalse(profile.thinkingBudgetConfigurable(),
                    "§ 2.6: a no-thinking default has no configurable thinking budget");
            assertNull(profile.promptCache(),
                    "§ 2.6: the conservative default has no prompt cache (null = unsupported)");
            assertFalse(profile.supportsImageInput(),
                    "§ 2.6: the conservative default has no image input");
            assertFalse(profile.supportsDocumentInput(),
                    "§ 2.6: the conservative default has no document input");
        }

        @Test
        @DisplayName("the conservative default still ASSUMES tool use and keeps running (ADR-0002 "
                + "tool-use assumed)")
        void conservativeDefault_assumesToolUseAndStillRuns() {
            // Oracle: ADR-0002 / § 2.6 — the conservative default has "tool-use assumed". The loop
            // still runs (the profile is produced, the window is computable); only the absent
            // optional features are skipped.
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    "unknown-provider.some-model", DISTINCT_FALLBACK_WINDOW);

            assertTrue(profile.supportsToolUse(),
                    "ADR-0002: tool use is assumed even for an unvalidated model");
            assertTrue(profile.inferenceParamPassthrough().isEmpty(),
                    "ADR-0002: an unvalidated model gets no inference-param passthrough");
            assertTrue(profile.contextWindowTokens() > 0,
                    "the window is a positive count so the compaction threshold stays computable");
        }

        @Test
        @DisplayName("an id mentioning 'anthropic' WITHOUT the claude infix is unknown (ADR-0002 "
                + "prefix match)")
        void anthropicNonClaudeId_isUnknown() {
            // Oracle: ADR-0002 keys on the anthropic.claude prefix specifically; an id without that
            // infix is not a v1-shipped Claude profile and degrades to the conservative default.
            ModelCapabilityProfile profile = ModelCapabilityRegistry.resolve(
                    "anthropic.titan-text-v1", DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.OTHER, profile.providerFamily(),
                    "an anthropic non-claude id is not a populated v1 profile (ADR-0002)");
        }
    }

    @Nested
    @DisplayName("NFR-MODEL-SUBAGENT: a non-default configured model id resolves its own profile")
    class NonDefaultModelSwap {

        @Test
        @DisplayName("a non-default Claude model id resolves a Claude profile independently of the "
                + "default (NFR-MODEL-SUBAGENT)")
        void nonDefaultClaudeId_resolvesIndependently() {
            // Oracle: NFR-MODEL-SUBAGENT — "a sub-agent may be configured to a cheaper/faster
            // model"; ADR-0002 — it "resolves its own profile independently via the same registry".
            // A configured non-default Claude (Haiku, the cheaper family) resolves a Claude profile
            // on its own, not by inheriting the Opus default's.
            ModelCapabilityProfile subAgent = ModelCapabilityRegistry.resolve(
                    "us.anthropic.claude-haiku-4-5-20251001-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.ANTHROPIC, subAgent.providerFamily(),
                    "a non-default Claude id resolves its own Claude profile (NFR-MODEL-SUBAGENT)");
            assertNotEquals(DISTINCT_FALLBACK_WINDOW, subAgent.contextWindowTokens(),
                    "a known non-default Claude id resolves a real window, not the fallback");
        }

        @Test
        @DisplayName("a non-default NON-Claude model id resolves the conservative default "
                + "independently (NFR-MODEL-SUBAGENT + ADR-0002 seam)")
        void nonDefaultNonClaudeId_resolvesConservativeDefault() {
            // Oracle: NFR-MODEL-SUBAGENT — a sub-agent "may differ in provider, subject to
            // NFR-MODEL-PROVIDER capability detection"; ADR-0002 — a non-Claude family is the seam,
            // resolving the conservative default until a profile is added. Swapping to a non-Claude
            // model yields the OTHER-family default, distinct from the Opus default's ANTHROPIC.
            ModelCapabilityProfile defaultProfile = ModelCapabilityRegistry.resolve(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);
            ModelCapabilityProfile swapped = ModelCapabilityRegistry.resolve(
                    "amazon.nova-pro-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertEquals(ProviderFamily.ANTHROPIC, defaultProfile.providerFamily(),
                    "guard: the default model is the populated Claude family");
            assertEquals(ProviderFamily.OTHER, swapped.providerFamily(),
                    "a non-Claude swap resolves the conservative default independently (ADR-0002 seam)");
        }
    }

    @Nested
    @DisplayName("ADR-0002 resolution invariants")
    class ResolutionInvariants {

        @Test
        @DisplayName("a null model id is rejected (resolution is keyed on the model id)")
        void nullModelId_rejected() {
            // Oracle: ADR-0002 — resolution is keyed on the model id; a null id has nothing to
            // resolve and is a programming error.
            assertThrows(NullPointerException.class,
                    () -> ModelCapabilityRegistry.resolve(null, DISTINCT_FALLBACK_WINDOW),
                    "a null model id cannot be resolved");
        }

        @Test
        @DisplayName("a non-positive fallback window is rejected (safe-minimum must be positive)")
        void nonPositiveFallback_rejected() {
            // Oracle: ADR-0002 — the conservative window is a "safe minimum"; a zero/negative
            // minimum would make the compaction threshold uncomputable, so it is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> ModelCapabilityRegistry.resolve("unknown-model", 0),
                    "a zero fallback window is invalid");
            assertThrows(IllegalArgumentException.class,
                    () -> ModelCapabilityRegistry.resolve("unknown-model", -100),
                    "a negative fallback window is invalid");
        }
    }
}
