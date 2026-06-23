---
review_id: amendment-greenfield-driver-authored-persistence-r1
phase: amendment
triggered_by: T-3.2-RD-D10
request_id: DCR-1
raised_by: spec-driven-task-builder
kind: architecture-update
approved_by_user_at: 2026-06-23T12:12:58+00:00
artifact: design/00-requirements.md, design/02-architecture.md, design/adr/0012-greenfield-workflow-formality.md, design/07-tasks.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-1 greenfield driver-authored phase-deliverable persistence (r1)

## Outcome

Approved (Option A, user-approved 2026-06-23T12:12:58+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. The design baseline now records that greenfield phase-deliverable
persistence is **driver-guaranteed in code**, not dependent on a model-emitted `write_artifact` tool call.

## Trigger

T-3.2-RD-D10 (raised by `spec-driven-task-builder`). Greenfield artifact persistence (AC-1.2 requirements /
AC-2.1 design+tasks markdown) was architected to depend on the live model emitting a `write_artifact`
Class-X `toolUse` during each pre-approval phase. Four successive fixes landed (T-3.2-RD-D6 approval, D7
prompt, D8 approval-contention, D9 inputSchema) and all PASSED their mocked task-builder tests — but live
ground truth (latest clean G3 run, D9 in place) shows the model reaches `stopReason=END_TURN` in each
pre-approval phase WITHOUT ever emitting a `write_artifact` `toolUse`: it answers in prose and stops.
`write_artifact` executes zero times across the whole greenfield run; the only `GreenfieldArtifactStore`
activity is the gate's approval-line stamp. So `design/00-requirements.md` + `design/01-design.md` (in the
TARGET project) hold only approval stamps, `design/02-tasks.md` is never created, AC-2.5 traceability
correctly refuses to find tasks, and greenfield never reaches implement. The D9 probe proved the plumbing
is correct when a `write_artifact` `toolUse` IS scripted (dispatch → tool → store → disk works; session
reaches implement). The remaining gap is empirical and model-behavioral — a defect class the mocked tests
cannot catch by construction (they script the tool_use the model never actually emits).

## Chosen option

**Option A — Driver-authored deliverables.** The `GreenfieldDriver` (C3) authors each pre-approval phase
deliverable deterministically: on each phase's `END_TURN`, the driver captures the model's final
deliverable prose and writes it to the phase artifact (target project's `design/00-requirements.md` /
`design/01-design.md` / `design/02-tasks.md`) via `GreenfieldArtifactStore.write()` in code (truncating
write of the composed content); the `ArtifactApprovalGate` then appends the AC-1.5 stamp. Later phases
inject the approved earlier-phase artifact content into their phase prompt to fix the
fresh-conversation-per-phase transcript discontinuity. `write_artifact` stays registered/available but is
no longer the persistence path. AC-1.4 (design/-confined; source-write tools withheld) preserved.

Option B (keep model-tool-dependent persistence; iterate prompt/Converse wiring / `toolChoice`) was the
rejected alternative — empirically unproven after four fixes, no mock-stable contract, ongoing spend risk.

## Scope reviewed (files edited)

| File | Change |
|---|---|
| `design/00-requirements.md` | AC-1.2 / AC-2.1 note persistence is driver-guaranteed (ADR-0012), not model-tool-dependent; AC-2.5 verifies traceability against the driver-written tasks artifact; AC-1.4 reinforced (design/-confinement preserved, source-write tools withheld). EARS Type tags (U/Ev/St/U) and US/RD refs all intact; ADR-0012 added to Refs. |
| `design/02-architecture.md` § 1.2 | C3 responsibility + invariant: driver authors deliverables deterministically via `GreenfieldArtifactStore.write()`, injects approved earlier artifacts into later prompts, persistence driver-guaranteed. C7 note: `write_artifact` registered/available but optional, not the persistence path. |
| `design/adr/0012-greenfield-workflow-formality.md` | New Decision bullet "Driver-authored phase deliverables" (mechanism + transcript continuity + AC-1.4 preservation); gating bullet cross-ref; Consequences note (composition shifts model→driver, mock-stable contract, `write_artifact` vestigial); Notes amendment entry; Status + front-matter (`amended`, `review`, AC-1.4 + ADR-0001 added to refs). |
| `design/07-tasks.md` | T-3.2 row: driver-authored persistence note; Refs add AC-1.4 + ADR-0012; Verify column notes driver-guaranteed write + traceability against the written artifact. |

## Constraints check (all respected)

- **No files touched outside `scope_of_design_edit`** except this amendment review file and `design/design-progress.md` (mandated by `shared.md` § 9.2). No mechanical-ripple edits to other design files were needed (no AC renumber, no ADR rename, no schema change). ✓
- **Phase 1a user stories (US-*) untouched.** No story-map change. ✓
- **Traceability preserved.** Every edited AC still references a US/RD (RD-7, RD-4, US-2) with ADR-0012 added; T-3.2 still traces to AC-1.2/1.4/1.5/2.1/2.5 + RD-7; no NFR reference broken. ✓
- **EARS form intact.** AC-1.2 (U), AC-2.1 (Ev), AC-1.4 (St), AC-2.5 (U) lead clauses unchanged; mechanism notes appended without altering the EARS template sentence. ✓
- **AC-1.4 design/-confinement preserved.** Greenfield pre-approval phases write only `design/` markdown (now via the driver, in code); source-write Class X tools stay withheld. The amended ADR-0012 and AC-1.4 both state this explicitly. ✓
- **Single amendment commit.** All edits + this review file + `design-progress.md` in one commit. ✓

## Ripple detection

Grepped the whole `design/` tree for every symbol in `spec_refs_touched` (AC-1.2, AC-1.4, AC-1.5, AC-2.1,
AC-2.5, ADR-0012, C3, C7).

- **Resolved mechanically:** none required. The amendment adds clarifying mechanism prose; it renumbers
  no AC, renames no ADR, and changes no schema field, so no cross-reference in `01`–`05`, `06-formal/`, or
  other task rows needed updating. All four touched symbols are edited at their definition sites (the four
  in-scope files); downstream references (e.g. `04-apis.md` § 3's generic C7 registry-entry contract,
  `adr/README.md`'s ADR-0012 title row, `02-architecture.md` § 9 ADR-queue row) cite the title/contract,
  which are unchanged — no mechanical edit warranted.
- **Unresolved (semantic — flagged, not edited):**
  - `design/open-questions.md` D8 discussion item (AC-9.4 / ADR-0004 gate decision table): the prior fixes
    introduced a `write_artifact` PermissionGate auto-approve carve-out. With persistence now
    driver-authored, `write_artifact` is optional and the AC-9.4 carve-out becomes even more clearly a
    vestigial-tool concern. This is a **pre-existing open question explicitly NOT part of DCR-1** — left as
    a separate ac-update candidate for the user.
  - `design/open-questions.md` D1 (output-token-cap nfr-update): recorded by the main agent as explicitly
    orthogonal to DCR-1 (the default 4096 maxTokens cap can truncate driver-captured END_TURN prose). Not
    in this DCR's scope; left as a separate nfr-update follow-on candidate.

## Consistency checks

- Schemas validated against fixtures: not applicable (no `06-formal/` schema touched).
- Contract-tests index still consistent: not applicable (no `06-formal/contract-tests.md` touched; the
  amendment changes the persistence *mechanism*, not any CT's schema/AC reference).

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No reviewer comments — amendment executed per the user-approved Option A. | — |

## Flagged for later

The two `open-questions.md` items above (AC-9.4 `write_artifact` carve-out clarification; output-token-cap
nfr-update) are independent of this DCR and remain the user's call to raise as their own amendments.
