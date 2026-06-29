package com.srk.codingagent.model;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The model-capability profile (component C5, ADR-0002): the abstraction the loop and the
 * context manager consult instead of branching on {@code modelId} directly. A profile
 * resolves a model id to the capabilities the rest of the system needs, so callers ask the
 * profile ({@code profile.contextWindowTokens()}) rather than testing
 * {@code if (modelId.contains("claude"))}.
 *
 * <p><b>v1 scope (T-2.1, extended T-4.2).</b> ADR-0002 ships an intentionally <em>thin</em>
 * abstraction in v1 — a seam plus Claude profiles, not a full multi-provider matrix. T-2.1
 * carried only {@link #contextWindowTokens()} (the figure ADR-0006's compaction trigger divides
 * by 0.85). T-4.2 adds the two multimodal capability flags this milestone's attachment pipeline
 * needs — {@link #supportsImageInput()} / {@link #supportsDocumentInput()} (03-data-model.md
 * &sect; 2.6) — which gate whether an {@code ImageBlock}/{@code DocumentBlock} attachment may be
 * sent (INV-19). The rest of the &sect; 2.6 shape ({@code providerFamily},
 * {@code supportsExtendedThinking}, {@code promptCache}, {@code supportsToolUse},
 * inference-param passthrough) plus feature detection and full schema validation remains
 * <b>T-4.3</b>'s job (cites CT-SCH-15, NFR-MODEL-PROVIDER, OQ-J). The record stays
 * forward-compatible: T-4.3 extends it with the remaining fields without reworking the callers
 * that already read the window / multimodal flags through this seam.
 *
 * <p><b>Resolution (ADR-0002).</b> {@link #forModelId(String, int)} maps a model id to a
 * profile via a static registry keyed by <b>model-id prefix</b>. A known prefix (v1: Claude
 * Opus / Sonnet / Haiku) yields that family's real window <em>and</em> image/document input
 * support (Claude accepts both per the verified Converse facts, &sect; 2.3); an unknown id
 * yields a <b>conservative default profile</b> whose window is the supplied safe-minimum
 * fallback and whose multimodal flags are <b>false</b> ("no image/document input", &sect; 2.6),
 * so an attachment degrades gracefully rather than failing the call (INV-19). The registry is
 * the single seam where future providers are added — a new prefix entry, not a code rewrite.
 *
 * <p>Immutable value object (Effective Java Item 17); one instance is safely shared.
 *
 * @param contextWindowTokens   the model's effective input-token window — the figure the
 *                              compaction threshold (NFR-CONTEXT-COMPACT-THRESHOLD,
 *                              ADR-0006) is taken as a fraction of; {@code >= 1}.
 * @param supportsImageInput    whether the model accepts {@code ImageBlock} multimodal input
 *                              (&sect; 2.6); when false the attachment pipeline declines an
 *                              image attachment with a message rather than sending it (INV-19).
 * @param supportsDocumentInput whether the model accepts {@code DocumentBlock} multimodal input
 *                              (&sect; 2.6); when false a document attachment is declined (INV-19).
 */
public record ModelCapabilityProfile(
        int contextWindowTokens, boolean supportsImageInput, boolean supportsDocumentInput) {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCapabilityProfile.class);

    /**
     * The effective input-token window for the Claude Opus / Sonnet / Haiku 4-family models
     * v1 ships profiles for (the default model id is the {@code us.anthropic.claude-opus-4-8}
     * inference profile — ConfigDefaults / ADR-0001). 200K tokens is the published 4-family
     * input window; the figure lives here (not in config) because v1 ships only Claude
     * profiles populated and the per-family window is a property of the model, not an
     * operator knob (ADR-0002 "v1 ships only Claude profiles populated"). T-4.3 may move this
     * into the full registry entry it builds out.
     */
    static final int CLAUDE_4_FAMILY_CONTEXT_WINDOW_TOKENS = 200_000;

    /**
     * The model-id prefix the known Claude profiles are keyed on. Both the bare id
     * ({@code anthropic.claude-...}) and the cross-region inference-profile id
     * ({@code us.anthropic.claude-...}, the configured default — ConfigDefaults.MODEL_ID)
     * contain this substring, so keying on it resolves the live default model to the real
     * Claude window rather than the conservative fallback.
     */
    private static final String CLAUDE_MODEL_ID_INFIX = "anthropic.claude";

    /**
     * Validates the profile invariant.
     *
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1}.
     */
    public ModelCapabilityProfile {
        if (contextWindowTokens < 1) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be >= 1 (was " + contextWindowTokens + ")");
        }
    }

    /**
     * Creates a profile carrying only the context window, with both multimodal flags
     * <b>false</b> — the conservative default for a model whose multimodal support is unknown
     * (&sect; 2.6 "no image/document input"). Used by callers that only need the window seam and
     * by the unknown-model fallback path.
     *
     * @param contextWindowTokens the model's effective input-token window; {@code >= 1}.
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1}.
     */
    public ModelCapabilityProfile(int contextWindowTokens) {
        this(contextWindowTokens, false, false);
    }

    /**
     * Resolves the capability profile for a model id (ADR-0002 static prefix registry). A
     * known Claude id resolves to the Claude family window; an unknown id resolves to the
     * conservative default profile carrying the supplied safe-minimum window.
     *
     * @param modelId             the active Bedrock model id (bare or {@code us.}
     *                            inference-profile form); must not be {@code null}.
     * @param fallbackWindowTokens the safe-minimum window for an unknown id (ADR-0002 "a
     *                            safe minimum context window"); {@code >= 1}.
     * @return the profile for {@code modelId}; never {@code null}.
     * @throws NullPointerException     if {@code modelId} is {@code null}.
     * @throws IllegalArgumentException if {@code fallbackWindowTokens < 1}.
     */
    public static ModelCapabilityProfile forModelId(String modelId, int fallbackWindowTokens) {
        Objects.requireNonNull(modelId, "modelId");
        if (fallbackWindowTokens < 1) {
            throw new IllegalArgumentException(
                    "fallbackWindowTokens must be >= 1 (was " + fallbackWindowTokens + ")");
        }
        if (modelId.contains(CLAUDE_MODEL_ID_INFIX)) {
            // ADR-0002 / § 2.3: the Claude 4-family accepts both image and document input
            // (verified Converse facts), so the resolved Claude profile reports both flags true
            // and the attachment pipeline sends the multimodal blocks (INV-19).
            return new ModelCapabilityProfile(CLAUDE_4_FAMILY_CONTEXT_WINDOW_TOKENS, true, true);
        }
        // ADR-0002: feature-detection over assumption — an unknown id is not failed, it
        // degrades to the conservative default profile (correctness over optimization for
        // unvalidated models). The fallback window keeps the compaction threshold computable;
        // both multimodal flags are false (§ 2.6 "no image/document input"), so an attachment to
        // an unknown model is declined with a message rather than sent (INV-19, graceful degrade).
        LOGGER.info("No capability profile for model id '{}'; using conservative default "
                + "window of {} tokens (no image/document input)", modelId, fallbackWindowTokens);
        return new ModelCapabilityProfile(fallbackWindowTokens, false, false);
    }
}
