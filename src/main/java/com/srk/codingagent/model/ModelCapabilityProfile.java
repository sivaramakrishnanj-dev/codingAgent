package com.srk.codingagent.model;

import java.util.List;
import java.util.Objects;

/**
 * The model-capability profile (component C5, ADR-0002): the abstraction the loop and the
 * context manager consult instead of branching on {@code modelId} directly. A profile resolves
 * a model id to the capabilities the rest of the system needs, so callers ask the profile
 * ({@code profile.supportsExtendedThinking()}) rather than testing
 * {@code if (modelId.contains("claude"))} — feature-detection over assumption (ADR-0002).
 *
 * <p><b>Shape (ADR-0002 / 03-data-model.md &sect; 2.6).</b> The profile carries:
 * <ul>
 *   <li>{@link #providerFamily()} — the {@link ProviderFamily} tag (drives family-specific
 *       {@code additionalModelRequestFields} mapping and the ADR-0011 auth/header differences);</li>
 *   <li>{@link #contextWindowTokens()} — the effective input-token window the compaction
 *       threshold (NFR-CONTEXT-COMPACT-THRESHOLD, ADR-0006) is taken as {@code 0.85 x} of;</li>
 *   <li>{@link #supportsExtendedThinking()} (+ {@link #thinkingBudgetConfigurable()}) — whether
 *       the model supports reasoning content (Claude, model-gated);</li>
 *   <li>{@link #supportsToolUse()} — whether the model supports tool use (assumed, but guarded);</li>
 *   <li>{@link #supportsImageInput()} / {@link #supportsDocumentInput()} — whether the model
 *       accepts {@code ImageBlock} / {@code DocumentBlock} multimodal input (INV-19);</li>
 *   <li>{@link #promptCache()} — the {@link PromptCacheCaps}, or {@code null} when prompt
 *       caching is unsupported (&sect; 2.6 "null = unsupported");</li>
 *   <li>{@link #inferenceParamPassthrough()} — the {@code additionalModelRequestFields} keys
 *       that are valid for this model (e.g. {@code top_k} for Claude, not universal).</li>
 * </ul>
 *
 * <p><b>Graceful degradation (ADR-0002).</b> The loop asks the profile for each optional
 * capability; an absent capability ({@code false}, or a {@code null} prompt cache) means the
 * feature is simply not used — the loop still runs. The unknown-model conservative default
 * reports {@link ProviderFamily#OTHER}, no extended thinking, no prompt cache, tool-use assumed
 * {@code true}, no image/document input, an empty passthrough set, and a safe-minimum window
 * (&sect; 2.6). Resolution from a {@code modelId} lives in {@link ModelCapabilityRegistry}; this
 * record is the resolved value object.
 *
 * <p>Immutable value object (Effective Java Item 17); the {@code inferenceParamPassthrough} list
 * is defensively copied so a shared instance cannot be mutated through it.
 *
 * @param providerFamily            the provider family tag (ADR-0002 / &sect; 2.6); must not be
 *                                  {@code null}.
 * @param contextWindowTokens       the model's effective input-token window — the figure the
 *                                  compaction threshold (ADR-0006) is a fraction of; {@code >= 1}.
 * @param supportsExtendedThinking  whether the model supports extended thinking (reasoning
 *                                  content; Claude, model-gated).
 * @param thinkingBudgetConfigurable whether the thinking budget is configurable; only meaningful
 *                                  when {@code supportsExtendedThinking} is {@code true}
 *                                  (schema note).
 * @param supportsToolUse           whether the model supports tool use (assumed {@code true}
 *                                  even in the conservative default, but kept as a guarded flag).
 * @param supportsImageInput        whether the model accepts {@code ImageBlock} multimodal input
 *                                  (&sect; 2.6); when {@code false} the attachment pipeline
 *                                  declines an image attachment with a message (INV-19).
 * @param supportsDocumentInput     whether the model accepts {@code DocumentBlock} multimodal
 *                                  input (&sect; 2.6); when {@code false} a document attachment is
 *                                  declined (INV-19).
 * @param promptCache               the prompt-cache capabilities, or {@code null} when prompt
 *                                  caching is unsupported (&sect; 2.6 "null = unsupported").
 * @param inferenceParamPassthrough the valid {@code additionalModelRequestFields} keys for this
 *                                  model; never {@code null} (empty when none), defensively copied.
 */
public record ModelCapabilityProfile(
        ProviderFamily providerFamily,
        int contextWindowTokens,
        boolean supportsExtendedThinking,
        boolean thinkingBudgetConfigurable,
        boolean supportsToolUse,
        boolean supportsImageInput,
        boolean supportsDocumentInput,
        PromptCacheCaps promptCache,
        List<String> inferenceParamPassthrough) {

    /**
     * Validates the profile invariant and defensively copies the passthrough list.
     *
     * @throws NullPointerException     if {@code providerFamily} or
     *                                  {@code inferenceParamPassthrough} is {@code null}.
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1} (schema {@code minimum: 1};
     *                                  it is the compaction divisor, 03-data-model &sect; 2.6).
     */
    public ModelCapabilityProfile {
        Objects.requireNonNull(providerFamily, "providerFamily");
        if (contextWindowTokens < 1) {
            throw new IllegalArgumentException(
                    "contextWindowTokens must be >= 1 (was " + contextWindowTokens + ")");
        }
        Objects.requireNonNull(inferenceParamPassthrough, "inferenceParamPassthrough");
        inferenceParamPassthrough = List.copyOf(inferenceParamPassthrough);
    }

    /**
     * Creates a conservative-default-shaped profile carrying only the context window plus the two
     * multimodal flags — the rest of the shape takes the conservative-default values
     * ({@link ProviderFamily#OTHER}, no extended thinking, tool-use assumed {@code true}, no
     * prompt cache, empty passthrough). Backward-compatible with the T-4.2-era three-arg form the
     * attachment pipeline's tests construct directly.
     *
     * @param contextWindowTokens   the model's effective input-token window; {@code >= 1}.
     * @param supportsImageInput    whether the model accepts {@code ImageBlock} input (INV-19).
     * @param supportsDocumentInput whether the model accepts {@code DocumentBlock} input (INV-19).
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1}.
     */
    public ModelCapabilityProfile(
            int contextWindowTokens, boolean supportsImageInput, boolean supportsDocumentInput) {
        this(ProviderFamily.OTHER, contextWindowTokens, false, false, true,
                supportsImageInput, supportsDocumentInput, null, List.of());
    }

    /**
     * Creates a conservative-default-shaped profile carrying only the context window, with both
     * multimodal flags {@code false} — the conservative default for a model whose multimodal
     * support is unknown (&sect; 2.6 "no image/document input"). Backward-compatible with the
     * T-2.1-era window-only form callers that need only the window seam construct directly.
     *
     * @param contextWindowTokens the model's effective input-token window; {@code >= 1}.
     * @throws IllegalArgumentException if {@code contextWindowTokens < 1}.
     */
    public ModelCapabilityProfile(int contextWindowTokens) {
        this(contextWindowTokens, false, false);
    }

    /**
     * Resolves the capability profile for a model id (ADR-0002 static prefix registry) — a thin
     * delegate to {@link ModelCapabilityRegistry#resolve(String, int)}, kept so existing callers
     * resolve through the same registry seam. A known Claude id resolves to the Claude family
     * profile; an unknown id resolves to the conservative default profile carrying the supplied
     * safe-minimum window.
     *
     * @param modelId              the active Bedrock model id (bare or {@code us.}
     *                             inference-profile form); must not be {@code null}.
     * @param fallbackWindowTokens the safe-minimum window for an unknown id (ADR-0002 "a safe
     *                             minimum context window"); {@code >= 1}.
     * @return the profile for {@code modelId}; never {@code null}.
     * @throws NullPointerException     if {@code modelId} is {@code null}.
     * @throws IllegalArgumentException if {@code fallbackWindowTokens < 1}.
     */
    public static ModelCapabilityProfile forModelId(String modelId, int fallbackWindowTokens) {
        return ModelCapabilityRegistry.resolve(modelId, fallbackWindowTokens);
    }
}
