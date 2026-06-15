---
adr: 0006
title: Context management — compaction-with-derivation, output disposal, cache placement
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0005, ADR-0002, ADR-0001, ADR-0003]
spec_refs: [US-18, US-19, AC-18.1, AC-18.2, AC-18.3, AC-18.4, AC-18.5, AC-19.1, AC-19.2, AC-19.3, RD-8, NFR-CONTEXT-COMPACT-THRESHOLD, NFR-OUTPUT-MAX-INLINE, NFR-MODEL-CONTEXT-WINDOW, OQ-D, OQ-I]
---

# ADR-0006 — Context management: compaction-with-derivation, output disposal, cache placement

## Status

accepted (2026-06-15)

## Context

Working context is finite; three token-producers threaten it (brainstorm "three levers"): the growing conversation (temporal), large sub-agent results (spatial — ADR-0010), and **fat tool/command output** (the quiet one — ADR-0003). The Context Manager (C6) owns two mechanisms — **compaction-with-derivation** (US-18) and **output disposal** (US-19) — plus the **prompt-cache placement** strategy that tempers the Opus cost (OQ-I). This ADR resolves **OQ-D** (who/how generates the compaction summary) and **OQ-I** (cache checkpoint placement).

Hard constraint from § 6.A.1: **reasoning-signature blocks are tamper-checked** — we may not edit conversation history in place. So compaction cannot rewrite a conversation; it must **derive a new one**.

## Decision

### Compaction-with-derivation (US-18, RD-8; resolves OQ-D)

- **Trigger.** Auto when `usage.inputTokens` (measured, every call — § 6.A.1) reaches `NFR-CONTEXT-COMPACT-THRESHOLD` (0.85) × the active model's `contextWindowTokens` (from the capability profile, ADR-0002). Also on the manual `/compact` command at any utilization (AC-18.2). Also when `stopReason == model_context_window_exceeded` as a backstop.
- **Summary generation (OQ-D).** A **dedicated Converse call** to the **same model** (or a configured cheaper summarizer model) with a fixed *compaction system prompt* that asks for: outstanding task state, decisions made, files touched, open work, and any durable learnings — enough to "continue without the developer re-explaining" (AC-18.4). The summary is text; it does not include raw reasoning blocks.
- **Derivation (not mutation).** We **create a new session** (ADR-0005) seeded with: the summary as an initial system/user context block, plus a configurable tail of recent verbatim turns. The original session is **preserved unchanged** and linked `derived-from` (AC-18.3, RD-8). Work continues in the child. This is mandatory, not stylistic — the signature rule forbids in-place edits (§ 6.A.1).
- **Learning harvest (AC-18.5).** During compaction, durable learnings identified in the summary are **proposed** for memory (ADR-0007) before the original is archived — the compaction moment is the natural harvest trigger.

### Output disposal (US-19)

- **Trigger.** Any tool/command output exceeding `NFR-OUTPUT-MAX-INLINE` (16 KB) is reduced before entering context (AC-19.1).
- **Strategy (tiered).** (1) **Persist full output** to the event log as a `tool_result` with a `fullRef` (AC-19.2) — never lost. (2) **Reduce for context**: head+tail truncation by default (keeps the start and the error tail — most build failures are legible from the tail), with a `truncated` marker. (3) For very large or structured output, optionally **summarize** via a model call. The model can **retrieve the full output** from the log on demand (AC-19.3) rather than re-running the command.
- **Why head+tail default, not summarize-always:** cheaper (no extra model call on every fat output), and build/test failures are usually diagnosable from the tail. Summarization is the escalation, not the default.

### Prompt-cache placement (OQ-I)

- **Strategy.** Place a `cachePoint` after the **stable prefix** — tools → system → memory-index — which are static across a session (cache order is tools→system→messages, § 6.A.1). This caches the largest static region; the variable `messages` tail stays uncached. Tempers the Opus default's resend cost (cached tokens bill reduced + don't count vs rate limit).
- **Capability-gated.** Only when the profile (ADR-0002) reports prompt-cache support and the prefix meets the model's token minimum (Opus 4.5/4.6: ≥4096; § 6.A.1). Absent support ⇒ no cachePoint, loop unaffected (graceful degradation).
- **Conservatism.** Use the model's *simplified single-breakpoint* cache management where available; don't micro-manage multiple checkpoints in v1.

## Consequences

**Positive**
- Survives arbitrarily long tasks (US-18) and verbose builds (US-19) without losing the thread or the data.
- Derivation respects the signature rule *and* gives history-for-free (the original is a preserved lineage node — US-15).
- Compaction doubles as the learning-harvest trigger (one mechanism, two payoffs).
- Cache placement makes the Opus default affordable without complicating the loop.

**Negative / costs**
- Compaction summary is a model call → cost + latency at the threshold; a bad summary loses nuance. Mitigated: configurable summarizer model; recent-tail verbatim carryover; original always retrievable.
- Head+tail truncation can drop the middle of a genuinely-needed large output → mitigated by on-demand full retrieval (AC-19.3).
- Cache correctness depends on prefix stability — any change to tools/system/memory-index invalidates the cache (§ 6.A.1); acceptable since those rarely change mid-session.

**Neutral / follow-ons**
- Compaction is a stateful flow → formalized as a state machine in `06-formal/` (the lifecycle from threshold → summarize → derive → continue).
- ADR-0007 consumes the harvested-learning proposals.
- ADR-0002 supplies `contextWindowTokens` + cache params.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Edit history in place** (drop old turns) | Trim the messages[] array directly | Violates the reasoning-signature tamper check (§ 6.A.1) — the call would error; also loses history (US-15) |
| **Sliding window** (keep last N turns, no summary) | Cheap, no model call | Loses task state/decisions silently — the model forgets *why*; AC-18.4 needs carried context |
| **Summarize-always for tool output** | Uniform reduction | Extra model call on every fat output = cost; head+tail is usually sufficient for build logs |
| **No prompt caching** | Simpler | Leaves the Opus resend cost on the table; caching is low-risk and capability-gated |
| **External vector store of old turns** (RAG recall) | Retrieve old context on demand | Embeddings/RAG is OOS for v1 (future-work rung 3); index+summary is the v1 line |

## Notes

- Resolves OQ-D (summary = dedicated Converse call, fixed compaction prompt, optional cheaper model) and OQ-I (cachePoint after tools→system→memory-index, capability-gated).
- Threshold math uses the measured `usage.inputTokens`, not an estimate (§ 6.A.1) — so 0.85×window is exact.
- The compaction state machine + the disposal thresholds are candidates for Phase 3 formalization + contract tests.
