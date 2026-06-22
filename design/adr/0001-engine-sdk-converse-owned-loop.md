---
adr: 0001
title: Engine — AWS SDK for Java v2 + Bedrock Converse, with an owned agent loop
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0002, ADR-0003, ADR-0005, ADR-0011]
spec_refs: [US-3, US-5, US-20, NFR-MODEL-DEFAULT, NFR-PLAT-JAVA, OQ-A]
---

# ADR-0001 — Engine: AWS SDK for Java v2 + Bedrock Converse, owned agent loop

## Status

accepted (2026-06-15)

## Context

codingAgent's heart is an LLM-driven tool-use loop (component C2). We must decide **how we talk to the model** and **who owns the loop**. Three sub-questions:

1. **Which Bedrock API?** Verified options (`design-progress.md` § 6.A.1–A.3): on `bedrock-runtime` — **Converse**/ConverseStream (unified, model-agnostic, client-side tools) and Invoke (per-model body shapes); on `bedrock-mantle` — Responses/Chat-Completions/Messages (OpenAI/Anthropic-compatible, can be server-side stateful).
2. **Which client library?** AWS SDK for Java v2 directly, vs a framework (LangChain4j, Spring AI).
3. **Who owns the loop?** Us (build request → parse blocks → dispatch tools → append results → repeat), or a framework's agent abstraction.

Constraints: Java 21 (`NFR-PLAT-JAVA`); Bedrock-only backend; we require full transcript ownership for the event-log/resume/observability design (US-13/15); Claude-default but provider-agnostic by design (ADR-0002). The brainstorm settled "control-first" and "own your core, rent your periphery."

## Decision

**We will use the AWS SDK for Java v2 `bedrock-runtime` client and call the Converse API (`Converse` / `ConverseStream`) directly, owning the agent loop ourselves.**

- **API: Converse.** It is the one interface AWS documents as "consistent across all models" — the exact property ADR-0002's provider-agnostic stance needs. It normalizes tool use (`toolConfig` ↔ `toolUse` ↔ `toolResult`) across model families, returns a uniform `stopReason` + `usage` envelope, and is on `bedrock-runtime` (SigV4 / our credential chain, ADR-0011). We do **not** use the Responses API as the engine (it's `bedrock-mantle`, OpenAI-SDK, server-side-stateful — opposite of our client-owned design; and it doesn't support Claude).
- **Library: AWS SDK for Java v2**, artifact `software.amazon.awssdk:bedrockruntime` (latest `2.46.10` per Maven Central, 2026-06-15 — pin/confirm exact version at implementation time). No LLM framework.
- **Owned loop (C2).** We implement the cycle: build `ConverseRequest` (messages + system + `toolConfig`) → call → parse `output.message.content[]` → on `stopReason == tool_use`, route each `toolUse` through the permission gate (ADR-0004) and tool registry (C7) → append `toolResult` blocks → re-call → until `end_turn`. `stopReason` is the loop's state selector (matrices in `02-architecture.md` § 3).
- **Tool registry → `toolConfig` (C7, OQ-A).** Each internal tool is `{name, description, inputSchema(JSON Schema)}` rendered to a Converse `toolSpec`; the model's `toolUse.input` (JSON matching the schema) dispatches to the handler.
- **Streaming.** Use `ConverseStream` for interactive output; assemble `toolUse` input from `contentBlockDelta` partial-JSON; accumulate `reasoningContent` verbatim (signatures — ADR-0006/§ 6.A.1).
- **Default model** `NFR-MODEL-DEFAULT` = newest Claude Opus (4.8 as of 2026-06-15). The pinned default is the **cross-region inference-profile id `us.anthropic.claude-opus-4-8`**, not the bare model id `anthropic.claude-opus-4-8`: on-demand Converse for Opus rejects the bare id with a 400 `ValidationException` ("Invocation of model ID ... with on-demand throughput isn't supported. Retry your request with the ID or ARN of an inference profile that contains this model."), so an inference-profile id is required and the `us.` cross-region profile is preferred for availability (confirmed by a real-Bedrock smoke test at implementation time; T-0.2-RD1). The exact id is resolved at runtime from config; this pinned default is carried in `ConfigDefaults.MODEL_ID`.

## Consequences

**Positive**
- Maximum control + transparency: we own every request/response, which is the substrate for event-sourcing (ADR-0005), compaction (ADR-0006), and observability (US-13).
- Model-agnostic by construction (Converse) — ADR-0002's capability layer rides on this for free.
- No framework version treadmill, no opinions to fight, no hidden context mutation.
- First-party SDK: credentials (ADR-0011), retries, region all handled idiomatically.

**Negative / costs**
- We write loop mechanics a framework would give us (tool dispatch, retry orchestration, streaming assembly). Accepted — it's the product's core, not boilerplate.
- We own correctness of the `toolUse`/`toolResult` protocol and the reasoning-signature replay rule.

**Neutral / follow-ons**
- Forces ADR-0003 (command exec), ADR-0004 (permission gate placement in the loop), ADR-0005 (what we log per turn).
- The `additionalModelRequestFields` passthrough is where Claude extended-thinking budget / `top_k` live (ADR-0002 capability layer).

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Invoke API** (per-model body) | Direct model access, full control over native body | Per-model request/response shapes — loses the model-agnostic property; Converse gives the same control with normalization |
| **Responses API** (`bedrock-mantle`) | OpenAI-style, server-side stateful, built-in tools | Doesn't support Claude (§ 6.A.3); server-side state guts our client-owned event-log/resume design; OpenAI SDK + bearer; different endpoint. (Retained only as the web-search alt — ADR-0008.) |
| **LangChain4j** | Java-native LLM framework, Bedrock + tool calling + memory | Framework owns the loop + context; opposes "control-first" and our bespoke event-log/compaction/sub-agent design; opaque context handling fights US-13 |
| **Spring AI** | Spring-ecosystem AI abstraction | Heavy for a single-user CLI; Spring runtime not otherwise needed; same loss-of-control objection |

## Notes

- Verified Bedrock facts: `design-progress.md` § 6.A.1 (Converse shape, stopReason set, usage, streaming, reasoning signatures) and § 6.A.3 (Responses API rejection rationale).
- SDK coordinate `software.amazon.awssdk:bedrockruntime:2.46.10` from Maven Central on 2026-06-15 — confirm latest at Phase 5 implementation; AWS SDK v2 releases frequently.
- Model id pinning deliberately deferred to runtime/config + impl-time confirmation: availability moves fast (Opus 4.6→4.7→4.8 observed within this design's lifetime).
