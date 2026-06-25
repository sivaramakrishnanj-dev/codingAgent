---
review_id: amendment-greenfield-implement-verify-model-r1
phase: amendment
triggered_by: T-3.8
request_id: DCR-7
raised_by: spec-driven-task-builder
kind: ac-update + adr-clarification
approved_by_user_at: 2026-06-25T00:00:00+00:00
artifact: design/00-requirements.md, design/adr/0012-greenfield-workflow-formality.md, design/06-formal/contract-tests.md, design/07-tasks.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: 4667724
---

# Amendment review — DCR-7 greenfield IMPLEMENT-phase verify-at-end / testable-only verification model (ac-update + adr-clarification) (r1)

## Outcome

Approved (Option A, user-approved 2026-06-25T00:00:00+00:00 via the coordinator preview, end-to-end).
Executed non-conversationally in amendment mode. A **design/-only** amendment that adopts a
**verify-at-end / testable-only** verification model for the greenfield IMPLEMENT phase to fix three
distinct G3-blocking defects (D1/D2/D3): each task is marked complete **on implementation** (durable
on-disk marker, read back on resume), verification runs **once at the end of the phase** (testable-only —
tasks not independently testable are implemented without per-task verification), a failing end verify
retries bounded by `NFR-VERIFY-MAX-ITERATIONS` then stop-and-surface, **no configured test command →
complete-with-warning** (terminal success, exit 0, no re-prompt loop), and **intra-IMPLEMENT resume skips
completed tasks** (resume at the first incomplete task, not `T-1`). A new **AC-3.6** carries the
no-test-command behavior that three code sites previously **mis-cited as AC-20.6**; **AC-20.6 text is
left unchanged**. **NO source code is edited by this amendment.** The three source fixes (T-3.8 loop
rework, T-3.9 end-of-phase verify + no-test terminal, T-3.10 intra-IMPLEMENT resume) are implemented by
the coordinator's task-builder, in that order, AFTER this amendment lands. **This only unblocks the
future G3 live smoke test — no milestone gate (G0–G4) is touched, marked, or re-judged; G3 stays OPEN.**

## Trigger

T-3.8 (raised by `spec-driven-task-builder`; all three root causes independently re-verified by the
prior coordinator against the live source, anchored to file:line). Three distinct G3-blocking greenfield
IMPLEMENT-phase defects:

1. **(D1) No-test-command livelock — resolved by T-3.9.** `GreenfieldImplementLoop.run()` takes a
   `NO_TEST_COMMAND` early-return (:184-188) that `asLoopTurn` wraps as "completed" (:209-211);
   `GreenfieldDriver.runImplementPhase` (:312-323) + the `ReplRunner` keep-alive (:189-196 / :228-232 /
   :152-155) then re-prompt into a fresh implement attempt — a livelock, not a terminal outcome. The
   three sites **mis-cite AC-20.6** (`VerifyLoop.java:129`, `GreenfieldImplementLoop.java:186` & `:281`).
   AC-20.6 (@ `00-requirements.md:398`) is "prefer configured named commands over ad-hoc command
   strings", **not** the no-test-command behavior.
2. **(D2) No implement progress on resume — resolved by T-3.10.** `GreenfieldDriver.java:211`
   reconstructs phase-state every turn; `GreenfieldPhaseState.reconstruct()` (:70-90) lands at IMPLEMENT
   on the three "Approved:" stamps; `TaskTraceability.tasksInOrder()` (:195-202) does **not** skip
   completed tasks; `markComplete` fires only on VERIFIED (:173-177 / :255-260 / :373-386) and is
   write-only (nothing reads completion lines back) — so a greenfield re-entry restarts at `T-1` rather
   than resuming at the first incomplete task.
3. **(D3) Per-task verify vs scaffold-first — resolved by T-3.8.** The loop body verifies per-task
   (:170-190) with an EXHAUSTED hard-stop (:178-183) bounded by `NFR-VERIFY-MAX-ITERATIONS=5`
   (`VerifyLoop.verify()` :135 / :160-162); a scaffold-first breakdown (`T-1` scaffold, `T-2` pom)
   hard-stops at `T-1` because the not-yet-buildable scaffold cannot pass a per-task verify.

Bug 3 from DCR-6 (a live-generated `CalculatorTest.java` referencing `CalcException` unqualified) is now
expected to be caught by the **end-of-phase verify** rather than separately fixed.

## Decision recorded (Option A — verify-at-end / testable-only; no-test = complete-with-warning)

1. **`00-requirements.md`** —
   - **AC-3.2** (type Ev, EARS form preserved): rewritten from "When a task's changes are complete, the
     agent shall verify them via the configured build/test commands" → verification runs **once at the
     end of the phase** (not per task; flat task list, end-of-phase boundary), **testable-only** (tasks
     not independently testable implemented without per-task verification). Refs `AC-20.1, ADR-0012`.
   - **AC-3.3** (type Ev, EARS form preserved): rewritten from "When a task passes verification, mark it
     complete …" → each task is **marked complete AS IT IS IMPLEMENTED** (durable on-disk marker), the
     marker is **read back on resume** to skip completed tasks (AC-7.6), and end-of-phase verification
     gates the **phase**, not each task. Refs `US-3, ADR-0012`.
   - **AC-3.4** (type Un): tightened from "If a task fails verification …" → "If **end-of-phase**
     verification fails after `NFR-VERIFY-MAX-ITERATIONS` …" (the bound moves from per-task to the single
     end-of-phase verify). Refs unchanged (`NFR-VERIFY-MAX-ITERATIONS`).
   - **NEW AC-3.6** (type Un, next free id under US-3): no configured test command → **skip the
     end-of-phase verification with a single warning**, having implemented + marked complete every task,
     and **terminate the phase deterministically (no re-prompt loop)** — complete-with-warning terminal
     success (exit 0), consistent with the brownfield no-verify precedent; not a hard-stop, not a re-loop.
     This is the correct AC the three code sites mis-cite as AC-20.6. Refs `US-3, NFR-VERIFY-MAX-ITERATIONS,
     ADR-0012`.
   - **AC-7.6** (type Ev): extended with an **intra-IMPLEMENT resume** clause — when the resumed phase is
     IMPLEMENT, read back the per-task completion markers (AC-3.3) and resume at the **first incomplete
     task**, terminating rather than restarting at `T-1`; the existing pre-approval phase-boundary
     tradeoff wording is preserved (the in-phase transcript is not preserved across an interruption).
     Refs cell `US-1, US-2, US-7, ADR-0012` → `US-1, US-2, US-3, US-7, ADR-0012` (adds US-3, the IMPLEMENT
     facet's owning story).
   - **AC-20.6 — TEXT UNCHANGED.** The constraint is honoured verbatim: the DCR only records that the
     code re-cites the no-test-command behavior to the new AC-3.6, not AC-20.6. No edit to AC-20.6's row.
   - 1b symbolic-NFR table + 1c NFR→AC coverage row for `NFR-VERIFY-MAX-ITERATIONS` += AC-3.6 (the NFR's
     no-test-command skip path is now referenced by AC-3.6 too) — preserving the "every NFR referenced by
     ≥ 1 AC" + recording-the-new-AC's-trace discipline.
   - Front-matter `amended_by` += DCR-7; `review` repointed to this file.
2. **`adr/0012-greenfield-workflow-formality.md`** —
   - The Decision section's **"Implementation (US-3, AC-3.x)"** bullet is rewritten into
     **"Implementation — verify at end / testable-only (US-3, AC-3.x — amended DCR-7)"**: mark each task
     complete on implementation (AC-3.3); verify once at end of phase (AC-3.2), testable-only; failing end
     verify bounded by `NFR-VERIFY-MAX-ITERATIONS` then stop-and-surface (AC-3.4/AC-20.5); no test command
     → complete-with-warning terminal (AC-3.6, **replacing the AC-20.6 mis-citation**); intra-IMPLEMENT
     resume skips completed tasks (AC-7.6). States the verify boundary is end-of-phase (flat task list, no
     milestone substructure) and that this is what a scaffold-first breakdown needs.
   - New Notes entry **"Amended 2026-06-25 (DCR-7, T-3.8/T-3.9/T-3.10)"** narrating the three defects
     (D1/D2/D3), the verify-at-end / testable-only model, the AC-20.6→AC-3.6 mis-citation fix, the
     rejected Option B, the deferred DCR-6 Bug 3 now caught by the end-verify, the out-of-scope brownfield
     no-test sites, and the G3-only unblock.
   - Front-matter `amended` += DCR-7 entry; Status line += DCR-7 sentence; `spec_refs` += AC-3.4, AC-3.6,
     AC-20.5, NFR-VERIFY-MAX-ITERATIONS; `review` repointed to this file.
   - No prior DCR-1/2/3/5/6 decision altered; the rejected Alternatives rows are left intact.
3. **`06-formal/contract-tests.md`** —
   - § 7 heading retitled "(DCR-3, DCR-6, DCR-7)"; intro + closing note extended to cover the new CTs and
     their Phase-5 task homes (CT-GF-5/CT-GF-7 in T-3.9, CT-GF-6 in T-3.10, CT-GF-8 in T-3.8).
   - New **CT-GF-5** (positive, ⚙, traces AC-3.6/AC-3.3/ADR-0012): no-test-command terminates cleanly —
     all tasks implemented + marked complete, end verify skipped with one warning, terminal (exit 0, NO
     re-loop).
   - New **CT-GF-6** (positive, ⚙, traces AC-7.6/AC-3.3/ADR-0012): intra-IMPLEMENT resume skips completed
     tasks — re-entry over a partially-completed breakdown resumes at the first incomplete task and
     terminates; does not restart at `T-1`.
   - New **CT-GF-7** (positive, ⚙, traces AC-3.2/AC-3.4/AC-20.5/ADR-0012): end-of-phase verify failure
     retries bounded by `NFR-VERIFY-MAX-ITERATIONS` then stop-and-surface.
   - New **CT-GF-8** (positive, ⚙, traces AC-3.2/AC-3.3/ADR-0012): scaffold-first breakdown (`T-1`
     scaffold, `T-2` pom) implements all tasks then verifies once at end — no hard-stop at `T-1`.
   - § 6 traceability summary extended to cite CT-GF-5..CT-GF-8 (DCR-7). Front-matter `amended_by` +=
     DCR-7; `review`/`last_reviewed` updated. CT-GF-1..CT-GF-4 rows intact.
   - The DCR permitted splitting the single requested CT-GF-5 into CT-GF-5..CT-GF-8 — done (one CT per
     facet, cleaner traceability).
4. **`07-tasks.md`** —
   - New **M3 task T-3.8** (size M, deps T-3.3, component C3 + C2): implement-loop rework — implement every
     task, mark each complete on implementation, drop per-task verify + per-task EXHAUSTED hard-stop +
     per-task `NO_TEST_COMMAND` early-return. Resolves D3. Refs AC-3.2/AC-3.3/ADR-0012/US-3. Verify: CT-GF-8.
   - New **M3 task T-3.9** (size M, deps T-3.8, component C3 + C2): end-of-phase / testable-only verify +
     no-test-command TERMINAL behavior; fix the AC-20.6→AC-3.6 mis-citation at the three sites; ensure the
     driver/REPL do not re-prompt the terminal no-test outcome. Resolves D1. Refs
     AC-3.2/AC-3.6/AC-20.5/NFR-VERIFY-MAX-ITERATIONS/ADR-0012/US-3. Verify: CT-GF-5 + CT-GF-7.
   - New **M3 task T-3.10** (size M, deps T-3.9, component C3 + C15): intra-IMPLEMENT resume skips completed
     tasks. Resolves D2. Refs AC-7.6/AC-3.3/ADR-0012/US-1/2/3/7. Verify: CT-GF-6.
   - § 6 Task → US mapping: US-1/2/3 greenfield row += T-3.8/T-3.9/T-3.10; US-7 resume row += T-3.10.
   - Front-matter `amended_by` += DCR-7; `last_reviewed`/`review` updated. G-gate table untouched.

Plus `design/design-progress.md` (same-commit amendment lifecycle per shared.md § 9.2 / § 9.5):
front-matter briefly flipped to `amendment-DCR-7` then returned to `handed-off-to-coordinator`; § 1
DCR-7 prose; § 3 carry-forward (citing AC-3.2/AC-3.3/AC-3.6, AC-7.6, AC-20.5, NFR-VERIFY-MAX-ITERATIONS,
ADR-0012, the four CTs, the three tasks); § 5 Landed line for DCR-7.

## Scope reviewed (files edited)

| File | Change |
|------|--------|
| `design/00-requirements.md` | AC-3.2 rewritten (verify at end, testable-only); AC-3.3 rewritten (mark complete on implementation, read back on resume); AC-3.4 scoped to end-of-phase; NEW AC-3.6 (no-test-command complete-with-warning terminal); AC-7.6 extended (intra-IMPLEMENT resume skips completed tasks). **AC-20.6 text untouched.** EARS Type preserved on every amended/new AC (Ev/Ev/Un/Un/Ev). 1b + 1c NFR-VERIFY-MAX-ITERATIONS rows += AC-3.6. Front-matter `amended_by` (+DCR-7)/`review`. No Phase-1a user story edited. |
| `design/adr/0012-greenfield-workflow-formality.md` | Implement-clause rewrite (verify-at-end / testable-only / no-test complete-with-warning / intra-IMPLEMENT resume; AC-3.6 replaces the AC-20.6 mis-citation); new DCR-7 Notes entry; `amended`/Status/`spec_refs` (+AC-3.4/AC-3.6/AC-20.5/NFR-VERIFY-MAX-ITERATIONS)/`review` front-matter. No prior decision altered; rejected Alternatives rows intact. |
| `design/06-formal/contract-tests.md` | New CT-GF-5..CT-GF-8 in § 7 (no-test terminal / resume-skips-completed / end-verify-failure-surface / scaffold-first); § 7 heading + intro + closing note updated; § 6 traceability summary extended; front-matter `amended_by` (+DCR-7)/`review`/`last_reviewed`. CT-GF-1..CT-GF-4 intact. |
| `design/07-tasks.md` | New M3 tasks T-3.8 (M, deps T-3.3, C3/C2) + T-3.9 (M, deps T-3.8, C3/C2) + T-3.10 (M, deps T-3.9, C3/C15); § 6 US-1/2/3 greenfield row += T-3.8/T-3.9/T-3.10, US-7 row += T-3.10; front-matter `amended_by` (+DCR-7)/`last_reviewed`/`review`. G-gate table untouched. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| 1 | — | EARS preserved on every amended/new AC: AC-3.2 (Ev), AC-3.3 (Ev), AC-3.4 (Un), AC-3.6 (Un — `If … then the agent shall …`), AC-7.6 (Ev). One new AC minted (AC-3.6, next free id under US-3); no EARS Type changed on a rewritten AC. | Satisfied. |
| 2 | — | **AC-20.6 text is unchanged** (constraint). Verified: AC-20.6's row at `00-requirements.md:398` ("The agent shall prefer configured named commands over ad-hoc command strings for verification") is byte-identical post-amendment. The DCR records only that the **code** re-cites the no-test-command behavior to AC-3.6; no spec text re-worded. | Verified against the constraint. |
| 3 | — | Traceability preserved: every amended/new AC references a US (AC-3.2→AC-20.1/ADR-0012 [chains to US-20/US-3]; AC-3.3→US-3; AC-3.6→US-3; AC-7.6→US-1/2/3/7). `NFR-VERIFY-MAX-ITERATIONS` is still referenced by ≥ 1 AC (AC-3.4, AC-20.5, now also AC-3.6). No AC renumbered. AC-3.6 is a new id appended after AC-3.5 — no existing cross-reference broken. | Verified. |
| 4 | — | The DCR-5 Option b rejection (relax the traceability gate / loose block scan) is **unrelated** to DCR-7 and is left intact in ADR-0012's Alternatives + Decision. `TaskTraceability` **strictness** semantics are not touched by this amendment; T-3.10 only adds completion-marker **read-back** to `tasksInOrder()` (skip completed tasks), which is orthogonal to the recognition-strictness contract. | Verified — constraint honoured. |
| 5 | — | No prior DCR-1/2/3/4/5/6 decision altered: the multi-turn / approve-to-finalize / driver-authored-persistence / mid-flow-resume / prompt-emits-vocabulary / containment / gate-coverage bullets and Notes are untouched; DCR-7 is additive (one rewritten Decision bullet, one new Notes entry, two rewritten ACs + one new AC + one extended AC, four new CTs, three task rows, front-matter appends). | Verified. |
| 6 | — | Contract-tests index stays consistent: CT-GF-5..CT-GF-8 are new ids (next free after CT-GF-4), each cites a valid AC + ADR-0012; no CT renamed or removed; the JSON schemas under `06-formal/` are untouched (no fixture revalidation needed). | Verified (see Consistency checks). |
| 7 | — | Design-only amendment: no file under `src/` edited. The three source fixes (T-3.8 loop rework, T-3.9 end-verify + no-test terminal, T-3.10 resume-skip) are implemented by the coordinator's task-builder AFTER this amendment lands, in dependency order. Brownfield no-test sites (`BrownfieldDriver`) are out of scope. | Scope-clean. |

## Consistency checks (shared.md § 7 amendment procedure step 6)

- No JSON Schema under `06-formal/` was modified → `schemas_validated_against_fixtures: not_applicable`.
- `06-formal/contract-tests.md` WAS modified (added CT-GF-5..CT-GF-8): every CT id is unique and references
  a valid AC (CT-GF-5 → AC-3.6/AC-3.3; CT-GF-6 → AC-7.6/AC-3.3; CT-GF-7 → AC-3.2/AC-3.4/AC-20.5;
  CT-GF-8 → AC-3.2/AC-3.3) + ADR-0012; all four are ⚙ (loop-dependent, exercised in Phase 5 with a mocked
  Bedrock client over a temp target repo); CT-GF-1..CT-GF-4 unchanged.
  → `contract_tests_index_still_consistent: passed`.

## Constraints honoured (DCR-7 constraints block)

- Edited only files in `scope_of_design_edit` (`00-requirements.md`, `adr/0012`, `06-formal/contract-tests.md`,
  `07-tasks.md`) + this review file + `design-progress.md` (the same-commit amendment-lifecycle file). No
  source/test edit; no edit outside scope. Did NOT touch `design/07-tasks-progress.md` or
  `design/open-questions.md` (coordinator-owned; their working-tree changes are the coordinator's DCR-7
  setup, committed by the coordinator). ✅
- Did NOT edit `00-requirements.md` Phase-1a user stories (US-1..US-21 text). Amended AC-3.2/AC-3.3/AC-3.4
  bodies + added AC-3.6 + extended AC-7.6, all under existing US-3/US-7; EARS form preserved. ✅
- Preserved all traceability: every AC still references a US; every NFR still referenced by ≥ 1 AC
  (NFR-VERIFY-MAX-ITERATIONS now also AC-3.6); the EARS Type column form preserved on every amended/new AC. ✅
- **AC-20.6 TEXT REMAINS UNCHANGED** (`00-requirements.md:398` — "prefer configured named commands over
  ad-hoc command strings for verification"); the DCR only records the code re-cites the no-test-command
  behavior to AC-3.6, not AC-20.6. ✅
- The DCR-5 Option b rejection (relax the traceability gate / loose block scan) is left intact in ADR-0012's
  Alternatives; `TaskTraceability` recognition-strictness semantics are NOT touched (T-3.10 adds only
  completion-marker read-back to `tasksInOrder()`). ✅
- Design/-only: NO file under `src/` edited (the three source tasks T-3.8/T-3.9/T-3.10 are implemented by
  the coordinator's task-builder after this amendment lands). ✅
- Did NOT touch, mark, or re-judge any milestone gate (G0–G4); G3 stays OPEN. ✅
- Single amendment commit per shared.md § 7.2; `design-progress.md` updated in the same commit per § 9.5,
  with the amendment-mode front-matter flip-and-return per § 9.2. ✅

## Flagged for later phases (ripple_unresolved)

Ripple-detection grepped the full `design/` tree for every symbol in `spec_refs_touched`
(AC-3.2, AC-3.3, AC-3.6, AC-7.6, AC-20.5, AC-20.6, NFR-VERIFY-MAX-ITERATIONS, ADR-0012, US-1/2/3/7) plus the
affected class/loop names (`GreenfieldImplementLoop`, `GreenfieldDriver`, `ReplRunner`, `VerifyLoop`,
`TaskTraceability.tasksInOrder`). Hits outside the four in-scope files:

- **`design/02-architecture.md` C3 (Workflow drivers, line 91) / C15 (Session/Lineage store, line 103)
  rows** — record the DCR-1/2/3 greenfield contracts (multi-turn, driver-authored persistence, mid-flow
  resume, AC-7.6) but **do not yet note** the DCR-7 IMPLEMENT-phase verify-at-end / testable-only model
  (mark-complete-on-implementation, end-of-phase verify, no-test complete-with-warning, intra-IMPLEMENT
  resume skipping completed tasks). This is the **same precedent DCR-5 and DCR-6 set** (the C3 row not yet
  noting the prompt-emits-vocabulary / containment / gate-coverage contracts was each surfaced as a
  non-blocking out-of-scope ripple). It is **semantic** (a narrative addition requiring judgment, not a
  mechanical cross-reference substitution) and **out of the approved `scope_of_design_edit`** (only
  `00-requirements`/`adr-0012`/`contract-tests`/`07-tasks`). The architecture's greenfield-implement
  semantics live in ADR-0012, which **IS** updated; T-3.8/T-3.9/T-3.10 cite ADR-0012 + AC-3.2/AC-3.3/AC-3.6/
  AC-7.6 directly, so the missing C3/C15 notes do **not** block any of the three tasks. Surfaced to the
  user as a candidate for a future tiny doc-fold-in (could bundle with the still-open DCR-5/DCR-6 C3
  ripples) — user's call. Not a new ambiguity, not scope creep. Per the DCR `run_ripple_detection`
  directive, reported as a NON-BLOCKING `ripple_unresolved` item — scope NOT auto-expanded.

Inspected-and-dismissed grep hits (NO ripple; recorded to show they were checked):
- **`design/02-architecture.md` line 202** (failure-handling matrix: "Verify fails after
  `NFR-VERIFY-MAX-ITERATIONS` → Workflow driver → surfaced, not necessarily fatal") — generic, still
  correct under DCR-7's end-of-phase verify. No change.
- **`design/05-operations.md` line 104** ("It stopped after 5 tries on a failing test" — AC-20.5) —
  generic, applies to both brownfield and the new greenfield end-of-phase verify. Still correct. No change.
- **`design/04-apis.md` line 108 / `design/adr/0003` lines 31/34** — AC-20.6 = "prefer named commands";
  these references are about preferring named commands, NOT the no-test-command behavior, and AC-20.6's
  text is unchanged. ADR-0003 line 34's "bounded by NFR-VERIFY-MAX-ITERATIONS (5) then stop-and-surface
  (AC-3.4/20.5)" remains correct — the end-of-phase verify retains this bound. No change.
- **`design/01-overview.md` line 141 / `design/03-data-model.md` line 105** — overview's
  correctness-by-verification quality attribute ("≤ 5 fix iterations then stop") and data-model's
  multimodal/greenfield ADR-0012 reference carry no DCR-7-relevant claim. No change.

No required edit could be expressed only by breaking traceability or expanding scope, so no
`needs-conversation` is warranted.

## Implementation note for T-3.8 / T-3.9 / T-3.10 (the source changes after this amendment)

- **T-3.8 (loop rework, driven first).** Rework `GreenfieldImplementLoop.run()`: implement every task in
  breakdown order; mark each complete **on implementation** via the reused `GreenfieldArtifactStore`
  completion marker (not on VERIFIED); **remove** the per-task verify, the per-task EXHAUSTED hard-stop
  (:178-183), and the per-task `NO_TEST_COMMAND` early-return (:184-188). CT-GF-8 pins the scaffold-first
  no-hard-stop.
- **T-3.9 (end-of-phase verify + no-test terminal, driven second).** Run the configured build/test once at
  end of phase; fail → bounded retry (`NFR-VERIFY-MAX-ITERATIONS`) then stop-and-surface (AC-3.4/AC-20.5);
  no test command → skip with one warning + terminate deterministically (AC-3.6, complete-with-warning,
  exit 0, no re-loop). **Fix the AC-20.6 → AC-3.6 mis-citation** at `VerifyLoop.java:129`,
  `GreenfieldImplementLoop.java:186` & `:281`. Map the terminal no-test outcome through
  `GreenfieldDriver.runImplementPhase` + `ReplRunner` keep-alive so it does NOT re-prompt a `T-1` redo.
  CT-GF-5 (no-test terminal) + CT-GF-7 (end-verify failure surface).
- **T-3.10 (intra-IMPLEMENT resume, driven third).** On a greenfield IMPLEMENT-phase re-entry, read back the
  per-task completion markers; `TaskTraceability.tasksInOrder()` / the loop skip completed tasks and resume
  at the first incomplete one, terminating instead of restarting at `T-1`. `markComplete` becomes read+write.
  CT-GF-6.
