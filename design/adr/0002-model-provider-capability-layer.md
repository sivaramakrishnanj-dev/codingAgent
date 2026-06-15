---
adr: 0002
title: Model-provider abstraction + capability layer
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0001, ADR-0006, ADR-0011]
spec_refs: [NFR-MODEL-PROVIDER, NFR-MODEL-DEFAULT, NFR-MODEL-SUBAGENT, NFR-MODEL-CONTEXT-WINDOW, OQ-J]
---

# ADR-0002 — Model-provider abstraction + capability layer

## Status

accepted (2026-06-15)

## Context

`NFR-MODEL-PROVIDER` pins the stance: **provider-agnostic by design, Claude-only validated/shipped in v1.** Converse already normalizes the *wire* across families (ADR-0001), but several capabilities are **model-specific** and the loop must not hard-assume them:

- **Extended thinking** (`reasoningContent` + tamper-checked signatures) — Claude; model-gated; budget not always configurable (§ 6.A.2).
- **Prompt-caching** token minimums + checkpoint counts differ per model (§ 6.A.1: Opus 4.5/4.6 need ≥4096 tokens/checkpoint; others 1024).
- **Context-window size** — needed to compute the compaction threshold (`NFR-CONTEXT-COMPACT-THRESHOLD` = 0.85 × window).
- **Inference params** — e.g. `top_k` via `additionalModelRequestFields` (Claude); not universal.
- **Tool-use support** — assumed for our agent, but a guard matters if a non-tool model is ever configured.

Without an abstraction, these leak into the loop as Claude-specific branches, which would (a) make the Claude-only-v1 / agnostic-by-design split incoherent, and (b) make a future non-Claude bring-up a rewrite. OQ-J asks: what's feature-detected, how does `modelId` resolve to a capability profile, and how does the loop degrade when a capability is absent?

## Decision

**We will introduce a `ModelCapabilityProfile` abstraction (component C5) that the loop and context manager consult instead of branching on `modelId` directly.**

- **Profile shape.** A `ModelCapabilityProfile` carries: `contextWindowTokens`, `supportsExtendedThinking` (+ optional `thinkingBudgetConfigurable`), `promptCache` (min tokens/checkpoint, max checkpoints, TTLs, or `unsupported`), `supportsToolUse`, `inferenceParamPassthrough` (which `additionalModelRequestFields` keys are valid), and a `providerFamily` tag (`anthropic` | `amazon` | `meta` | …).
- **Resolution.** `modelId` → profile via a **static registry keyed by model-id prefix**, with a **conservative default profile** for unknown ids (no extended thinking, no prompt caching, tool-use assumed, a safe minimum context window read from config `NFR-MODEL-CONTEXT-WINDOW`). v1 ships **only Claude profiles** populated; the registry is the seam where future providers are added (config + a profile entry, not a code rewrite).
- **Feature-detection, not assumption.** The loop asks the profile (`profile.supportsExtendedThinking()`), never `if (modelId.contains("claude"))`. Absent capability ⇒ the feature is simply not used (graceful degradation), the loop still runs.
- **Sub-agent model override** (`NFR-MODEL-SUBAGENT`). A sub-agent may run a different (cheaper) model; it resolves its own profile independently. May differ in family, subject to the same detection.
- **v1 boundary.** Only Claude is validated/shipped. Non-Claude profiles are **post-v1** — the registry entry + a validation pass per provider. The abstraction is intentionally **thin** in v1 (a seam + Claude profiles), not a full multi-provider matrix, to bound cost (per the overview's "agnostic core, narrow v1 target").

## Consequences

**Positive**
- The Claude-only-v1 / agnostic-by-design split becomes coherent and enforced in one place.
- Compaction (ADR-0006) reads `contextWindowTokens` + prompt-cache params from the profile — no model branching there.
- Future provider bring-up = add a profile + validate; no loop changes. Keeps the `NFR-MODEL-PROVIDER` promise honestly.

**Negative / costs**
- One indirection layer to maintain; risk of a stale profile if a model's real capabilities change. Mitigated: profiles are small, data-driven, and unknown ids fall to the safe default.
- The conservative default could under-use a capable unknown model (e.g. skip caching). Acceptable — correctness over optimization for unvalidated models.

**Neutral / follow-ons**
- ADR-0006 (compaction) and the prompt-caching strategy consume this profile.
- Phase 3 may formalize the profile as a small schema; Phase 4 task picks the registry seed (Claude profiles).

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Branch on `modelId` in the loop** | `if claude … else …` inline | Leaks model specifics everywhere; makes provider-agnostic claim false; future bring-up = scattered edits |
| **Assume Claude everywhere (drop agnostic seam)** | Hard-code Claude features | Contradicts user-directed `NFR-MODEL-PROVIDER`; closes the swap-later door |
| **Full multi-provider matrix in v1** | Populate + validate all providers now | Pays multi-provider validation cost the user explicitly deferred; v1 ships Claude only |
| **Query Bedrock for capabilities at runtime** | Discover capabilities via an API | No single authoritative capability API across families; adds startup calls/latency; static registry is simpler and offline |

## Notes

- Capability facts: `design-progress.md` § 6.A.1 (prompt-cache minimums, reasoning signatures) and § 6.A.2 (reasoning model-gated; 100+ models / families).
- `providerFamily` tag also informs ADR-0011 (some auth/headers) and any family-specific `additionalModelRequestFields` mapping.
- Revisit when the first non-Claude provider is brought up post-v1 — that exercise will validate whether the profile shape is complete.
