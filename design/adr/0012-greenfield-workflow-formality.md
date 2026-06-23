---
adr: 0012
title: Greenfield workflow formality — full spec-driven structure
status: accepted
date: 2026-06-15
amended: [DCR-1 (2026-06-23) — driver-authored phase-deliverable persistence, DCR-2 (2026-06-23) — multi-turn phase dialogue + approve-to-finalize]
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0004, ADR-0003, ADR-0007, ADR-0001]
spec_refs: [US-1, US-2, US-3, AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-1.5, AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-3.1, AC-3.2, AC-3.3, RD-7, OQ-B, NFR-MODEL-DEFAULT]
review: reviews/2026-06-23-amendment-greenfield-multiturn-phase-dialogue-r1.md
---

# ADR-0012 — Greenfield workflow formality: full spec-driven structure

## Status

accepted (2026-06-15); amended 2026-06-23 (DCR-1) — phase-deliverable persistence is now driver-authored in code rather than model-tool-dependent; amended 2026-06-23 (DCR-2) — each pre-approval phase is a **multi-turn conversation** that converges the deliverable, **approve = finalize**, and the greenfield Converse request sets an explicit output-token budget (see Decision + Notes).

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
- **Multi-turn phase dialogue, approve = finalize (AC-1.1, AC-2.3, AC-1.5, AC-2.4 — amended DCR-2, 2026-06-23).** Each pre-approval phase (requirements, design, tasks) is a **multi-turn conversation**, not a single model turn. The developer converses with the agent across several REPL turns to shape the phase's deliverable; the model refines the deliverable each round and may ask clarifying questions (AC-1.1 — it now has room to, given a terse idea). **The phase transcript carries across turns *within* the phase** — the model sees its own prior turns within the phase, fixing the fresh-conversation-per-phase in-phase discontinuity that made the single-turn shape capture *questions* rather than a converged deliverable. **Finalize = approve, one gesture** (reuse the existing `ArtifactApprovalGate` / `InteractiveGreenfieldApproval`): each round the developer is offered the approval prompt; a **non-approve** answer is *not* persist-and-stop — it keeps the phase conversation going (another refining turn, which also satisfies AC-2.4's revise-and-re-request). When the developer **approves**, that approval IS the finalize signal: the driver captures the model's latest substantive deliverable text in the phase conversation, persists it (the driver-authored path below), records the AC-1.5 approval timestamp, and advances to the next phase (AC-2.3 — implementation begins only after the design + task breakdown are approved).
- **Driver-authored phase deliverables (AC-1.2, AC-2.1 — amended DCR-1, 2026-06-23; persistence trigger refined by DCR-2).** The greenfield workflow driver (C3) **authors each pre-approval phase deliverable deterministically in code**, not via a model-emitted tool call. On **phase approval** (the finalize signal above; DCR-1 originally triggered this on each phase's first `END_TURN`, DCR-2 moves the capture-and-persist trigger to approval so the *converged* deliverable is what is written), the driver captures the model's latest substantive deliverable text in the phase conversation and writes it to the phase artifact (the target project's `design/00-requirements.md` / `design/01-design.md` / `design/02-tasks.md`) via `GreenfieldArtifactStore.write()` — a truncating write of the composed content; the `ArtifactApprovalGate` then appends the AC-1.5 approval stamp. Persistence is therefore **driver-guaranteed** — it does not depend on the live model reliably emitting a `write_artifact` `toolUse`, which empirically it does not (the model answers in prose and stops; see Context). The `write_artifact` design-doc tool stays **registered/available** in the pre-approval registry (C7) but is **optional — no longer the persistence mechanism**.
  - **Cross-phase transcript continuity (DCR-1, kept).** Later phases **inject the approved earlier-phase artifact content into their phase's conversation context** (requirements → design → tasks), so design and tasks are authored against the actual approved upstream content rather than a discontinuous fresh start. DCR-2 adds *in-phase* continuity (the phase's own turns carry within the phase); DCR-1's *cross-phase* injection is retained unchanged.
  - **AC-1.4 preserved.** The driver's in-code artifact write is confined to the target project's `design/` markdown; **source-write Class X tools (`write_file` / `edit_file`) stay withheld** from the pre-approval phase registry across *every* turn of the multi-turn dialogue, until the breakdown is approved. The change is to *who persists the deliverable* (driver, in code) and *how the phase converges* (multi-turn) — not to *what may be written before approval* (still only `design/` markdown).
- **Greenfield-phase output-token budget (DCR-2, 2026-06-23 — D1 follow-on folded in).** The greenfield phase Converse request sets an explicit `inferenceConfig.maxTokens` so a full requirements / design / tasks deliverable is not truncated at the backend's default 4096 output-token cap (a live run observed `stopReason=MAX_TOKENS` mid-deliverable; the Converse request previously set no `inferenceConfig`, so the backend default applied — see `02-architecture.md` § 2.1 + C4). **Chosen value: 16384 tokens (16K), configurable.** Rationale: a full phase deliverable is realistically a few thousand tokens of markdown (occasionally up to ~8K for a rich design doc), so 16K is ~2–4× headroom; it stays well within the Claude Opus 4.x output ceiling across the whole family (Opus 4.8/4.7/4.6 = 128K max output, Opus 4.5 = 64K, legacy 4.1 = 32K — verified 2026-06-23 from platform.claude.com), and it is small enough not to invite runaway generation. **This is the model's output-token cap for the generation, distinct from `NFR-OUTPUT-MAX-INLINE`** (16 KB), which governs how much *tool/command output* enters context (a disposal threshold, ADR-0006) — different axis, coincidentally similar magnitude.
- **Traceability mandatory (AC-2.5).** Every task traces to ≥ 1 requirement; the methodology's chain (US → AC → NFR/ADR → task) is preserved. Traceability is verified against the **driver-written** task-breakdown artifact.
- **Implementation (US-3, AC-3.x).** Tasks are implemented one at a time in breakdown order, each **verified** via build/test (ADR-0003) before the next is marked complete (AC-3.2, AC-3.3), bounded by `NFR-VERIFY-MAX-ITERATIONS`.
- **Gating before source writes (AC-1.3, AC-1.4).** Across every turn of the requirements/design multi-turn dialogue, the agent does not write source — only design markdown — until the breakdown is approved. (The "only design markdown" write is the driver-authored persistence above, performed at phase approval; source-write tools stay withheld for the whole pre-approval dialogue.)
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
- **Driver-authored persistence (DCR-1) shifts artifact composition from model to driver:** the model still *produces* the deliverable prose, but the driver *captures and writes* it. This makes persistence a real, mock-stable contract a test can assert deterministically (the prior model-tool-dependent contract was only live-observable and empirically unmet after four fixes — T-3.2-RD-D6 through D9). The `write_artifact` tool becomes vestigial/optional rather than the documented mechanism; it is left registered so a future model that reliably calls it still functions, and so the existing tool/store/dispatch plumbing (proven correct by the D9 probe) is not removed.
- **Multi-turn phase dialogue (DCR-2) matches how a phase actually converges.** The single-turn-per-phase shape was the wrong contract for a deliverable that needs conversational back-and-forth: given a terse idea, the requirements model correctly does AC-1.1 clarification and the single-turn driver captured the *questions* as the artifact (live G3 after DCR-1: requirements/design/tasks wrote 3432/1720/2365 bytes of questions, and AC-2.5 correctly rejected 0 tasks — every component behaving correctly, the interaction shape wrong). Multi-turn gives the model room to converge; approve-to-finalize captures the converged deliverable. **Cost:** a larger interaction surface to build + test (multi-turn loop state, in-phase transcript carry); approve-to-finalize overloads the approval gesture — accepted because approval *is* the natural finalize point and a non-approve turn already means "keep going" (AC-2.4).
- **Output-token budget (DCR-2) is a small, orthogonal Converse-wiring fix** ridden along on the same greenfield request path: setting `inferenceConfig.maxTokens` (16K, configurable) prevents the default-4096-cap truncation of a large deliverable that even driver-authored capture would otherwise persist in truncated form.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Lightweight scaffold** | requirements/design/tasks as plain markdown + approval gates, no EARS/NFR/ADR/contract ceremony | User chose full rigor; scaffold loses the traceability + formal-contract value. Kept as a possible future "light mode" |
| **Configurable (default light)** | Flag to opt into full structure | Doubles the greenfield surface to build/test in v1; user chose a single full path |
| **No greenfield in v1** (brownfield only) | Smallest scope | US-1/2/3 are accepted core-value requirements |
| **Single-turn-per-phase + harder prompt** (DCR-2 Option B) | Keep one model turn per phase; lead the prompt harder, forbid clarifying questions, demand a full doc in one shot | Fights AC-1.1 (the model *should* ask clarifying questions on a terse idea); a single turn cannot converge a deliverable that genuinely needs back-and-forth — live G3 (after DCR-1) proved the single turn captures *questions*, not a deliverable. Rejected in favour of multi-turn dialogue (DCR-2 Option A, the Decision above). |

## Notes

- Resolves OQ-B: full spec-driven structure.
- The methodology mirrors the skill that built codingAgent — a useful reference and a source of prompt/playbook content for the driver.
- Because it's a workflow driver over the shared engine, greenfield inherits persistence, permission, sub-agents, and memory for free; its specific work is phase orchestration + the per-phase prompts/templates.
- **Amended 2026-06-23 (DCR-1, T-3.2-RD-D10).** Phase-deliverable persistence is now driver-authored in code (`GreenfieldArtifactStore.write()`) rather than dependent on a model-emitted `write_artifact` `toolUse`; later phases inject approved earlier-phase artifacts into their prompt for transcript continuity; AC-1.4 design/-confinement is preserved (source-write tools stay withheld pre-approval). See the Decision section's "Driver-authored phase deliverables" bullet and `02-architecture.md` § 1.2 (C3, C7). The model-tool-dependent persistence (the prior mechanism) was kept as the rejected Option B in the DCR — empirically unproven after four fixes.
- **Amended 2026-06-23 (DCR-2, T-3.2).** DCR-1 landed correctly but the live G3 run after it proved a deeper defect: each pre-approval phase ran as a *single* model turn, so the driver captured the model's AC-1.1 clarifying *questions* rather than a converged deliverable. DCR-2 makes each pre-approval phase a **multi-turn conversation** (in-phase transcript carries within the phase) with **approve = finalize** (a non-approve answer keeps the conversation going — another refining turn — rather than persist-and-stop; the driver-authored capture-and-persist trigger moves from each phase's first `END_TURN` to phase approval, so the *converged* deliverable is what is written). DCR-1's cross-phase artifact injection, driver-authored persistence, AC-1.4 source-withholding, AC-2.5 traceability on the written tasks artifact, AC-1.5 timestamp, and AC-2.3 per-phase approval are all retained. The single-turn shape (Option B — coax a full deliverable from one turn) was rejected: it fights AC-1.1 and a single turn cannot converge a deliverable that genuinely needs back-and-forth. **Also folded in (D1 follow-on):** the greenfield Converse request now sets `inferenceConfig.maxTokens = 16384` (configurable) so a large deliverable is not truncated at the default 4096 cap. See the Decision section's "Multi-turn phase dialogue", "Driver-authored phase deliverables", and "Greenfield-phase output-token budget" bullets, and `02-architecture.md` § 1.2 (C3) + § 2.1 (C4 output budget).
