---
adr: 0007
title: Memory — two-tier markdown + index, curated writes
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0005, ADR-0006]
spec_refs: [US-12, US-14, US-21, AC-12.1, AC-12.2, AC-12.3, AC-12.4, AC-14.1, AC-14.2, AC-14.3, AC-21.1, AC-21.2, AC-21.3, AC-21.4, RD-9, OQ-F]
---

# ADR-0007 — Memory: two-tier markdown + index, curated writes

## Status

accepted (2026-06-15)

## Context

Cross-session learning is what makes the agent compound in value (US-12/21) without repeating mistakes. The brainstorm's four-memory framing places this as **semantic memory**, distinct from episodic (the event log, ADR-0005) and working (context, ADR-0006). Decisions settled: two tiers (global + project), markdown-per-entry + index with selective load, **curated writes** (explicit + propose-and-approve; no auto-extraction in v1), human-editable/deletable, re-read fresh. This ADR fixes the entry format, the index/load mechanism, the write lifecycle, and resolves **OQ-F** (does v1 need retrieval beyond index+load?).

The dark side is real: **bad memory is worse than no memory** (poisoning). The design must keep a human in the loop and make entries inspectable/deletable.

## Decision

### Two tiers (RD-9, AC-12.3)

- **Global** `~/.codingagent/memory/` — cross-project facts ("user prefers constructor injection").
- **Project** `~/.codingagent/projects/<repo-key>/memory/` — repo-specific ("integration tests need `-P integration`").
- On a write the agent classifies the entry's tier; on load, **both** tiers' indexes are read.

### Entry format (RD-9, AC-12.2, AC-14.1)

One markdown file per learning, `<slug>.md`, with front-matter:
```
---
slug: <kebab>
tier: global | project
created: <ISO ts>            # captured at the I/O boundary
origin_session: <session-id>
why: <one line — provenance>
status: active | retired
---
<the learning, in prose. Cite the spec/symbol/file it concerns so it stays verifiable.>
```
Plain markdown so a human can read/edit/delete with a text editor (AC-14.1). No DB.

### Index + selective load (RD-9, AC-14.3)

- Each tier has an `INDEX.md`: one line per entry — `- [slug] one-line description` — the always-loaded surface.
- **On session start**, both indexes load into the system prompt (cheap awareness). This sits in the cacheable static prefix (ADR-0006).
- The agent pulls a **full entry on demand** via a `read_memory(slug)` tool when an index line looks relevant. No embeddings/vector search (OQ-F answer below).

### Write lifecycle — curated only (AC-12.1, AC-21.*, RD-9)

- **Explicit** (US-12): the developer says "remember X" → agent writes the entry (+ index line) directly.
- **Propose-and-approve** (US-21): the agent proposes a learning (e.g. a mistake+fix discovered in the loop, or harvested at compaction per ADR-0006 AC-18.5) → **developer approves** → write. Not approved → not persisted (AC-21.2).
- **No auto-extraction** in v1 (AC-21.4) — the anti-poisoning stance. Auto-harvest is future-work (RL ladder).
- Every write logs a `memory_write` **event** (ADR-0005, AC-12.4) — provenance + an audit trail / rollback handle.

### Read-fresh + external edits (AC-14.2)

Memory is re-read from disk on each load — **not** baked into a resumed transcript. A resumed session sees the *latest* memory; an entry edited/deleted by hand out-of-band is honored on next load (no masking cache).

### Retrieval boundary (resolves OQ-F)

**v1 does NOT need retrieval beyond index + selective load.** Project-scoping keeps each index small; the always-loaded index + on-demand full-read is sufficient for realistic per-project learning volumes. Retrieval (embeddings / trajectory recall — RL rung 3) is **future-work**, triggered only if an index grows large enough to pressure context — at which point pruning comes first, retrieval second.

## Consequences

**Positive**
- Compounding value (stops repeating mistakes) with zero ML infra.
- Human-curated + inspectable + deletable → poisoning is correctable by hand; provenance on every entry.
- Markdown+index is greppable/diffable, consistent with the event-log philosophy (ADR-0005) and the Operator persona (US-14).
- Re-read-fresh decouples memory evolution from any one conversation.

**Negative / costs**
- Manual curation = the agent won't learn things nobody approves (by design — trades recall for safety).
- Index grows with entries → eventual context pressure; mitigated by project-scoping + pruning, retrieval deferred.
- A stale entry (code moved on) can mislead; mitigated by citing symbols (verifiable) and preferring durable conventions over volatile line numbers.

**Neutral / follow-ons**
- ADR-0006 compaction proposes harvested learnings into this lifecycle.
- `read_memory`/`write_memory` are Class R / Class X tools respectively (ADR-0004) — writes gated, reads free.
- Auto-reflection harvesting + retrieval are future-work (overview § 10).

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Auto-extract learnings** | Agent writes memory without approval | Poisoning risk — one wrong "always do X" misleads every future session; user chose curated (RD-9) |
| **Embeddings / vector store** | Semantic retrieval over many learnings | OOS for v1; index+load suffices at project scale; adds infra + a similarity-tuning surface |
| **Single tier** (project only, or global only) | Simpler | Loses the global-vs-project distinction the brainstorm wanted (user prefs vs repo facts) |
| **Bake memory into the transcript** | Memory travels with the session | Stale on resume; can't honor out-of-band edits (AC-14.2); couples memory to one conversation |
| **SQLite memory** | Queryable | Not hand-editable in a text editor (AC-14.1 fails); opaque to the Operator |

## Notes

- Resolves OQ-F: index+load for v1; retrieval is future-work gated on index-size pressure, pruning-first.
- The two-tier index+load pattern mirrors Claude Code's `~/.claude` memory model (a deliberate, proven prior).
- Entry timestamps captured at the boundary (no in-process clock) for reproducibility, consistent with ADR-0005.
