---
review_id: amendment-greenfield-containment-and-gate-miscounting-r1
phase: amendment
triggered_by: T-3.6
request_id: DCR-6
raised_by: spec-driven-implementer
kind: ac-update
approved_by_user_at: 2026-06-24T00:00:00+00:00
artifact: design/00-requirements.md, design/adr/0012-greenfield-workflow-formality.md, design/07-tasks.md, design/06-formal/contract-tests.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-6 greenfield write_artifact containment + TaskTraceability real-breakdown miscounting (ac-update + adr-clarification) (r1)

## Outcome

Approved (Option 1, user-approved 2026-06-24T00:00:00+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. A single mini-DCR, **design/-only**, that (a) qualifies the
AC-2.2/AC-2.5 gate wording so a miscounting-only hardening is permitted while the strict same-line-ref
guarantee stands, (b) records the qualification + the containment tightening in ADR-0012, (c) adds the
two new M3 task rows (T-3.6 containment, T-3.7 gate-hardening + prompt) that the coordinator's
task-builder will implement as source changes, and (d) adds a contract test for the new shapes
(CT-GF-3 gate miscounting, CT-GF-4 containment). **NO source code is edited by this amendment.** The
`TaskTraceability` RECOGNITION is **unchanged in STRICTNESS** — a miscounting-only hardening changes
recognition **COVERAGE, not strictness**; the same-line-ref strictness is **NOT** loosened into a block
scan (the rejected DCR-5 Option b stands rejected). **This only unblocks the future G3 live smoke test —
no milestone gate (G0–G4) is touched, marked, or re-judged; G3 stays OPEN.**

## Trigger

T-3.6 (raised by `spec-driven-implementer`; both root causes independently re-verified by the
coordinator against the live source). Two distinct G3-blocking greenfield defects:

1. **Containment hole (closed by new task T-3.6, source change).** `GreenfieldArtifactStore.resolveArtifact`
   (lines 93-102) confines a write only by `resolved.startsWith(<workspaceRoot>/design)` — no allowlist —
   so `design/impl/pom.xml` and `design/impl/src/main/java/.../Calculator.java` resolve under
   `<repo>/design` and PASS the check, reaching `store.write()` via `WriteArtifactTool.handle` (line 105).
   Both class Javadocs already promise the tool "cannot write source files" (WriteArtifactTool lines
   19-25/68-70; GreenfieldArtifactStore lines 24-27) and AC-1.4 forbids any Class-X op against source
   files in the pre-approval dialogue — a code-vs-Javadoc/AC-1.4 conformance hole (the pre-approval
   source-write hole).
2. **Gate real-breakdown miscounting (closed by new task T-3.7, source change).** `TaskTraceability` is
   correctly STRICT on which lines count as tasks (`T-<n>`, hyphen mandatory) and which refs count as
   traces (`US-`/`AC-`/`NFR-`/`RD-`/`INV-`), but it MISCOUNTS the shapes a real Sonnet-style breakdown
   contains: (i) a repeated id is double-counted in `untracedTasks` (line 98, no dedup); (ii) an
   arrow/sequencing-diagram line `T-1 -> T-2` is read as a single task (`TASK_LINE` captures only the
   first id); (iii) a range heading `T-3 through T-8` recognizes only `T-3`, silently dropping
   `T-4..T-8`; (iv) a bold-wrapped id in a table row `| **T-1** |` is not recognized (the `**` defeats
   the list/heading/table prefix). These are recognition-COVERAGE misses, not strictness relaxations.

Bug 3 (a live-generated `CalculatorTest.java` referencing `CalcException` unqualified) is **DEFERRED** —
not part of DCR-6; the genuine IMPLEMENT-phase verify loop is expected to catch it.

## Decision recorded (Option 1 — qualify the wording, harden coverage, keep strictness)

1. **`00-requirements.md`** —
   - **AC-2.2** (type U, EARS form preserved): the trailing parenthetical "the gate's `TaskTraceability`
     is unchanged" is replaced with a qualification — recognition is **unchanged in STRICTNESS** (which
     lines count as tasks / which refs count as traces is not relaxed); a miscounting-only hardening
     (dedup repeated ids, skip arrow/sequencing-diagram lines, expand range headings so each task is
     individually recognized, recognize bold-wrapped ids in table rows) is permitted because it changes
     recognition COVERAGE not strictness; the same-line-ref strictness is **NOT** loosened into a block
     scan (DCR-5 Option b stands rejected); single-line task rows are guaranteed by the prompt. Citation
     `(ADR-0012, DCR-6)` added alongside the existing DCR-2/DCR-5 citations. Refs cell `US-2, ADR-0012`
     unchanged.
   - **AC-2.5** (type U, EARS form preserved): the trailing "the gate's `TaskTraceability` regexes are
     unchanged" is replaced with the same unchanged-in-STRICTNESS qualification (with "expanding range
     headings so each task is individually recognized **and correctly flagged if untraced, not silently
     collapsed**"), cite `(ADR-0012, DCR-6)`. Refs cell `US-2, ADR-0012` unchanged.
   - Front-matter `amended_by` += DCR-6; `review` repointed to this file.
2. **`adr/0012-greenfield-workflow-formality.md`** —
   - The Decision section's "The gate is unchanged" sub-bullet is qualified to **"The gate is unchanged
     in STRICTNESS (qualified by DCR-6)"**, and a new sub-bullet **"A miscounting-only recognition-COVERAGE
     hardening is permitted (DCR-6, T-3.7)"** enumerates the four miscounted shapes and the four
     coverage fixes, states the change is COVERAGE not strictness, and reaffirms the strict same-line-ref
     guarantee + the rejection of the loose block scan (DCR-5 Option b) both stand. The prompt carries
     the single-line-row guarantee.
   - The "AC-1.4 preserved" Decision sub-bullet is extended (**"write_artifact containment tightened to
     the known design-doc artifacts (DCR-6, T-3.6)"**) to record the residual containment hole and the
     allowlist tightening (only `design/00-requirements.md`/`01-design.md`/`02-tasks.md`; reject any
     other path under `design/`, including `design/impl/**`). No contract change — tightens an existing
     confinement to match the Javadoc/AC-1.4 promise.
   - New Notes entry **"Amended 2026-06-24 (DCR-6, T-3.6/T-3.7)"** narrating both bugs, the qualification,
     the deferred Bug 3, and the G3-only unblock; cross-references DCR-5.
   - Front-matter `amended` += DCR-6 entry; Status line += DCR-6 sentence; `review` repointed to this file.
   - The **"Relax the traceability gate (DCR-5 Option b)"** Alternatives row is **left intact as rejected**
     (the DCR-6 hardening is coverage, not the Option-b strictness relax).
3. **`07-tasks.md`** —
   - New **M3 task T-3.6** (size S, deps T-3.2, component C9 + C3): tighten `GreenfieldArtifactStore`/
     `WriteArtifactTool` to ONLY the three known design-doc artifacts; reject source paths under `design/`.
     Refs: AC-1.4, RD-7, ADR-0012, GreenfieldArtifactStore/WriteArtifactTool. Verify: CT-GF-4.
   - New **M3 task T-3.7** (size S, deps T-3.5, component C3): harden `TaskTraceability` recognition
     coverage (dedup / arrow-skip / range-expand / bold-table-cell) — NO strictness relaxation, NO loose
     block scan — + extend the greenfield TASKS prompt (single-line rows; forbid ranges/multi-line
     `**Refs:**`/arrow-diagram-as-task-list) + regression tests for all four shapes + a full Sonnet-style
     breakdown + a `GreenfieldPlaybook` prompt assertion. Refs: AC-2.2, AC-2.5, ADR-0012 (DCR-6),
     GreenfieldPlaybook, TaskTraceability. Verify: CT-GF-3.
   - § 6 Task → US mapping: the US-1/2/3 greenfield row gains T-3.6 + T-3.7 (consistent with how T-3.5 was
     added).
   - Front-matter `amended_by` += DCR-6; `last_reviewed`/`review` updated. G-gate table untouched.
4. **`06-formal/contract-tests.md`** —
   - § 7 heading retitled "(DCR-3, DCR-6)"; intro extended to cover the new CTs and their phase-5 task
     homes (CT-GF-3 in T-3.7, CT-GF-4 in T-3.6).
   - New **CT-GF-3** (positive, traces AC-2.2/AC-2.5/ADR-0012): the four gate miscounting shapes
     (multi-line `**Refs:**` block / range heading `T-3 through T-8` / arrow line `T-1 -> T-2` / bold table
     cell `| **T-1** |`) each correctly counted/flagged, plus a full Sonnet-style breakdown that now passes;
     strict same-line-ref rule + block-scan rejection both held.
   - New **CT-GF-4** (negative, traces AC-1.4/RD-7/ADR-0012): the containment allowlist — `design/impl/pom.xml`
     REJECTED, a source file under `design/impl/src/**` REJECTED, a bare source path REJECTED (already), the
     three real artifacts ALLOWED.
   - § 6 traceability summary extended to cite CT-GF-3/CT-GF-4 (DCR-6). Front-matter `amended_by` += DCR-6;
     `review`/`last_reviewed` updated. CT-GF-1/CT-GF-2 rows intact.

Plus `design/design-progress.md` (same-commit amendment lifecycle per shared.md § 9.2 / § 9.5):
front-matter briefly flipped to `amendment-DCR-6` then returned to `handed-off-to-coordinator`; § 1
DCR-6 prose; § 3 carry-forward (citing AC-2.2/AC-2.5, AC-1.4, RD-7, ADR-0012, the store/tool + gate); § 5
Landed line for DCR-6.

## Scope reviewed (files edited)

| File | Change |
|------|--------|
| `design/00-requirements.md` | AC-2.2 + AC-2.5 trailing-clause qualification (unchanged-in-STRICTNESS; coverage-not-strictness hardening permitted; block-scan stays rejected; cite DCR-6). EARS Type (U/U) preserved; Refs cells (US-2, ADR-0012) preserved; no Phase-1a user story edited. Front-matter `amended_by` (+DCR-6)/`review`. |
| `design/adr/0012-greenfield-workflow-formality.md` | "gate unchanged" → "unchanged in STRICTNESS" sub-bullet + new coverage-hardening sub-bullet; AC-1.4-preserved sub-bullet extended with the containment allowlist; new DCR-6 Notes entry; `amended`/Status/`review` front-matter. The rejected "Relax the traceability gate (DCR-5 Option b)" Alternatives row left intact. No prior DCR-1/2/3/5 decision altered. |
| `design/07-tasks.md` | New M3 tasks T-3.6 (S, deps T-3.2, C9/C3) + T-3.7 (S, deps T-3.5, C3); § 6 US-1/2/3 greenfield row += T-3.6, T-3.7; front-matter `amended_by` (+DCR-6)/`last_reviewed`/`review`. G-gate table untouched. |
| `design/06-formal/contract-tests.md` | New CT-GF-3 (gate miscounting, +, AC-2.2/AC-2.5/ADR-0012) + CT-GF-4 (containment, −, AC-1.4/RD-7/ADR-0012) in § 7; § 7 heading + intro + closing note updated; § 6 traceability summary extended; front-matter `amended_by` (+DCR-6)/`review`. CT-GF-1/CT-GF-2 intact. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| 1 | — | EARS preserved on the amended ACs: AC-2.2 stays ubiquitous (`The agent shall express the task breakdown … each with a stable identifier of the form T-<n> …`); AC-2.5 stays ubiquitous (`The agent shall ensure every task … traces to at least one stated requirement …`). The added clauses qualify the existing requirement; no new AC minted, no EARS Type changed. | Satisfied. |
| 2 | — | The qualification carefully says "unchanged in STRICTNESS" (not "unchanged"), and explicitly states the hardening changes recognition COVERAGE not strictness, and that the same-line-ref rule is NOT loosened into a block scan — so "unchanged" never invites the rejected DCR-5 Option b. | Verified against the DCR constraint ("must not loosen same-line-ref strictness into a block scan; DCR-5 Option b stays REJECTED"). |
| 3 | — | Traceability preserved: AC-2.2 → US-2, ADR-0012; AC-2.5 → US-2, ADR-0012 (Refs cells unchanged). No AC renumbered. Every AC still references a US. No NFR newly orphaned. ADR-0012 `spec_refs` already carried AC-1.4/AC-2.2/AC-2.5 (no add needed). T-3.6/T-3.7 cite AC-1.4/RD-7 and AC-2.2/AC-2.5 respectively; both map to US-2 in § 6. | Verified. |
| 4 | — | The TaskTraceability recognition stays unchanged in strictness — recorded explicitly in ADR-0012 (qualified Decision sub-bullet + coverage-hardening sub-bullet) and in AC-2.2/AC-2.5 that the loose block-scan (DCR-5 Option b) stays rejected. The Alternatives row recording Option b as rejected is left intact. | Verified — DCR-6 constraints honoured. |
| 5 | — | No prior DCR-1/2/3/5 decision altered: the multi-turn / approve-to-finalize / driver-authored-persistence / mid-flow-resume / prompt-emits-vocabulary bullets and Notes are untouched; DCR-6 is additive (one qualified sub-bullet, one new sub-bullet, one extended sub-bullet, one Notes entry, two task rows, two CTs, front-matter appends). | Verified. |
| 6 | — | Contract-tests index stays consistent: CT-GF-3/CT-GF-4 are new ids (next free after CT-GF-1/CT-GF-2), each cites a valid AC + ADR-0012; no CT renamed or removed; the JSON schemas under `06-formal/` are untouched (no fixture revalidation needed). | Verified (see Consistency checks). |
| 7 | — | Design-only amendment: no file under `src/` edited. The two source fixes (T-3.6 containment, T-3.7 gate + prompt) are implemented by the coordinator's task-builder AFTER this amendment lands. | Scope-clean. |

## Consistency checks (shared.md § 7 amendment procedure step 6)

- No JSON Schema under `06-formal/` was modified → `schemas_validated_against_fixtures: not_applicable`.
- `06-formal/contract-tests.md` WAS modified (added CT-GF-3 + CT-GF-4): every CT id is unique and references
  a valid AC (CT-GF-3 → AC-2.2/AC-2.5; CT-GF-4 → AC-1.4/RD-7) + ADR-0012; CT-GF-3/4 are ⚙/structural
  (exercised by the gate parser + the store path resolver, no schema dependency); CT-GF-1/CT-GF-2 unchanged.
  → `contract_tests_index_still_consistent: passed`.

## Constraints honoured (DCR-6 constraints block)

- Edited only files in `scope_of_design_edit` (`00-requirements.md`, `adr/0012`, `07-tasks.md`,
  `06-formal/contract-tests.md`) + this review file + `design-progress.md` (the same-commit
  amendment-lifecycle file). No source/test edit; no edit outside scope. ✅
- Did NOT edit `00-requirements.md` Phase-1a user stories (US-1..US-21 text). Amended AC-2.2/AC-2.5 bodies
  only, under existing US-2; EARS form preserved. ✅
- Preserved all traceability: AC-2.2/AC-2.5 keep Refs US-2, ADR-0012; every AC still references a US; every
  NFR still referenced by ≥ 1 AC; the EARS Type column form (U/U) preserved on the amended ACs. ✅
- The AC qualification preserves EARS form on AC-2.2 (U) and AC-2.5 (U). ✅
- NO `TaskTraceability` strictness relaxation; the same-line-ref guarantee stands; a loose block scan
  (DCR-5 Option b) stays REJECTED (recorded in AC-2.2/AC-2.5 + ADR-0012 Decision + the intact Alternatives
  row). ✅
- Did NOT touch, mark, or re-judge any milestone gate (G0–G4); G3 stays OPEN. ✅
- Did NOT edit `design/07-tasks-progress.md` or `design/open-questions.md` (coordinator-owned). ✅
- Single amendment commit per shared.md § 7.2; `design-progress.md` updated in the same commit per § 9.5,
  with the amendment-mode front-matter flip-and-return per § 9.2. ✅

## Flagged for later phases (ripple_unresolved)

Ripple-detection grepped the full `design/` tree for every symbol in `spec_refs_touched`
(AC-2.2, AC-2.5, AC-1.4, RD-7, ADR-0012) plus the affected class names (`TaskTraceability`,
`GreenfieldArtifactStore`, `WriteArtifactTool`, `write_artifact`). Hits outside the four in-scope files:

- **`design/02-architecture.md` C3 (Workflow drivers) / C7 (Tool Registry) / C9 (File tools) rows** —
  record the DCR-1/2/3 greenfield contracts (and write_artifact-optional for C7) but **do not yet note**
  the DCR-6 contracts: C3/C9 the `write_artifact` containment allowlist (only the three known design-doc
  artifacts; source paths under `design/` rejected) and the gate recognition-COVERAGE hardening; C7 the
  same containment note. This is the **same precedent DCR-5 set** (the C3 row not yet noting the
  prompt-emits-the-vocabulary contract was surfaced as a non-blocking out-of-scope ripple). It is
  **semantic** (a narrative addition requiring judgment, not a mechanical cross-reference substitution)
  and **out of the approved `scope_of_design_edit`** (only `00-requirements`/`adr-0012`/`07-tasks`/
  `contract-tests`). The architecture's traceability semantics live in ADR-0012, which **IS** updated;
  T-3.6/T-3.7 cite ADR-0012 + AC-1.4/AC-2.2/AC-2.5 directly, so the missing C3/C7/C9 notes do **not**
  block either task. Surfaced to the user as a candidate for a future tiny doc-fold-in — user's call.
  Not a new ambiguity, not scope creep.

- **`design/03-data-model.md` line 105** references ADR-0012 only in the context of **multimodal input
  scope** (greenfield diagrams/docs, US-1). Semantically unrelated to the DCR-6 containment/miscounting
  qualification. **No ripple** — recorded here only to show the grep hit was inspected and dismissed.

No required edit could not be expressed within the approved four-file scope without breaking traceability,
so no `needs-conversation` is warranted.

## Implementation note for T-3.6 / T-3.7 (the source changes after this amendment)

- **T-3.6 (containment, driven first).** The fix is localized to `GreenfieldArtifactStore.resolveArtifact`
  (and the path it receives from `WriteArtifactTool.handle`): replace the bare
  `resolved.startsWith(<workspaceRoot>/design)` confinement with an allowlist of exactly the three known
  design-doc artifact paths, rejecting any other path under `design/`. CT-GF-4 pins the three ALLOWED + the
  three REJECTED cases.
- **T-3.7 (gate + prompt, driven second).** The gate fix is in `TaskTraceability`: dedup the recognized-id
  set before counting untraced tasks; skip arrow/sequencing-diagram lines; expand a range heading into the
  individual ids it spans (each still held to the same strict same-line-ref rule); recognize a bold-wrapped
  id in a table cell. The prompt change is in `GreenfieldPlaybook` (the TASKS-phase block): force one
  canonical single-line task row per task and forbid range headings / multi-line `**Refs:**` blocks /
  arrow-diagram-as-task-list. CT-GF-3 pins the four shapes + a full Sonnet-style breakdown that now passes,
  plus a prompt-content assertion guarding against silent prompt drift. NO `TaskTraceability` strictness
  relaxation; NO loose block scan.
