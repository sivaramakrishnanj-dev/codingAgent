---
adr: 0009
title: Configuration model + precedence
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0005, ADR-0011, ADR-0002]
spec_refs: [US-8, AC-8.1, AC-8.2, AC-8.3, AC-8.4, AC-8.5, AC-6.3, AC-6.4, OQ-G]
---

# ADR-0009 — Configuration model + precedence

## Status

accepted (2026-06-15)

## Context

The agent must be configured per project and per user: model id, permission mode, build/test commands, AWS region/profile, sub-agent cap, etc. (US-8). Requirements fix the **precedence** (AC-8.2: CLI flags > project config > global config > built-in defaults) and the **fail-fast** rule (AC-8.5/6.4: malformed config → exit 2). This ADR resolves **OQ-G** — the file format + layout + how precedence is implemented.

## Decision

**We will use YAML config files at two locations, merged by a fixed precedence into one resolved config, validated before any model call.**

### Files + layout

- **Global**: `~/.codingagent/config.yaml` — user-wide defaults (default model, default permission mode, AWS region/profile, sub-agent cap, summarizer model).
- **Project**: `~/.codingagent/projects/<repo-key>/project.yaml` — per-repo overrides (build/test/lint commands, project-specific model or mode, the human-readable repo origin).
- **CLI flags** — highest precedence, for one-off overrides (`--model`, `--permission-mode`, `--region`, …).
- **Built-in defaults** — compiled-in (the NFR defaults: `ASK_EVERY_TIME`, newest Opus, 0.85 compaction, N=1 sub-agents, 16 KB output cap, timeouts).

> Note: project config lives under the **user-global store** keyed by repo, *not* committed in the repo (consistent with ADR-0005 — `git clean` won't touch it, and we don't impose a dotfile on the user's repo). A future option to also read an in-repo `.codingagent.yaml` is left open but OOS for v1.

### Precedence (AC-8.2)

Resolution order, first-wins per key: **CLI flag → project.yaml → global config.yaml → built-in default.** Implemented as a layered merge into one immutable `ResolvedConfig` object at startup; every component reads `ResolvedConfig`, never the raw files.

### Format = YAML

YAML (not JSON/TOML/properties): human-friendly, comment-able, matches the ecosystem the user already works in (the spec-driven `.kiro/*.yaml`, AWS configs). One schema per file; documented in `05-operations.md`.

### Validation + fail-fast (AC-8.5, AC-6.4)

- On startup, parse + validate the merged config **before** any Bedrock call or tool execution.
- Malformed/unknown values (bad permission mode, unparseable command, unknown key) → **exit 2** (usage-config) with a message naming the offending key (AC-8.5). Missing *required* config → exit 2 naming the missing field (AC-6.4).
- Unknown keys are an error (not silently ignored) — catches typos early.

## Consequences

**Positive**
- One immutable resolved object → no scattered file reads, no mid-run config drift.
- Fail-fast on bad config → never start a run that will misbehave; clear operator feedback.
- Two-tier mirrors the memory tiers (ADR-0007) and the user-global store (ADR-0005) — one mental model.

**Negative / costs**
- Project config living outside the repo is slightly less discoverable than an in-repo dotfile (mitigated: `05-operations.md` documents the path; future in-repo option left open).
- Strict unknown-key rejection can annoy on a typo — but that's the point (catch typos vs silently misconfigure).

**Neutral / follow-ons**
- ADR-0011 (credentials) reads `aws.profile`/`region` from here; bearer-token is env, not file.
- ADR-0002 reads the default/override model id; ADR-0004 reads the permission mode; ADR-0003 reads the command set — all via `ResolvedConfig`.
- Config schema is a Phase 3 formalization candidate.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **In-repo `.codingagent.yaml`** | Travels with the repo, discoverable | Imposes a file on the user's repo + gets caught by `git clean`/commits; user-global keying chosen (ADR-0005). Left as a future option |
| **JSON / TOML / properties** | Other formats | YAML matches the surrounding ecosystem + supports comments; least friction |
| **Env-vars only** | 12-factor style | Poor for structured config (command sets, nested); env reserved for secrets/bearer token + one-off overrides |
| **Silently ignore unknown keys** | Lenient | Hides typos that misconfigure safety-relevant settings; fail-fast is safer |

## Notes

- Resolves OQ-G (YAML, two-tier under the user-global store, layered first-wins merge, fail-fast exit 2).
- Secrets (bearer token) never go in these files — env var only (ADR-0011).
- `ResolvedConfig` is built once at startup; timestamps/ids it needs are passed at the boundary (reproducibility, per ADR-0005).
