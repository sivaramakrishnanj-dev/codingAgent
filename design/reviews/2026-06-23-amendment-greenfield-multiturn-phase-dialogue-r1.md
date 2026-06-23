---
review_id: amendment-greenfield-multiturn-phase-dialogue-r1
phase: amendment
triggered_by: T-3.2
request_id: DCR-2
raised_by: spec-driven-implementer
kind: architecture-update
approved_by_user_at: 2026-06-23T00:00:00+00:00
artifact: design/adr/0012-greenfield-workflow-formality.md, design/00-requirements.md, design/02-architecture.md, design/07-tasks.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-2 greenfield multi-turn phase dialogue + approve-to-finalize (r1)

## Outcome

Approved (Option A, user-approved 2026-06-23T00:00:00+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. The design baseline now records that each greenfield pre-approval
phase (requirements, design, tasks) is a **multi-turn conversation** that converges the deliverable, with
**approve = finalize** as the single gesture that captures + persists the converged deliverable and
advances; a non-approve answer keeps the phase conversation going (a refining turn), not persist-and-stop.
The D1 output-token-cap follow-on is folded in: the greenfield Converse request now sets an explicit
`inferenceConfig.maxTokens` (16384, configurable) so a large deliverable is not truncated at the default
4096 cap.

## Trigger

T-3.2 (raised by `spec-driven-implementer`). DCR-1 (driver-authored persistence) was necessary and landed
correctly — requirements/design/tasks now write real content (3432/1720/2365 bytes live). But the live G3
smoke test *after* DCR-1 proved a deeper defect: each greenfield phase ran as **one** LLM turn. Given a
terse idea, the requirements-phase model correctly does AC-1.1 (asks clarifying questions) instead of
inventing requirements — so the driver persisted the model's *questions* as `00-requirements.md`. Design
read questions → more questions; tasks read questions → honestly refused to fabricate; AC-2.5 correctly
rejected 0 tasks. Every component behaved correctly; the **interaction shape** was wrong — a phase that
needs a multi-turn conversation to converge was being run as a single turn, so the model never finished
shaping the deliverable before it was captured. Two contributing facts: (1) the fresh-conversation-per-phase
shape meant the model could not see its own prior turns *within* a phase (in-phase transcript
discontinuity, on top of the cross-phase discontinuity DCR-1 already addressed); (2) the
single-turn-per-phase contract was the wrong shape for a deliverable that converges conversationally.

## Chosen option

**Option A — Multi-turn phase dialogue with approve-to-finalize.** Each greenfield pre-approval phase
becomes a multi-turn conversation; the developer converses across several REPL turns to shape the phase's
deliverable; the model refines each round and may ask AC-1.1 clarifying questions (it now has room to). The
phase transcript carries across turns **within** the phase (fix the in-phase discontinuity). **Finalize =
approve** (reuse the existing `ArtifactApprovalGate` / `InteractiveGreenfieldApproval`): each round the
developer is offered the approval prompt; on **approve**, the driver captures the model's latest substantive
deliverable text in the phase conversation, persists it via `GreenfieldArtifactStore.write()` (the DCR-1
driver-authored path, kept), records the AC-1.5 approval timestamp, and advances. A **non-approve** answer
keeps the phase conversation going (another refining turn — also the AC-2.4 revise path); it does NOT
persist-and-stop. Later phases inject approved earlier-phase artifacts into their conversation (DCR-1
cross-phase continuity kept). Preserved: AC-1.4 (source-write Class X tools structurally withheld across
every pre-approval turn), driver-authored persistence (DCR-1), AC-2.5 traceability on the written tasks
artifact, AC-1.5 timestamp, AC-2.3 per-phase approval. **D1 follow-on folded in:** set
`inferenceConfig.maxTokens = 16384` (configurable) on the greenfield Converse request.

Option B (keep the single-turn-per-phase shape; lead the prompt harder, forbid clarifying questions, demand
a full doc in one shot) was the rejected alternative — it fights AC-1.1 and a single turn cannot converge a
deliverable that genuinely needs back-and-forth; live G3 (after DCR-1) proved the single turn captures
*questions*, not a deliverable.

## Scope reviewed (files edited)

| File | Change |
|---|---|
| `design/adr/0012-greenfield-workflow-formality.md` | **THE main change.** New Decision bullet "Multi-turn phase dialogue, approve = finalize" (AC-1.1/2.3/1.5/2.4); the "Driver-authored phase deliverables" bullet refined — capture-and-persist trigger moves from each phase's first `END_TURN` to **phase approval** (so the *converged* deliverable is written); DCR-1 cross-phase injection kept and re-labelled (cross-phase) alongside the new in-phase continuity; AC-1.4 reinforced to apply across every pre-approval turn; new Decision bullet "Greenfield-phase output-token budget" (D1: `inferenceConfig.maxTokens = 16384`, configurable, distinct from `NFR-OUTPUT-MAX-INLINE`); Consequences notes (multi-turn matches convergence; cost; output-budget orthogonal fix); Alternatives row (DCR-2 Option B single-turn rejected); Notes amendment entry (DCR-2); Status line; front-matter (`amended`, `review`, `NFR-MODEL-DEFAULT` added to refs). |
| `design/00-requirements.md` | AC-1.1 (multi-turn requirements dialogue; clarifying questions + refine each round; ADR-0012 added to Refs). AC-1.5 (confirmation = finalize signal; captures converged deliverable, persists, advances; non-approval keeps dialogue going; ADR-0012 added to Refs). AC-2.3 (multi-turn; approval offered each round = finalize; ADR-0012 added). AC-2.4 (non-approval realized as another refining turn — not persist-and-stop; ADR-0012 added). AC-2.5 (written tasks artifact now holds the *converged* breakdown). EARS Type tags unchanged (Ev/Ev/Ev/Un/U); all US/RD refs intact; front-matter `amended_by: [DCR-1, DCR-2]`. |
| `design/02-architecture.md` | § 1.2 C3 row: multi-turn phase conversation, in-phase transcript carry, approve = finalize, capture-and-persist at approval, non-approve = refining turn, DCR-1 cross-phase injection retained; invariant cell updated (source tools withheld every pre-approval turn; non-approve never finalizes; persistence driver-guaranteed at approval). § 2.1 new "Output-token budget on the Converse request" note (C4 → Model Client; `inferenceConfig.maxTokens` 16K configurable; default-4096 truncation rationale; distinct from `NFR-OUTPUT-MAX-INLINE`). front-matter `amended_by: [DCR-1, DCR-2]`, `review`. |
| `design/07-tasks.md` | T-3.1 row (multi-turn phase dialogue + approve-to-finalize note; Refs add AC-2.4; Verify notes convergence-before-finalize + no-source-write-any-turn). T-3.2 row (approve-to-finalize capture at approval writes the converged deliverable; output-token budget `maxTokens=16384` note; Component add C4; Refs add AC-1.1/2.3/2.4; Verify adds no-MAX_TOKENS-truncation). T-3.3 row (implement loop now over the converged + approved breakdown). front-matter `amended_by: [DCR-1, DCR-2]`, `review`. |

## Constraints check (all respected)

- **No files touched outside `scope_of_design_edit`** except this amendment review file and
  `design/design-progress.md` (mandated by `shared.md` § 9.2). No mechanical-ripple edits to other design
  files were needed (no AC renumber, no ADR rename, no schema change). ✓
- **Phase 1a user stories (US-1/US-2/US-3) untouched.** No story-map change; only AC bodies in scope were
  edited. ✓
- **Traceability preserved.** Every edited AC still references a US (US-1 for AC-1.1/1.5; US-2 for
  AC-2.3/2.4/2.5) plus ADR-0012; no NFR reference broken; T-3.1/T-3.2/T-3.3 still trace to valid AC + ADR
  symbols; the US → AC → NFR/ADR → task chain is intact. ✓
- **DCR-1 NOT reverted.** Driver-authored persistence via `GreenfieldArtifactStore.write()` remains the
  persistence mechanism; cross-phase artifact injection is retained. DCR-2 changes the per-phase
  *interaction shape* (single-turn → multi-turn) and the persistence *trigger* (first END_TURN → approval),
  not *who* persists the deliverable. ✓
- **AC-1.4 source-write withholding structurally enforced.** Source-write Class X tools (`write_file` /
  `edit_file`) stay withheld from the pre-approval phase registry across *every* turn of the multi-turn
  dialogue; the driver's in-code write stays `design/`-confined. Stated in AC-1.4's existing body (unchanged,
  still accurate for a multi-turn dialogue), reinforced in ADR-0012's AC-1.4 bullet and C3's invariant. ✓
- **EARS form intact.** AC-1.1 (Ev), AC-1.5 (Ev), AC-2.3 (Ev), AC-2.4 (Un), AC-2.5 (U) lead clauses
  unchanged; mechanism notes appended without altering the EARS template sentence. ✓
- **D1 output-token-cap fix: value + rationale recorded.** 16384 tokens (16K), configurable; rationale (a
  full phase deliverable is realistically a few thousand tokens, occasionally ~8K; 16K is ~2–4× headroom;
  within the Claude Opus 4.x output ceiling — 128K for 4.8/4.7/4.6, 64K for 4.5, verified 2026-06-23 from
  platform.claude.com; small enough not to invite runaway generation). Recorded in ADR-0012, `02-architecture.md`
  § 2.1, and the T-3.2 row. Framed as distinct from `NFR-OUTPUT-MAX-INLINE` (tool-output disposal, a
  different axis). No new NFR symbol minted — per user direction the D1 fix rides along this
  architecture-update DCR. ✓
- **Single amendment commit.** All edits + this review file + `design-progress.md` in one commit. ✓

## Ripple detection

Grepped the whole `design/` tree for every symbol in `spec_refs_touched` (AC-1.1, AC-1.4, AC-1.5, AC-2.1,
AC-2.3, AC-2.4, AC-2.5, ADR-0012, C3, NFR-OUTPUT-MAX-INLINE) plus `multi-turn`, `END_TURN`, `single-turn`,
`maxTokens`, `inferenceConfig`, `MAX_TOKENS`.

- **Resolved mechanically:** none required. The amendment refines mechanism + interaction-shape prose; it
  renumbers no AC, renames no ADR, and changes no schema field, so no cross-reference in `01`–`05`,
  `06-formal/`, or other task rows needed updating. The edited symbols are all touched at their definition
  sites (the four in-scope files). Outside-scope ADR-0012 references are **title-level only** and unchanged:
  `design/adr/README.md` (ADR-0012 title + "C3, OQ-B" resolves row) and `02-architecture.md` § 9 ADR-queue
  row (title + "C3, OQ-B"). `03-data-model.md` § 4's `StopReason` enum lists `MAX_TOKENS` as an enum value —
  that definition is unchanged and correct (the D1 fix sets a request-side `maxTokens` budget; it does not
  alter the response-side `StopReason` enum). `04-apis.md` § 3 already documents
  `inferenceConfig (maxTokens/…)` as part of the request shape — DCR-2 sets an actual *value* on the
  greenfield path, which the in-scope `02-architecture.md` § 2.1 note records; no `04-apis.md` edit warranted.
- **Unresolved (semantic — flagged, not edited):**
  - `design/open-questions.md` **D8 discussion item** (AC-9.4 / ADR-0004 gate-decision-table `write_artifact`
    auto-approve carve-out): a **pre-existing** open question, explicitly **NOT part of DCR-2** (the DCR
    constraints say do not edit ADR-0004's gate-decision table here). With persistence driver-authored
    (DCR-1) and the phase now multi-turn (DCR-2), `write_artifact` is even more clearly a vestigial-tool
    concern; the AC-9.4 carve-out clarification remains a separate `ac-update` candidate for the user. Left
    untouched.
  - `design/07-tasks-progress.md` (coordinator-owned Phase 5 state) carries historical narrative in its
    resolved-task log that describes the prior single-turn / first-END_TURN persistence shape (DCR-1's
    state as of when it landed). This is an **audit trail**, not a live spec cross-reference — it correctly
    records what happened at that time and must not be retro-edited. Out of scope (coordinator territory) and
    intentionally not edited. Flagged for awareness only; not a defect.

## Consistency checks

- Schemas validated against fixtures: **not applicable** (no `06-formal/` schema touched).
- Contract-tests index still consistent: **not applicable** (no `06-formal/contract-tests.md` touched; the
  amendment changes the per-phase interaction shape + persistence trigger + the request output-token budget,
  not any CT's schema/AC reference). Note: `contract-tests.md` § 6 already flags greenfield-workflow
  phase-gating as a Phase-4-scoped gap with a dedicated CT to be authored when M3 is scoped (the T-3.1
  "phase-gating CT"); DCR-2 makes that CT's subject multi-turn, which the implementer realizes at T-3.x —
  no contract-test-index edit is needed at amendment time.

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No reviewer comments — amendment executed per the user-approved Option A. | — |

## Flagged for later

The `open-questions.md` D8 item (AC-9.4 / ADR-0004 `write_artifact` carve-out clarification) is independent
of this DCR and remains the user's call to raise as its own `ac-update` amendment. The `07-tasks-progress.md`
historical-log mentions of the single-turn shape are an intentional audit trail and are correctly left
frozen.
