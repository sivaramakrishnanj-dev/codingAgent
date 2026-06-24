---
review_id: amendment-greenfield-playbook-traceability-vocabulary-r1
phase: amendment
triggered_by: T-3.5
request_id: DCR-5
raised_by: spec-driven-task-builder
kind: ac-update
approved_by_user_at: 2026-06-24T00:00:00+00:00
artifact: design/00-requirements.md, design/adr/0012-greenfield-workflow-formality.md, design/07-tasks.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-5 align greenfield playbook prompts to the strict traceability gate's vocabulary (ac-update + adr-clarification) (r1)

## Outcome

Approved (Option (a), user-approved 2026-06-24T00:00:00+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. A greenfield project could not satisfy its **own** AC-2.5
traceability gate: `TaskTraceability` (the `ArtifactApprovalGate`'s tasks-phase check, ADR-0012) is
**strict by design** — `TASK_LINE` requires a `T-<n>` / `T-<n>.<m>` id (hyphen mandatory) and
`REQUIREMENT_REF` accepts only `US-<n>` / `AC-<n>(.<m>)` / `NFR-<NAME>` / `RD-<n>` / `INV-<n>` — but the
`GreenfieldPlaybook` per-phase prompt (C3) never taught the model that vocabulary, so a live model emitted
`T1`/`T2`/`T10` ids citing `R1`–`R6` refs and the strict gate correctly refused `0-tasks`/untraceable.
DCR-5 **fixes the prompt and keeps the gate strict**: the contract that the greenfield playbook prompt
**emits** the gate's vocabulary on both pre-approval authoring phases is folded into **AC-2.2 + AC-2.5** and
recorded in **ADR-0012**, and an M3 task **T-3.5** (deps T-3.2) is appended to implement the prompt change +
the regression tests. **No `TaskTraceability` regex is changed** (Option b — relax the gate — was rejected by
the user). This is a **design-only** amendment; the source change (the prompt + tests) is T-3.5, implemented
by the coordinator's task-builder after this amendment lands. **This only unblocks the future G3 live smoke
test — no milestone gate (G0–G4) is touched, marked, or re-judged; G3 stays OPEN.**

## Trigger

T-3.5 (raised by `spec-driven-task-builder`; the diagnosis was independently verified by the prior
coordinator — compiled + ran the production regexes against the live-failing forms). `TaskTraceability.java`
(lines 44-45 `TASK_LINE`, lines 51-52 `REQUIREMENT_REF`) is strict; `GreenfieldPlaybook.java` never emits the
required id/symbol shapes — the TASKS-phase block (line 152-159) says "each with a stable identifier and each
tracing to at least one requirement" without naming the `T-<n>` id form or the `AC-`/`US-`/`NFR-` symbol
forms, and the REQUIREMENTS-phase block (line 138-144) says "personas, user stories, acceptance criteria,
non-functional requirements" without pinning the gate-recognizable `AC-<n>.<m>` / `US-<n>` / `NFR-<NAME>`
symbol shapes. The existing `TaskTraceabilityTest` (10 tests) exercises only gate-conformant forms, so the
unit suite was green while live model output failed — a defect class those tests cannot catch by
construction. Confirmed NEW (not previously in `open-questions.md`).

## Decision recorded (Option (a) — fix the prompt, keep the gate strict)

1. **`00-requirements.md`** —
   - **AC-2.2** (type U, unchanged EARS form): added a clause that each task's stable identifier is of the
     form `T-<n>` / `T-<n>.<m>` (hyphen mandatory), and that for a greenfield project the playbook prompt
     **emits** this id form so the project's own breakdown conforms to the strict gate (`TaskTraceability`
     unchanged). Refs widened US-2 → US-2, ADR-0012.
   - **AC-2.5** (type U, unchanged EARS form): added a clause that for a greenfield project the
     **traceability vocabulary is the model-authored requirement symbols** `AC-<n>.<m>` / `US-<n>` /
     `NFR-<NAME>` authored in the requirements phase, that each task line cites ≥ 1 such symbol, and that the
     playbook prompt **emits** the vocabulary on both phases so the project self-conforms (gate stays strict;
     `TaskTraceability` regexes unchanged). Refs already US-2, ADR-0012.
2. **`adr/0012-greenfield-workflow-formality.md`** —
   - New Decision bullet **"The playbook prompt emits the gate's vocabulary; the gate stays strict
     (AC-2.2, AC-2.5 — amended DCR-5)"**: states the strict gate vocabulary (`TASK_LINE` `T-<n>`/`T-<n>.<m>`;
     `REQUIREMENT_REF` `US-/AC-/NFR-/RD-/INV-`), that this strictness is the formal traceability guarantee
     this ADR chose over the lightweight scaffold (and the reflexive-consistency value), and that the burden
     of conformance sits on the prompt — the requirements phase authors gate-recognizable `AC-<n>.<m>` /
     `US-<n>` / `NFR-<NAME>`; the tasks phase emits `T-<n>` ids citing ≥ 1 such symbol; **no regex relaxed**.
   - New Alternatives row **"Relax the traceability gate (DCR-5 Option b)"** recording the rejected
     gate-relaxation (broaden `TASK_LINE`/`REQUIREMENT_REF` to accept `T1`/`R5`) and why (discards the strict
     guarantee; breaks reflexive consistency).
   - New Notes entry **"Amended 2026-06-24 (DCR-5, T-3.5)"** narrating the defect, the fix, the rejected
     option, and the G3-only unblock.
   - Front-matter: `amended` += DCR-5 entry; Status line += DCR-5 sentence; `review` repointed to this file.
3. **`07-tasks.md`** —
   - New **M3 task T-3.5** (size S, deps T-3.2, component C3): align the `GreenfieldPlaybook` per-phase prompt
     to emit the gate vocabulary (requirements phase → `AC-<n>.<m>`/`US-<n>`/`NFR-<NAME>`; tasks phase →
     `T-<n>` ids citing ≥ 1 such symbol); add regression tests pinning the live-failing `T1`/`R5` forms (gate
     refuses) and gate-vocabulary forms (gate passes); **no `TaskTraceability` change**. Refs:
     AC-2.5, AC-2.2, ADR-0012, US-1/2/3, GreenfieldPlaybook (C3 per-phase prompt).
   - Task → US mapping: greenfield row gains T-3.5 (US-2 traceability-vocabulary alignment, AC-2.5/AC-2.2).
   - Front-matter `amended_by` += DCR-5; `last_reviewed`/`review` updated.

Plus `design/design-progress.md` (same-commit amendment lifecycle per shared.md § 9.2 / § 9.5): front-matter
briefly flipped to `amendment-DCR-5` then returned to `handed-off-to-coordinator`; § 1 DCR-5 prose; § 3
carry-forward (citing AC-2.2/AC-2.5, ADR-0012, GreenfieldPlaybook C3, T-3.5); § 5 Landed line for DCR-5.

Rejected alternative recorded: **Option (b) — relax the gate** (broaden `TaskTraceability`'s `TASK_LINE` to
accept hyphen-less `T1`/`T10` and `REQUIREMENT_REF` to accept bare `R<n>`). Rejected by the user: it discards
the strict formal traceability guarantee ADR-0012 chose over the lightweight scaffold and breaks the
reflexive-consistency value that greenfield mirrors the methodology that built codingAgent itself.

## Scope reviewed (files edited)

| File | Change |
|------|--------|
| `design/00-requirements.md` | AC-2.2 clause (T-<n> id form + prompt emits it; Refs += ADR-0012); AC-2.5 clause (greenfield traceability vocabulary = model-authored AC-<n>.<m>/US-<n>/NFR-<NAME>; prompt emits it; gate strict). EARS Type column (U/U) preserved; no Phase-1a user story edited. Front-matter `amended_by` (+DCR-5)/`review`. |
| `design/adr/0012-greenfield-workflow-formality.md` | New "playbook prompt emits the gate's vocabulary; gate stays strict" Decision bullet; new "Relax the traceability gate (DCR-5 Option b)" rejected Alternatives row; new DCR-5 Notes entry; `amended`/Status/`review` front-matter. No prior DCR-1/2/3 decision changed. |
| `design/07-tasks.md` | New M3 task T-3.5 (S, deps T-3.2); task → US mapping greenfield row += T-3.5; front-matter `amended_by` (+DCR-5)/`last_reviewed`/`review`. G-gate table untouched. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| 1 | — | EARS preserved on the amended ACs: AC-2.2 stays ubiquitous (`The agent shall express the task breakdown … each with a stable identifier of the form T-<n> …`); AC-2.5 stays ubiquitous (`The agent shall ensure every task … traces to at least one stated requirement …`). The added clauses qualify the existing requirement; no new AC minted, no EARS Type changed. | Satisfied. |
| 2 | — | The amended ACs cite the gate's vocabulary verbatim from the production regexes (`TASK_LINE` = `T-<n>`/`T-<n>.<m>` hyphen-mandatory, lines 44-45; `REQUIREMENT_REF` = `US-/AC-/NFR-/RD-/INV-`, lines 51-52). The spec text matches the strict code contract that stays unchanged. | Verified against source (read-only). |
| 3 | — | Traceability preserved: AC-2.2 → US-2, ADR-0012; AC-2.5 → US-2, ADR-0012. No AC renumbered. No NFR newly orphaned. ADR-0012 `spec_refs` already carried AC-2.2/AC-2.5 (no add needed). | Verified. |
| 4 | — | Gate stays strict — recorded explicitly in ADR-0012 (Decision bullet + rejected Alternatives row + Notes) that **no `TaskTraceability` regex is relaxed**; the relax-the-gate path is Option b, rejected. | Verified — DCR-5 constraint honoured. |
| 5 | — | No prior DCR-1/2/3 decision altered: the multi-turn / approve-to-finalize / driver-authored-persistence / mid-flow-resume bullets and Notes are untouched; DCR-5 is purely additive (one Decision bullet, one Alternatives row, one Notes entry, front-matter append). | Verified. |
| 6 | — | No schema / contract-test / data-model blast radius (Option a's stated property): T-3.5's regression tests are task-level unit tests over `TaskTraceability` + the prompt, not a new formal CT. `06-formal/contract-tests.md` is therefore not edited and is not in scope. | Scope-clean. |

## Consistency checks (shared.md § 7 amendment procedure step 6)

- No JSON Schema under `06-formal/` was modified → `schemas_validated_against_fixtures: not_applicable`.
- `06-formal/contract-tests.md` was not modified → `contract_tests_index_still_consistent: not_applicable`
  (no CT id created, renamed, or removed; T-3.5 carries task-level regression tests, not a formal CT).

## Constraints honoured (DCR-5 constraints block)

- Edited only files in `scope_of_design_edit` (`00-requirements.md`, `adr/0012`, `07-tasks.md`) + this review
  file + `design-progress.md` (the same-commit amendment-lifecycle file). No source/test edit; no edit
  outside scope. ✅
- Did NOT change `TaskTraceability.java` or any source/test file — design-only amendment; the source change
  is T-3.5, implemented after this amendment lands. ✅
- Did NOT edit `00-requirements.md` Phase-1a user stories (US-1..US-21 text). Amended AC-2.2/AC-2.5 bodies
  only, under existing US-2; EARS form preserved. ✅
- Preserved all traceability: AC-2.2/AC-2.5 keep Refs US-2 (AC-2.2 widened to US-2, ADR-0012); every AC still
  references a US; the EARS Type column form (U) preserved. ✅
- The gate stays strict — recorded explicitly in ADR-0012 that NO `TaskTraceability` regex relaxation is made
  (Option b rejected). ✅
- Did NOT touch, mark, or re-judge any milestone gate (G0–G4); G3 stays OPEN. ✅
- Did NOT edit `design/07-tasks-progress.md` or `design/open-questions.md` (coordinator-owned; the coordinator
  logged the DCR-5 lifecycle there — `open-questions.md` is modified in the working tree by the coordinator,
  not by this amendment). ✅
- Single amendment commit per shared.md § 7.2; `design-progress.md` updated in the same commit per § 9.5,
  with the amendment-mode front-matter flip-and-return per § 9.2. ✅

## Flagged for later phases (ripple_unresolved)

- **`02-architecture.md` C3 row** records the DCR-1/DCR-2/DCR-3 greenfield contracts (multi-turn dialogue,
  driver-authored persistence, mid-flow resume) but not the DCR-5 prompt-emits-the-gate-vocabulary contract.
  It is **out of scope** for this DCR (`scope_of_design_edit` lists only `00-requirements.md`, `adr/0012`,
  `07-tasks.md`) and the addition is semantic, not a mechanical cross-reference update. Surfaced for the user
  to decide whether C3's row should note that the greenfield playbook prompt emits the gate's traceability
  vocabulary — a small narrative addition, not required for T-3.5 to land (T-3.5 cites ADR-0012 + AC-2.2/AC-2.5
  directly).

## Implementation note for T-3.5 (the source change after this amendment)

The prompt change is localized to `GreenfieldPlaybook.phaseBlock(...)` (the REQUIREMENTS and TASKS cases). The
regression tests should pin both directions against the **unchanged** `TaskTraceability`: (a) the prior
live-failing forms (`T1`/`T2`/`T10` ids citing `R1`–`R6`) are refused as `0-tasks`/untraceable; (b)
gate-vocabulary forms (`T-1`/`T-1.2` ids citing `AC-1.1`/`US-2`/`NFR-FOO`) pass. A prompt-content assertion
(the REQUIREMENTS block names `AC-<n>.<m>`/`US-<n>`/`NFR-<NAME>`; the TASKS block names the `T-<n>` id form +
the cite-≥1-symbol requirement) guards against silent prompt drift.
