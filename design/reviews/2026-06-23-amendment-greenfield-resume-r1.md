---
review_id: amendment-greenfield-resume-r1
phase: amendment
triggered_by: T-3.2-RD-D12-D13
request_id: DCR-3
raised_by: spec-driven-task-builder
kind: architecture-update
approved_by_user_at: 2026-06-23T00:00:00+00:00
artifact: design/adr/0012-greenfield-workflow-formality.md, design/00-requirements.md, design/02-architecture.md, design/07-tasks.md, design/06-formal/contract-tests.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-3 greenfield mid-flow resume (re-derive phase-state from stamped artifacts) (r1)

## Outcome

Approved (Option A, user-approved 2026-06-23T00:00:00+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. The design baseline now records that greenfield **phase-state is
resumable**, and that the durable resume state is the **on-disk approval-stamped artifacts themselves** —
no separate phase-state persistence is introduced. A phase whose target-repo artifact bears the AC-1.5
approval stamp is approved; a fresh `codingagent --mode greenfield` reconstructs phase-state on session
start and **resumes at the first unstamped/absent phase** rather than restarting at requirements; a
transient mid-phase failure left the failed phase's artifact unstamped, so it is **retryable in place**.
Resumable greenfield sessions are keyed to the target project by the **real AC-7.3 repo key** (git remote
URL else normalized abs path, ADR-0005), brought forward to replace the fixed `Main.ONE_SHOT_LINEAGE` M0
placeholder (the run-collision root cause). The **AC-1.5 stamp is documented as one durable on-disk fact
serving both** the resume marker and the D13 clobber-protection guard (`GreenfieldArtifactStore.write()`
refuses to truncate a stamped artifact — `ApprovedArtifactProtectedException`; that code already shipped in
the working tree and lands with the resumed-task commit — it is not part of this design-edit scope, only
documented here).

## Trigger

T-3.2-RD-D12-D13 (raised by `spec-driven-task-builder`, single-agent topology). DCR-1 (driver-authored
persistence) and DCR-2 (multi-turn phase dialogue + approve-to-finalize) landed correctly, but a transient
model-backend failure (or any non-approval interruption) mid-greenfield did **not** resume the in-flight
session at the failed phase — the next REPL prompt restarted greenfield from requirements (D12). Root cause:
`GreenfieldDriver.run()` was a pure in-memory phase state machine that always began at
`GreenfieldPhase.initial()`; greenfield phase-state (which phases were approved, the current phase, the
approved-artifact content) was never persisted in a resumable form, and the existing T-1.2 resume machinery
(`SessionStore`/`SessionReplay`/`SessionLineage`/`ResumeCommand`) reconstructs the **brownfield conversation
transcript** (events → `messages[]`, AC-7.2) — it is not a greenfield phase-state projection, and there is
no EventType for "phase approved"/"advanced"/"current phase" from which to reconstruct the phase machine. No
AC/INV/ADR pinned how greenfield phase-state is persisted or resumed; ADR-0012 said greenfield "inherits
persistence for free" but was silent on resuming an interrupted greenfield session. A contributing root
cause was run collision: every run shared the fixed `Main.ONE_SHOT_LINEAGE` M0 placeholder (a known stand-in
for the AC-7.3 git-remote/abs-path repo-key), so distinct greenfield runs over different target projects
were not isolated. (D13 — the destructive sibling, silent overwrite of an approved artifact — was already
fixed in code this round as a safety stopgap derivable from AC-1.2/AC-1.5; that code is uncommitted in the
working tree and lands with the resumed-task commit. This amendment **documents** the greenfield-resume
contract that the stamp-as-resume-signal now also serves; the D13 code itself is out of this design-edit
scope.)

## Decision recorded (Option A)

Per-greenfield-project resume by re-deriving phase-state from the on-disk approved artifacts:

- The **AC-1.5 approval stamp is the phase-approved marker**; the current phase is the first
  unstamped/absent one.
- A fresh `--mode greenfield` **reconstructs phase-state from the target repo's `design/` artifacts** on
  session start and resumes at the current phase rather than restarting.
- **Transient mid-phase failure is retryable in place** (the failed phase is unstamped, so the next prompt
  re-enters it) — retry-in-place and resume-a-prior-project fall out of the same stamp-driven mechanism.
- **Keyed to the target project** by the real AC-7.3 repo key, replacing the `ONE_SHOT_LINEAGE` placeholder.
- The **AC-1.5 stamp doubles as the D13 clobber-protection signal** — one durable on-disk fact serves both.
- **Accepted tradeoff** (user-confirmed): resume re-derives only phase boundaries, not the in-phase
  multi-turn transcript; an interrupted mid-phase conversation loses its in-phase turns and re-enters at the
  phase boundary to re-converse (the approved upstream phases are still injected as context per DCR-1).

Rejected alternative recorded in ADR-0012: **Option B — first-class greenfield phase events in the session
log + replay into phase-state** (new `GREENFIELD_PHASE_APPROVED`/`_ADVANCED` EventType + a replay
projection). Rejected as a larger blast radius (touches `event.schema.json`, CT-SCH-2, the EventType
taxonomy, plus new gate/driver wiring) for resume richness beyond the phase boundary the user accepted
resuming at. Kept as a future option.

## Scope reviewed (files edited)

| File | Change |
|------|--------|
| `design/adr/0012-greenfield-workflow-formality.md` | New "Greenfield mid-flow resume" Decision bullet; Option-B alternatives-considered row; DCR-3 Notes amendment entry; front-matter `amended`/`spec_refs` (+US-7, AC-7.1/7.2/7.3/7.4/7.6)/`related` (+ADR-0005)/`review`. |
| `design/00-requirements.md` | New **AC-7.6** (Ev) under US-7 (greenfield-scoped resume; traces US-1/US-2/US-7 + ADR-0012); AC-1.5 augmented to note the stamp doubles as resume + clobber-protection marker; front-matter `amended_by` (+DCR-3)/`review`. |
| `design/02-architecture.md` § 1.2 | C3 — phase-state reconstruction from on-disk stamped artifacts on session start (Refs +US-7/AC-7.6, ADR +ADR-0005); C15 — real AC-7.3 repo-keying replacing the `ONE_SHOT_LINEAGE` M0 placeholder (Refs +AC-7.3); front-matter. |
| `design/07-tasks.md` | T-3.2 DCR-3 clobber-protection note; new **T-3.4** (greenfield mid-flow resume + AC-7.3 repo-keying-forward, M3); G3 gate + task→US mapping (US-1/2/3, US-7) updated; front-matter. |
| `design/06-formal/contract-tests.md` | New **§ 7** with **CT-GF-1** (resume from a stamped requirements artifact, no restart) + **CT-GF-2** (no-clobber of a stamped artifact); § 6 traceability summary updated (phase-gating gap addressed); front-matter `amended_by`/`review`. |

Plus `design/design-progress.md` (same-commit lifecycle per shared.md § 9.5): § 1 DCR-3 prose, § 3
carry-forward (resume contract, citing AC-7.6/AC-7.3/ADR-0012/ADR-0005/CT-GF-*), § 5 Landed line for DCR-3,
front-matter `next_action`; also backfilled the stale `pending` SHA on the DCR-2 Landed line to `a9644b4`.

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| 1 | — | EARS for the new AC: AC-7.6 is event-driven (`When the developer starts greenfield mode against a target project that already holds approval-stamped phase artifacts, the agent shall …`). | Satisfied. |
| 2 | — | Traceability preserved: AC-7.6 → US-1/US-2/US-7 + ADR-0012; every existing AC still references a US; no AC/NFR renumbered; no NFR newly orphaned (none touched). | Verified. |
| 3 | — | AC-7.3 already existed (`00-requirements.md` line 266; ADR-0005 `<repo-key>` derivation). The DCR brings the *implementation* forward (replace `ONE_SHOT_LINEAGE`), not the spec — so AC-7.3 text is unchanged; AC-7.6/C15/T-3.4 reference it. | No spec edit to AC-7.3 needed. |
| 4 | — | New CT family `CT-GF-*` added as a fresh § 7 (appended after § 6) so existing CT ids and the § 5/§ 6 section numbers are NOT renumbered — avoids breaking the `07-tasks.md` G4 "§6" reference and review-file "§ 6" refs. | Mechanically clean. |

## Constraints honoured (DCR-3 constraints block)

- Edited only files in `scope_of_design_edit` + the same-commit `design-progress.md` + this review file. No
  source/test edits. ✅
- Did NOT edit Phase-1a user stories (US-1..US-21 text). Added a new AC (AC-7.6) under existing US-7. ✅
- Preserved all traceability (every AC → a US; AC-7.6 → US-1/2/7 + ADR-0012; no NFR orphaned). ✅
- Did NOT retro-edit `design/07-tasks-progress.md` (coordinator-owned frozen audit trail). ✅
- Ran ripple-detection; resolved mechanical ripples in-commit; surfaced none unresolved that DCR-3
  introduces. The two pre-existing DCR-1/DCR-2 ripple_unresolved items (D8 AC-9.4/ADR-0004 carve-out;
  `07-tasks-progress.md` historical narrative) are NOT touched by DCR-3 and are not re-litigated. ✅
- Single amendment commit per shared.md § 7.2; `design-progress.md` updated in the same commit per § 9.5. ✅

## Flagged for later phases

- Pre-existing (NOT introduced by DCR-3, not re-flagged as new): the D8 AC-9.4 / ADR-0004 gate-decision-table
  `write_artifact` auto-approve carve-out remains a separate `ac-update` candidate; the
  `07-tasks-progress.md` historical narrative is a correctly-frozen coordinator-owned audit trail. Both are
  recorded in `open-questions.md` and were surfaced in DCR-1/DCR-2; neither is part of DCR-3.
- The greenfield-resume contract is documented; the implementation (re-derive phase-state on start + bring
  AC-7.3 repo-keying forward) is **T-3.4** in M3. The D13 no-clobber guard (`ApprovedArtifactProtectedException`)
  is already in the working tree and lands with the resumed T-3.2-RD-D12-D13 code commit.
