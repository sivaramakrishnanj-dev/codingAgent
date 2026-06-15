---
adr: 0005
title: Persistence — event-sourced JSONL + conversation tree
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0006, ADR-0007, ADR-0010, ADR-0001]
spec_refs: [US-7, US-13, US-15, US-16, AC-7.2, AC-7.3, AC-13.1, AC-13.3, AC-13.4, AC-15.1, AC-15.3, AC-16.1, RD-8, NFR-LOG-FORMAT, NFR-LOG-LOCATION, NFR-LOG-DURABILITY, NFR-LOG-RETENTION]
---

# ADR-0005 — Persistence: event-sourced JSONL + conversation tree

## Status

accepted (2026-06-15)

## Context

Bedrock Converse is **stateless** — we resend the full conversation every call (`design-progress.md` § 6.A.1). So *we* own all durable state. Four requirements ride on one persistence model: full observability (US-13 — log everything), resume (US-7), history incl. compacted sessions (US-15), and outcome signals (US-16). The brainstorm settled: user-global store, JSONL per conversation as the single source of truth, sessions keyed by repo, a conversation-*tree* with `derived-from`/`spawned-by` edges.

This ADR fixes: the event-log format + the event taxonomy, the on-disk layout, the conversation-tree model, durability, and how resume reconstructs `messages[]`.

## Decision

**We will persist an append-only JSONL event log per conversation as the single source of truth, under a user-global store keyed by repo, with a separate lineage model linking conversations into a tree.**

### On-disk layout (`NFR-LOG-LOCATION`)

```
~/.codingagent/
├── config.yaml                      # global config (ADR-0009)
├── memory/                          # global memory tier (ADR-0007)
└── projects/<repo-key>/
    ├── project.yaml                 # project config (ADR-0009)
    ├── sessions/
    │   ├── <session-id>.jsonl       # THE event log (source of truth)
    │   └── <session-id>.meta.json   # summary: status, tokens, parent edge, outcome
    ├── memory/                      # project memory tier (ADR-0007)
    └── lineage.json                 # conversation tree edges
```

- **`<repo-key>`** = the git remote URL when present, else the normalized absolute path of the working directory (AC-7.3). Hashed/sanitized for filesystem safety; the human-readable origin is recorded in `project.yaml`.
- **`<session-id>`** = a sortable unique id (timestamp-prefixed; the actual timestamp is captured at session creation, not derived in-process — passed in at the boundary).

### Event log format (`NFR-LOG-FORMAT` = JSONL)

One JSON object per line, appended in occurrence order. Every event carries: `seq` (monotonic per session), `ts` (ISO-8601), `type`, and a type-specific payload. Event `type` taxonomy (AC-13.1):

| `type` | Payload (key fields) |
|--------|----------------------|
| `session_start` | mode (greenfield/brownfield), repoKey, modelId, permissionMode |
| `user_message` | content blocks |
| `model_request` | the messages[]+system+toolConfig digest sent (or a ref), modelId |
| `model_response` | assistant content blocks (text/toolUse/reasoning), stopReason |
| `model_usage` | inputTokens, outputTokens, cacheRead/WriteInputTokens |
| `tool_use` | toolUseId, name, input |
| `permission_decision` | toolUseId, classification, mode, decision (approve/deny), matchedGrant? |
| `tool_result` | toolUseId, result {exit,stdout,stderr,…} or text, truncated?, fullRef? |
| `subagent_spawn` / `subagent_result` | childSessionId, prompt digest / summary |
| `compaction` | fromSessionId, toSessionId, summaryRef, triggerReason |
| `memory_write` | tier, slug, provenance |
| `outcome` | taskRef, success(bool), iterations (US-16, RD-10) |
| `error` | category, message, exitCode? |

The data structures behind these events are detailed in `03-data-model.md`; the JSON schemas in `06-formal/`.

### Conversation tree (RD-8, AC-15.3)

- A **Conversation** = one session = one JSONL file. Each has at most one **parent** plus an **edge type**: `derived-from` (compaction continuation, ADR-0006) or `spawned-by` (sub-agent, ADR-0010). Roots have no parent.
- `lineage.json` holds the edges `{child, parent, edgeType, ts}`. The main session, a compacted continuation, and a sub-agent are **the same shape** differing only by edge type — one model, several operations.
- **Originals are never deleted** on compaction (RD-8, AC-15.1) — the parent JSONL stays; the child references it.

### Durability + resume

- **Flush per event** (`NFR-LOG-DURABILITY`) — append + flush before the loop acts on it (the "log-before-act" rule, `02-architecture.md` § 2). A crash loses ≤ 1 in-flight event.
- **Resume** (AC-7.2) = read the session's JSONL in `seq` order and **replay events into a fresh `messages[]`** (user/model/tool blocks become Converse messages; reasoning blocks replay verbatim with signatures). The `.meta.json` gives a fast summary without scanning the whole log; resuming a compacted lineage picks the **latest continuation** by default (AC-7.4).
- **Corrupt/unreadable log** (AC-7.5) → report + offer a new session; never crash.
- **Retention** (`NFR-LOG-RETENTION`) — indefinite, operator-managed; nothing auto-deleted.

## Consequences

**Positive**
- One mechanism serves observability + resume + history + outcomes — log-once, read-many.
- Append-only + per-event flush = strong crash safety with trivial implementation.
- JSONL is greppable, diffable, hand-inspectable (serves US-13/15 and the Operator persona directly).
- The unified tree collapses "session / continuation / sub-agent" into one model (DRY across ADR-0006/0010).

**Negative / costs**
- Resend-everything + log-everything = disk grows with use; large sessions = large files. Mitigated: disposal (ADR-0006) keeps fat tool output as `fullRef` side entries, not inline; retention is operator-managed.
- Per-event flush has an I/O cost on hot loops — acceptable for a local single-user CLI; can batch-flush within a turn if measured as a problem (revisit, don't pre-optimize).
- Replaying long logs to resume has a cost proportional to session length — bounded by compaction (sessions don't grow unboundedly).

**Neutral / follow-ons**
- ADR-0006 (compaction) writes `compaction` events + new derived sessions.
- ADR-0010 (sub-agents) writes `subagent_*` events + `spawned-by` edges; in-process sub-agents share this writer.
- ADR-0007 (memory) logs `memory_write` events here, but memory *content* lives in the markdown store, not the JSONL.
- `03-data-model.md` formalizes event/edge types; `06-formal/` carries their schemas.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **SQLite / embedded DB** | Structured queries over events | Not hand-inspectable/greppable; opaque to the Operator; schema migrations; overkill for append-only single-writer. Could add later as a *derived index* if query speed demands |
| **Snapshot latest state only** (no event log) | Smaller, simpler | Loses the full trace US-13 requires; can't reconstruct "how it got here" (US-15); no replay fidelity |
| **Project-local `.codingagent/`** (in-repo) | Travels with the repo | `git clean` nukes history; the brainstorm chose user-global so history survives repo cleans |
| **One log for all sessions** | Single file | Cross-session contention, unbounded growth, hard to resume a single session; per-session files isolate cleanly |

## Notes

- The "stateless API ⇒ we own state" chain is the root justification — `design-progress.md` § 6.A.1.
- Timestamps/ids are captured at the I/O boundary and passed in (the design avoids in-process clock/random calls so behavior stays reproducible/testable).
- `meta.json` is a convenience cache derived from the JSONL; on disagreement, **the JSONL wins** (it's the source of truth).
