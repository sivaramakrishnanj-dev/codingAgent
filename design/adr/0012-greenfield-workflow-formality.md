---
adr: 0012
title: Greenfield workflow formality — full spec-driven structure
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0004, ADR-0003, ADR-0007]
spec_refs: [US-1, US-2, US-3, AC-1.1, AC-1.2, AC-1.3, AC-1.5, AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-3.1, AC-3.2, AC-3.3, RD-7, OQ-B]
---

# ADR-0012 — Greenfield workflow formality: full spec-driven structure

## Status

accepted (2026-06-15)

## Context

Greenfield mode (US-1/2/3, C3) takes a project from idea → requirements → design → tasks → implementation. RD-7 fixed that it persists requirements/design/tasks as markdown with approval gates between stages. **OQ-B** asks *how formal* that structure is: a lightweight scaffold or the full spec-driven ceremony (EARS acceptance criteria, numeric NFRs, ADRs, formal contracts, milestone/task breakdown with traceability). **User chose full spec-driven structure.**

There is a pleasing reflexivity here: greenfield mode is essentially the workflow that produced *this* design. The agent runs a designer playbook.

## Decision

**Greenfield mode implements the full spec-driven structure — the same phase-gated methodology used to design codingAgent itself.**

- **Phases + artifacts** (persisted as markdown in the *target* project, per RD-7):
  1. **Requirements** — personas + user stories → **EARS** acceptance criteria → numeric **NFRs**.
  2. **Design** — overview → architecture (+ **ADRs**) → data model → APIs → operations.
  3. **Formal contracts** — schemas, state machine, exit codes, contract tests, fixtures (when the project shape warrants).
  4. **Tasks** — milestones + task breakdown with **traceability** (each task → AC/NFR).
- **Per-sub-phase approval gates (AC-2.3, AC-1.5, AC-3.x).** The agent does not advance a phase without explicit developer approval; each approval is recorded (timestamped) in the artifact (AC-1.5). Implementation begins only after the design + task breakdown are approved (AC-2.3).
- **Traceability mandatory (AC-2.5).** Every task traces to ≥ 1 requirement; the methodology's chain (US → AC → NFR/ADR → task) is preserved.
- **Implementation (US-3, AC-3.x).** Tasks are implemented one at a time in breakdown order, each **verified** via build/test (ADR-0003) before the next is marked complete (AC-3.2, AC-3.3), bounded by `NFR-VERIFY-MAX-ITERATIONS`.
- **Gating before source writes (AC-1.3, AC-1.4).** While in the requirements/design dialogue, the agent does not write source — only design markdown — until the breakdown is approved.
- **Reuse the engine.** This is a **workflow driver** (C3) over the same Agent Loop (ADR-0001), tools (file/exec/memory), and permission gate (ADR-0004) — not a separate engine. The "phases + gates" are orchestration on top of the loop.

## Consequences

**Positive**
- Maximally rigorous, reviewable output — the project gets a real design baseline, not just code.
- Reflexive consistency: greenfield ≈ the designer methodology, so the agent's own design process is the reference implementation (dogfooding).
- Traceability + per-phase gates give the developer tight control and auditable artifacts (pairs with the event log, ADR-0005).

**Negative / costs**
- **Heavy**: many phases, many gates, many model turns — slow for a user who "just wants to start a project." This is the explicit tradeoff the user accepted over the lightweight scaffold.
- More surface to build + test in v1 (the full phase machinery) than a scaffold.
- Risk of ceremony fatigue → mitigated by the gates being approvals (fast yes/no), and the agent doing the drafting.

**Neutral / follow-ons**
- A lighter or configurable greenfield rigor (the rejected alternatives) remains a future option if the full ceremony proves too heavy in practice — the workflow-driver seam allows it.
- Phase 4 task breakdown will size the greenfield driver as a substantial milestone (it's effectively a methodology engine).
- Greenfield artifacts in the *target* project are separate from codingAgent's own `design/` — the agent writes them into the user's repo.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Lightweight scaffold** | requirements/design/tasks as plain markdown + approval gates, no EARS/NFR/ADR/contract ceremony | User chose full rigor; scaffold loses the traceability + formal-contract value. Kept as a possible future "light mode" |
| **Configurable (default light)** | Flag to opt into full structure | Doubles the greenfield surface to build/test in v1; user chose a single full path |
| **No greenfield in v1** (brownfield only) | Smallest scope | US-1/2/3 are accepted core-value requirements |

## Notes

- Resolves OQ-B: full spec-driven structure.
- The methodology mirrors the skill that built codingAgent — a useful reference and a source of prompt/playbook content for the driver.
- Because it's a workflow driver over the shared engine, greenfield inherits persistence, permission, sub-agents, and memory for free; its specific work is phase orchestration + the per-phase prompts/templates.
