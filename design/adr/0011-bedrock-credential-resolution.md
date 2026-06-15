---
adr: 0011
title: Bedrock credential resolution — profile → default chain (SigV4 only, bearer ignored)
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0001, ADR-0009, ADR-0002]
spec_refs: [US-8, AC-8.6, AC-8.7, AC-8.8, AC-8.9, RD-11, NFR-AWS-CREDENTIALS, NFR-BEDROCK-REGION]
---

# ADR-0011 — Bedrock credential resolution

## Status

accepted (2026-06-15)

## Context

Every Converse call needs credentials. Reading the Bedrock user guide surfaced **three** possible auth paths (§ 6.A.2): a bearer token (`AWS_BEARER_TOKEN_BEDROCK`, the newer API-key mechanism), a named AWS profile (SigV4), and the SDK default credential provider chain (SigV4).

An earlier revision of this ADR put bearer-token **first**. On review the user identified the hazard: a `AWS_BEARER_TOKEN_BEDROCK` env var left in the environment from **another account** would silently authenticate this tool against the wrong AWS account — a billing/data footgun and a production-safety concern (the user's global `amazon-production-safety` rule says "assume production when uncertain"). For a tool that calls a paid API and acts on the user's account, **predictable, explicitly-chosen credentials beat a convenient-but-ambient bearer token.** The user directed: **profile → default chain only, and ignore any bearer token.**

A subtlety this raises: the AWS SDK v2 Bedrock client may *natively* honor `AWS_BEARER_TOKEN_BEDROCK` if present. So "don't configure bearer" is insufficient — we must **explicitly construct the client to use a SigV4 credentials provider** so an ambient bearer token cannot take effect.

## Decision

**We will resolve Bedrock credentials via SigV4 in two tiers — named profile, then default chain — and explicitly ignore any bearer token.**

### Precedence (RD-11, AC-8.6–8.9)

1. **Named profile** — if `aws.profile` is configured (ADR-0009), resolve via `ProfileCredentialsProvider` from `~/.aws/{config,credentials}` (AC-8.6).
2. **Default chain** — else (or if the named profile is not found), the AWS SDK v2 `DefaultCredentialsProvider` (env vars → SSO/SDK token cache → container/instance role) (AC-8.7).
3. **Both fail** → **exit 4** (model-backend) with a message naming the paths attempted (AC-8.9).

### Bearer token explicitly ignored (AC-8.8 — the load-bearing rule)

- The Model Client (C4, ADR-0001) constructs the `BedrockRuntimeClient` with an **explicit SigV4 `AwsCredentialsProvider`** (profile or default chain per above). It does **not** enable bearer-token auth, and **must not pick up `AWS_BEARER_TOKEN_BEDROCK` even when that env var is set.**
- If a bearer token *is* present in the environment, the agent logs a one-line **warning** ("ignoring AWS_BEARER_TOKEN_BEDROCK; this tool authenticates via SigV4 profile/chain only") so the behavior is observable, then proceeds with SigV4. This makes the ignore explicit and debuggable rather than silent.
- Implementation note: verify, at build time, that the constructed client does not fall back to bearer auth from the environment — this is a testable requirement, not an assumption about SDK defaults.

### Region + IAM posture

- Region from `NFR-BEDROCK-REGION` (`aws.default_region`, default `us-east-1`, configurable via ADR-0009).
- **Read/invoke only.** `bedrock:InvokeModel`, `bedrock:InvokeModelWithResponseStream` (+ inference-profile reads) — verified sufficient for Converse/ConverseStream (§ 6.A.2). **No create/update/delete/put** AWS verbs, ever (matches the global production-safety rule). Least-privilege guidance documented in `05-operations.md`.
- A clear startup log line records which tier resolved (profile name or "default chain") — never logging secrets.

## Consequences

**Positive**
- **No wrong-account footgun** — credentials are explicitly chosen (profile) or the well-understood default chain; an ambient bearer token from another account cannot hijack the tool.
- Predictable + auditable: the startup log states the resolved tier; aligns with production-safety ("know which account you're acting on").
- Simpler than three tiers — two SigV4 paths, both SDK-native.
- Covers the real setups: dev with a named profile, CI/instance with role-based default chain.

**Negative / costs**
- Loses the bearer-token convenience AWS markets as prod-friendly (short-term keys). Acceptable — the user prioritized account-safety over that convenience; bearer support can be revisited post-v1 *with an explicit opt-in flag* if ever wanted.
- Requires explicitly overriding any SDK-native bearer pickup — a small implementation + test obligation (call it out in Phase 5).

**Neutral / follow-ons**
- Region + profile come from config (ADR-0009).
- The `aws-bedrock-token-generator` dependency is **not** needed (no bearer path).
- A future "bearer opt-in" would be a deliberate, flag-gated addition — not the default — if a user ever asks for it.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Bearer → profile → default chain** (prior revision) | Bearer first, prod-friendly | A stray `AWS_BEARER_TOKEN_BEDROCK` from another account silently authenticates the wrong account — the footgun the user rejected |
| **Default chain only** | Simplest; SDK does everything | Loses explicit named-profile selection (the user's primary credential ask) |
| **Bearer only** | Newest, simplest auth | Excludes profile/role users; *and* is exactly the ambient-token risk we're avoiding |
| **Honor bearer but pin to an account** | Validate the token's account before use | Complex, fragile (account discovery per token type); ignoring bearer entirely is simpler and safer for v1 |

## Notes

- Revises the 2026-06-14 bearer-first decision (§ 6.A.2) to SigV4-only-ignore-bearer per user direction 2026-06-15.
- The "must not pick up an ambient bearer token" rule (AC-8.8) is the load-bearing safety property here — a passive "we just don't set it" is insufficient if the SDK auto-detects the env var.
- Narrow by design (auth only); client construction + region live with the engine (ADR-0001) and config (ADR-0009).
