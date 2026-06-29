package com.srk.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.srk.codingagent.config.ConfigDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelCapabilityProfile} — the C5 capability seam (ADR-0002) that
 * resolves a {@code modelId} to the {@code contextWindowTokens} the compaction trigger
 * (ADR-0006) needs. The SUT is the real profile + its static registry; nothing is mocked.
 *
 * <p><b>Oracles (each expectation traces to a cited spec symbol, never to the SUT's code):</b>
 * <ul>
 *   <li><b>ADR-0002</b> — resolution is a static registry keyed by <em>model-id prefix</em>;
 *       an unknown id yields a conservative default profile with a safe-minimum window read
 *       as the supplied fallback; v1 ships only Claude profiles populated.</li>
 *   <li><b>ADR-0001 / NFR-MODEL-DEFAULT</b> — the live default model id is the cross-region
 *       inference-profile form {@code us.anthropic.claude-opus-4-8}; it must resolve to a real
 *       Claude window, not the conservative fallback.</li>
 *   <li><b>03-data-model.md &sect; 2.6</b> — {@code contextWindowTokens} is the field that
 *       drives the compaction threshold; it must be a positive token count.</li>
 * </ul>
 */
class ModelCapabilityProfileTest {

    /**
     * A fallback window distinct from any real Claude window, so a test that asserts "fallback
     * used" vs "real Claude window used" cannot pass by coincidence. The value itself is not a
     * spec literal — the spec only requires the fallback be the supplied safe-minimum, so the
     * oracle is "the returned window equals what we passed in", not a magic number.
     */
    private static final int DISTINCT_FALLBACK_WINDOW = 12_345;

    @Nested
    @DisplayName("ADR-0002: known Claude ids resolve to the real Claude window (not the fallback)")
    class KnownClaudeIds {

        @Test
        @DisplayName("the live default model id resolves to a real Claude window, not fallback "
                + "(ADR-0001 / ADR-0002)")
        void liveDefaultModelId_resolvesToRealClaudeWindow() {
            // Oracle: ADR-0001/NFR-MODEL-DEFAULT pins the live default to the us.-prefixed
            // inference-profile id; ADR-0002 says v1 ships Claude profiles populated, so the
            // default must hit the registry (a real Claude window), not the conservative
            // fallback. We prove "not fallback" by passing a fallback the result must NOT equal.
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);

            assertEquals("us.anthropic.claude-opus-4-8", ConfigDefaults.MODEL_ID,
                    "guard: the live default is the us. inference-profile form (ADR-0001)");
            // Oracle is the spec property, not the literal window value (the spec does not pin
            // a number for the Claude window — the prompt only requires the default resolve to
            // a REAL Claude window, "not the conservative fallback"). So: must not equal the
            // distinct fallback, and must be a positive token count (the compaction divisor).
            assertNotEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "the us. Claude Opus default must resolve to the real Claude window, NOT "
                            + "the conservative fallback (ADR-0002 v1 Claude profiles)");
            assertTrue(profile.contextWindowTokens() > 0,
                    "a resolved window is a positive token count (03-data-model § 2.6)");
        }

        @Test
        @DisplayName("the bare Claude Opus id resolves to the same real Claude window (ADR-0002)")
        void bareClaudeId_resolvesToRealClaudeWindow() {
            // Oracle: ADR-0002 keys on the model-id prefix; both the bare and the us. forms
            // share the anthropic.claude infix, so both resolve to the Claude family window.
            ModelCapabilityProfile bare = ModelCapabilityProfile.forModelId(
                    "anthropic.claude-opus-4-8", DISTINCT_FALLBACK_WINDOW);
            ModelCapabilityProfile inferenceProfile = ModelCapabilityProfile.forModelId(
                    "us.anthropic.claude-opus-4-8", DISTINCT_FALLBACK_WINDOW);

            // Oracle: ADR-0002 prefix registry — both forms share the anthropic.claude infix,
            // so both resolve to the same (real, non-fallback) Claude window.
            assertNotEquals(DISTINCT_FALLBACK_WINDOW, bare.contextWindowTokens(),
                    "a known Claude id must not use the conservative fallback (ADR-0002)");
            assertEquals(inferenceProfile.contextWindowTokens(), bare.contextWindowTokens(),
                    "the bare and us. Claude Opus forms resolve to the same window "
                            + "(ADR-0002 prefix keying)");
        }

        @Test
        @DisplayName("a Claude Sonnet/Haiku-family id also resolves via the Claude prefix "
                + "(ADR-0002)")
        void otherClaudeFamilyId_resolvesViaPrefix() {
            // Oracle: ADR-0002 — the registry is keyed by prefix, so any anthropic.claude id
            // resolves through the Claude entry (v1's only populated family), not the fallback.
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    "us.anthropic.claude-haiku-4-5-20251001-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertNotEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "a Claude-family id resolves via the anthropic.claude prefix, not the "
                            + "fallback (ADR-0002)");
        }
    }

    @Nested
    @DisplayName("ADR-0002: an unknown id yields the conservative default (safe-minimum window)")
    class UnknownIds {

        @Test
        @DisplayName("an unknown model id uses the supplied conservative fallback window "
                + "(ADR-0002 conservative default)")
        void unknownId_usesConservativeFallbackWindow() {
            // Oracle: ADR-0002 — unknown ids get a conservative default profile whose window is
            // "a safe minimum context window read from config" (the supplied fallback here).
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    "meta.llama3-70b-instruct-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "an unknown id must fall back to the supplied safe-minimum window "
                            + "(ADR-0002 conservative default profile)");
        }

        @Test
        @DisplayName("an id that merely mentions 'anthropic' without the claude infix is unknown "
                + "(ADR-0002 prefix match)")
        void nonClaudeAnthropicId_isUnknown() {
            // Oracle: ADR-0002 keys on the anthropic.claude prefix specifically; an id without
            // that infix is not a v1-shipped Claude profile and degrades to the fallback.
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    "anthropic.titan-text-v1", DISTINCT_FALLBACK_WINDOW);

            assertEquals(DISTINCT_FALLBACK_WINDOW, profile.contextWindowTokens(),
                    "an anthropic non-claude id is not a populated v1 profile (ADR-0002)");
        }
    }

    @Nested
    @DisplayName("§ 2.6: multimodal input flags (supportsImageInput / supportsDocumentInput, INV-19)")
    class MultimodalFlags {

        @Test
        @DisplayName("a Claude id resolves to a profile that supports BOTH image and document input "
                + "(§ 2.3 / § 2.6)")
        void claudeId_supportsImageAndDocumentInput() {
            // Oracle: 03-data-model § 2.3 — "Claude ... Word/Excel attach natively" and image input
            // is load-bearing for design diagrams (US-1); § 2.6 — the Claude profile reports
            // supportsImageInput/supportsDocumentInput true so the attachment pipeline sends the
            // multimodal blocks (INV-19). Resolved via forModelId on the live default id.
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    ConfigDefaults.MODEL_ID, DISTINCT_FALLBACK_WINDOW);

            assertTrue(profile.supportsImageInput(),
                    "§ 2.6: the Claude profile accepts image input (multimodal)");
            assertTrue(profile.supportsDocumentInput(),
                    "§ 2.6: the Claude profile accepts document input (multimodal)");
        }

        @Test
        @DisplayName("an unknown id resolves to a conservative profile with NO image/document input "
                + "(§ 2.6)")
        void unknownId_noMultimodalInput() {
            // Oracle: § 2.6 — "Unknown modelId → a conservative default profile (... no
            // image/document input ...)". The conservative profile reports both multimodal flags
            // false so an attachment to an unknown model is declined, not sent (INV-19).
            ModelCapabilityProfile profile = ModelCapabilityProfile.forModelId(
                    "meta.llama3-70b-instruct-v1:0", DISTINCT_FALLBACK_WINDOW);

            assertFalse(profile.supportsImageInput(),
                    "§ 2.6: the conservative default has no image input");
            assertFalse(profile.supportsDocumentInput(),
                    "§ 2.6: the conservative default has no document input");
        }

        @Test
        @DisplayName("the window-only constructor defaults both multimodal flags to false "
                + "(conservative, § 2.6)")
        void windowOnlyConstructor_defaultsFlagsFalse() {
            // Oracle: § 2.6 — the conservative default has no image/document input. The
            // backward-compatible window-only constructor must default both flags to the
            // conservative false (a caller that only needs the window does not silently opt into
            // multimodal support).
            ModelCapabilityProfile profile = new ModelCapabilityProfile(50_000);

            assertFalse(profile.supportsImageInput(),
                    "§ 2.6: the window-only constructor defaults supportsImageInput to false");
            assertFalse(profile.supportsDocumentInput(),
                    "§ 2.6: the window-only constructor defaults supportsDocumentInput to false");
        }
    }

    @Nested
    @DisplayName("03-data-model.md § 2.6: contextWindowTokens is a positive token count")
    class Invariants {

        @Test
        @DisplayName("a zero or negative window is rejected (contextWindowTokens drives the "
                + "compaction divisor)")
        void nonPositiveWindow_rejected() {
            // Oracle: 03-data-model § 2.6 — contextWindowTokens drives the compaction threshold
            // (a divisor in ADR-0006's 0.85 x window), so it must be a positive token count.
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelCapabilityProfile(0),
                    "a zero context window is not a valid model window");
            assertThrows(IllegalArgumentException.class,
                    () -> new ModelCapabilityProfile(-1),
                    "a negative context window is not a valid model window");
        }

        @Test
        @DisplayName("a non-positive fallback window is rejected by forModelId (safe-minimum "
                + "must be positive)")
        void nonPositiveFallback_rejected() {
            // Oracle: ADR-0002 — the conservative window is a "safe minimum"; a zero/negative
            // minimum would make the threshold uncomputable, so it is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> ModelCapabilityProfile.forModelId("unknown-model", 0),
                    "a zero fallback window is invalid");
            assertThrows(IllegalArgumentException.class,
                    () -> ModelCapabilityProfile.forModelId("unknown-model", -100),
                    "a negative fallback window is invalid");
        }

        @Test
        @DisplayName("a null model id is rejected (forModelId requires a model id)")
        void nullModelId_rejected() {
            // Oracle: ADR-0002 — resolution is keyed on the model id; a null id has nothing to
            // resolve and is a programming error.
            assertThrows(NullPointerException.class,
                    () -> ModelCapabilityProfile.forModelId(null, DISTINCT_FALLBACK_WINDOW),
                    "a null model id cannot be resolved");
        }
    }
}
