package com.srk.codingagent.model;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static prefix-keyed capability registry (component C5, ADR-0002): resolves a Bedrock
 * {@code modelId} to its {@link ModelCapabilityProfile}. This is the single seam ADR-0002 names
 * — "Resolution: modelId &rarr; profile via a static registry keyed by model-id prefix, with a
 * conservative default profile for unknown ids" — so the loop and the context manager consult a
 * resolved profile instead of branching on the model id, and a future provider bring-up is "a
 * registry entry + a validation pass, not a code rewrite".
 *
 * <p><b>v1 ships only Claude (ANTHROPIC) profiles populated (ADR-0002, NFR-MODEL-PROVIDER).</b>
 * The registry is intentionally thin: a single populated family (Claude) plus the conservative
 * default. The two Claude entries differ only in their prompt-cache checkpoint minimum, the one
 * model-specific figure ADR-0002 calls out ("Opus 4.5/4.6 need &ge; 4096 tokens/checkpoint,
 * others 1024"):
 * <ul>
 *   <li>a Claude <b>Opus</b> id (the configured default {@code us.anthropic.claude-opus-4-8})
 *       resolves to a profile whose prompt cache requires {@code >= 4096} tokens per checkpoint;</li>
 *   <li>any other Claude id (Sonnet / Haiku) resolves to the same profile but with the
 *       {@code 1024}-token checkpoint minimum.</li>
 * </ul>
 * Both report {@link ProviderFamily#ANTHROPIC}, extended thinking, tool use, image + document
 * input, and {@code top_k} inference-param passthrough (the Claude-only Converse parameter
 * ADR-0002 names). Non-Claude ids are the seam: they resolve through the conservative default
 * ({@link ProviderFamily#OTHER}) until a profile + validation pass is added for that family.
 *
 * <p><b>The conservative default (ADR-0002 / &sect; 2.6).</b> An unknown id is never failed; it
 * degrades to a profile with no extended thinking, no prompt cache ({@code null}), tool-use
 * assumed {@code true}, no image/document input, an empty inference-param passthrough set, and
 * the supplied safe-minimum window — "correctness over optimization for unvalidated models". The
 * resolution is OFFLINE by design (ADR-0002 rejected runtime Bedrock capability queries): it is a
 * pure function of the model id and the fallback window, so it is fully unit-testable without any
 * AWS call.
 *
 * <p>Constants holder; not instantiable.
 */
public final class ModelCapabilityRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelCapabilityRegistry.class);

    /**
     * The model-id infix every v1 Claude profile is keyed on. Both the bare id
     * ({@code anthropic.claude-...}) and the cross-region inference-profile id
     * ({@code us.anthropic.claude-...}, the configured default — {@code ConfigDefaults.MODEL_ID})
     * contain it, so keying on the infix resolves the live default model to the real Claude
     * profile rather than the conservative fallback.
     */
    private static final String CLAUDE_MODEL_ID_INFIX = "anthropic.claude";

    /**
     * The Claude-Opus discriminator within a Claude id. ADR-0002 pins the higher prompt-cache
     * checkpoint minimum to Opus 4.5/4.6 ("&ge; 4096 tokens/checkpoint"); the v1 default model
     * {@code us.anthropic.claude-opus-4-8} is an Opus, so an id whose Claude segment names
     * {@code opus} takes the {@code 4096}-token minimum.
     */
    private static final String CLAUDE_OPUS_DISCRIMINATOR = "claude-opus";

    /**
     * The effective input-token window for the Claude 4-family models v1 ships profiles for. 200K
     * tokens is the published 4-family input window; the figure is a property of the model (not an
     * operator knob), so it lives here rather than in config — v1 ships only Claude profiles
     * populated (ADR-0002).
     */
    static final int CLAUDE_4_FAMILY_CONTEXT_WINDOW_TOKENS = 200_000;

    /**
     * The prompt-cache checkpoint minimum for Claude <b>Opus</b> 4.x (ADR-0002 "Opus 4.5/4.6 need
     * &ge; 4096 tokens/checkpoint").
     */
    static final int CLAUDE_OPUS_MIN_TOKENS_PER_CHECKPOINT = 4096;

    /**
     * The prompt-cache checkpoint minimum for non-Opus Claude (ADR-0002 "others 1024").
     */
    static final int CLAUDE_NON_OPUS_MIN_TOKENS_PER_CHECKPOINT = 1024;

    /**
     * The maximum number of prompt-cache checkpoints Claude permits. ADR-0002 names "checkpoint
     * counts" as a model-specific figure but does not pin an integer; this is the verified
     * Anthropic-on-Bedrock figure (4 cache checkpoints), carried as a compiled-in registry datum.
     */
    static final int CLAUDE_MAX_CACHE_CHECKPOINTS = 4;

    /**
     * The single {@code additionalModelRequestFields} key valid for Claude (ADR-0002 "inference
     * params (top_k via additionalModelRequestFields, Claude, not universal)"). The conservative
     * default carries an empty passthrough set — an unvalidated model gets no param passthrough.
     */
    static final String CLAUDE_INFERENCE_PARAM_TOP_K = "top_k";

    private ModelCapabilityRegistry() {
        // Constants / resolution holder; not instantiable.
    }

    /**
     * Resolves the capability profile for a model id via the static prefix registry (ADR-0002). A
     * known Claude id resolves to the Claude family profile (the Opus variant when the id names
     * {@code claude-opus}, else the Sonnet/Haiku variant); an unknown id resolves to the
     * conservative default profile carrying the supplied safe-minimum window.
     *
     * <p>This is the resolution every caller (the budget guard's window, the attachment pipeline's
     * INV-19 gate, a sub-agent's independently-resolved override model) funnels through, so a
     * non-default configured {@code modelId} — including a sub-agent's cheaper override
     * (NFR-MODEL-SUBAGENT) — resolves its own profile through the same seam.
     *
     * @param modelId              the active Bedrock model id (bare or {@code us.}
     *                             inference-profile form); must not be {@code null}.
     * @param fallbackWindowTokens the safe-minimum window for an unknown id (ADR-0002 "a safe
     *                             minimum context window"); {@code >= 1}.
     * @return the profile for {@code modelId}; never {@code null}.
     * @throws NullPointerException     if {@code modelId} is {@code null}.
     * @throws IllegalArgumentException if {@code fallbackWindowTokens < 1}.
     */
    public static ModelCapabilityProfile resolve(String modelId, int fallbackWindowTokens) {
        Objects.requireNonNull(modelId, "modelId");
        if (fallbackWindowTokens < 1) {
            throw new IllegalArgumentException(
                    "fallbackWindowTokens must be >= 1 (was " + fallbackWindowTokens + ")");
        }
        if (modelId.contains(CLAUDE_MODEL_ID_INFIX)) {
            return claudeProfile(modelId);
        }
        return conservativeDefault(modelId, fallbackWindowTokens);
    }

    /**
     * Builds the populated Claude (ANTHROPIC) profile (ADR-0002, &sect; 2.3 verified Converse
     * facts): extended thinking with a configurable budget, tool use, image + document input, the
     * 200K 4-family window, {@code top_k} inference-param passthrough, and a prompt cache whose
     * checkpoint minimum is the Opus figure when the id names {@code claude-opus}, else the
     * non-Opus figure (ADR-0002 "Opus 4.5/4.6 need &ge; 4096 tokens/checkpoint, others 1024").
     */
    private static ModelCapabilityProfile claudeProfile(String modelId) {
        int minTokensPerCheckpoint = modelId.contains(CLAUDE_OPUS_DISCRIMINATOR)
                ? CLAUDE_OPUS_MIN_TOKENS_PER_CHECKPOINT
                : CLAUDE_NON_OPUS_MIN_TOKENS_PER_CHECKPOINT;
        PromptCacheCaps promptCache = new PromptCacheCaps(
                minTokensPerCheckpoint,
                CLAUDE_MAX_CACHE_CHECKPOINTS,
                List.of(PromptCacheCaps.TimeToLive.FIVE_MINUTES, PromptCacheCaps.TimeToLive.ONE_HOUR));
        return new ModelCapabilityProfile(
                ProviderFamily.ANTHROPIC,
                CLAUDE_4_FAMILY_CONTEXT_WINDOW_TOKENS,
                /* supportsExtendedThinking */ true,
                /* thinkingBudgetConfigurable */ true,
                /* supportsToolUse */ true,
                /* supportsImageInput */ true,
                /* supportsDocumentInput */ true,
                promptCache,
                List.of(CLAUDE_INFERENCE_PARAM_TOP_K));
    }

    /**
     * Builds the conservative default profile for an unknown id (ADR-0002 / &sect; 2.6): provider
     * family {@link ProviderFamily#OTHER}, no extended thinking, no prompt cache, tool-use assumed
     * {@code true}, no image/document input, an empty inference-param passthrough set, and the
     * supplied safe-minimum window. An unknown id is degraded, not failed (graceful degradation).
     */
    private static ModelCapabilityProfile conservativeDefault(String modelId, int fallbackWindowTokens) {
        // ADR-0002: feature-detection over assumption — an unknown id is not failed, it degrades
        // to the conservative default profile (correctness over optimization for unvalidated
        // models). The fallback window keeps the compaction threshold computable; the optional
        // capabilities are all off, so the loop uses none of them yet still runs.
        LOGGER.info("No capability profile for model id '{}'; using conservative default profile "
                + "(window {} tokens, no extended thinking, no prompt cache, no image/document input)",
                modelId, fallbackWindowTokens);
        return new ModelCapabilityProfile(
                ProviderFamily.OTHER,
                fallbackWindowTokens,
                /* supportsExtendedThinking */ false,
                /* thinkingBudgetConfigurable */ false,
                /* supportsToolUse */ true,
                /* supportsImageInput */ false,
                /* supportsDocumentInput */ false,
                /* promptCache */ null,
                List.of());
    }
}
