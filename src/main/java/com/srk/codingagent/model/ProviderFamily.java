package com.srk.codingagent.model;

/**
 * The model-provider family a {@link ModelCapabilityProfile} is tagged with (ADR-0002,
 * 03-data-model.md &sect; 2.6 / &sect; 4). The tag drives provider-specific concerns the loop
 * keeps out of itself — family-specific {@code additionalModelRequestFields} mapping and the
 * auth/header differences ADR-0011 notes — so callers consult the profile's family rather than
 * substring-matching the model id.
 *
 * <p><b>v1 scope (ADR-0002, NFR-MODEL-PROVIDER).</b> The model boundary is <em>designed</em> to
 * reach any Bedrock family through the Converse API, but v1 targets, validates, and ships
 * <b>Claude only</b> — so {@link #ANTHROPIC} is the only family with a populated profile in the
 * registry. The remaining values are the architectural seam: a future provider bring-up adds a
 * registry entry tagged with its family plus a validation pass, not a loop rewrite. Non-Claude
 * families therefore exist here but resolve through the conservative default ({@link #OTHER})
 * until a profile is added.
 */
public enum ProviderFamily {

    /** Anthropic Claude — the only family v1 ships a populated capability profile for. */
    ANTHROPIC,

    /** Amazon (Nova, Titan) — seam only; no v1 profile. */
    AMAZON,

    /** Meta (Llama) — seam only; no v1 profile. */
    META,

    /** Mistral — seam only; no v1 profile. */
    MISTRAL,

    /**
     * Any other / unknown family — the family of the conservative default profile an
     * unrecognized model id resolves to (ADR-0002 "a conservative default profile for unknown
     * ids").
     */
    OTHER
}
