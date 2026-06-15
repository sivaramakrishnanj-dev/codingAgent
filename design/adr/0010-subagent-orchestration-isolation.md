---
adr: 0010
title: Sub-agent orchestration + isolation (in-process)
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0005, ADR-0004, ADR-0006, ADR-0001]
spec_refs: [US-17, AC-17.1, AC-17.2, AC-17.3, AC-17.4, AC-17.5, AC-17.6, AC-10.6, RD-5, NFR-SUBAGENT-MAX, NFR-SUBAGENT-BUDGET, OQ-C]
---

# ADR-0010 — Sub-agent orchestration + isolation (in-process)

## Status

accepted (2026-06-15)

## Context

Sub-agents are the **spatial** context lever (brainstorm "three levers"): a worker burns *its own* window on a scoped subtask and returns a short summary, keeping the parent lean (US-17). Requirements fix: own context window per child (AC-17.2), ≤ `NFR-SUBAGENT-MAX` concurrent (AC-17.3, default 1), parent gets only the summary (AC-17.4), lineage logged (AC-17.5), failure/over-budget returns to parent (AC-17.6), and children **do not inherit remembered grants** (AC-10.6, RD-5). This ADR resolves **OQ-C** — the isolation boundary: in-process vs separate process. **User chose in-process.**

## Decision

**We will run sub-agents in-process: each is a nested Agent Loop on its own thread with its own isolated context (`messages[]`), sharing the JVM, event-log writer, and config with the parent.**

- **Spawn.** The parent emits a `spawn_subagent` tool call (Class X, gated — ADR-0004) with a scoped prompt + budget. The orchestrator (C13) starts a **new nested Agent Loop** (ADR-0001) on a worker thread, with a **fresh context** (its own `messages[]`, its own system prompt) — logical isolation, same JVM.
- **Isolation boundary (OQ-C = in-process).** Isolation is **contextual, not OS-level**: separate context objects, but shared address space. A child cannot see the parent's `messages[]` and vice-versa; it returns only a summary block (AC-17.4). Chosen for: cheap/fast spawn, shared event-log writer (one persistence path), shared config — fits the N=1 default and the "seam now, parallelism later" stance.
- **Concurrency (AC-17.3).** ≤ `NFR-SUBAGENT-MAX` concurrent children (default **1** — effectively sequential isolation in v1). N>1 = a bounded thread pool; the seam exists, the parallelism is config-gated. The parent **blocks on** (N=1) or **joins** (N>1) child results.
- **Own context + model (AC-17.2).** Each child has its own context window and may run a different/cheaper model (`NFR-MODEL-SUBAGENT`, ADR-0002 profile resolved independently).
- **Budget (AC-17.6, `NFR-SUBAGENT-BUDGET`).** A child gets its own context window + a wall-clock cap (600 s default). Exceeding it → the child is stopped and a **failure result** returned to the parent, which decides a next step (never hangs).
- **Lineage (AC-17.5).** Each child is its own session (ADR-0005) linked `spawned-by` the parent; `subagent_spawn`/`subagent_result` events logged in the parent's log; the child's own JSONL holds its full transcript (history/observability preserved).
- **No inherited grants (AC-10.6, RD-5).** The child runs the configured permission mode **fresh** — it does not inherit the parent's remembered `ASK_ONCE_THEN_REMEMBER` grants. Prevents a child riding a broad parent grant into an unexpected action.

## Consequences

**Positive**
- Cheap, fast spawn; no IPC/serialization; one event-log writer and one config path (simplicity).
- Context isolation (the actual goal — keep the parent lean) is fully achieved without OS-process overhead.
- Fits N=1 default exactly; scaling to a thread pool later is a contained change.
- Each child is a first-class session → full history + lineage for free (ADR-0005).

**Negative / costs**
- **No fault isolation**: a child that crashes the JVM (OOM, fatal error) takes the parent down. Mitigated: children run bounded budgets; a *managed* failure returns cleanly; only catastrophic JVM-level faults are shared. Accepted for v1's N=1; a future fork-based backend is the escalation if fault isolation becomes necessary.
- **No resource isolation**: a child shares heap/CPU with the parent. Bounded by N=1 default + budgets.
- Thread-safety discipline required on the shared event-log writer (append must be synchronized) — a real implementation constraint, called out for Phase 5.

**Neutral / follow-ons**
- The orchestrator is the seam where a **process-fork backend** could be added later (the rejected alternative) if fault/resource isolation is needed — the `spawn_subagent` contract wouldn't change.
- Parallel execution (N>1) deferred behind config; concurrent-file-write conflict handling becomes real only then (and is why v1 ships N=1).

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Separate process (fork child CLI)** | Each child its own JVM; IPC; full fault+resource isolation | Heavier: process spawn cost, IPC serialization, separate credential/log/config plumbing. Overkill for N=1; user chose in-process. Retained as the future escalation if isolation needs grow |
| **No sub-agents in v1** | Smallest scope | US-17 is accepted + it's the context-survival lever; the seam is cheap in-process |
| **Unbounded concurrency** | Max throughput | Concurrent file-write conflicts + resource contention; v1 N=1 sidesteps both |

## Notes

- Resolves OQ-C: in-process (thread + isolated context). The architecture (`02-architecture.md` § 4) already notes single-threaded-per-conversation with sub-agents as the only parallelism seam — this ADR confirms the mechanism.
- The shared event-log writer must be concurrency-safe (synchronized append) once N>1 is enabled — Phase 5 implementation constraint.
- "Own context window" is logical (separate `messages[]`), not a separate token budget pool from Bedrock's view — each child's calls are independent Converse requests.
