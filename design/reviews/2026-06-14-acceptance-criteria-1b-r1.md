---
review_id: acceptance-criteria-1b-r1
artifact: design/00-requirements.md
phase: 1b-acceptance-criteria
round: 1
reviewed_on: 2026-06-14
reviewer: user
status: approved
approved_in: pending
---

# Review — 00-requirements.md (Phase 1b — EARS acceptance criteria) r1

## Outcome

Approved as drafted. User: "good to go."

## Scope reviewed

Phase 1b of `00-requirements.md`: the RD-1..RD-10 resolved-behavioral-defaults table, the CLI exit-code seed (0/1/2/3/4/5/130), 80 acceptance criteria (AC-1.1..AC-21.4) across all 21 user stories — each tagged with one EARS template (U/Ev/St/Un/Op) — and the table of 7 symbolic `NFR-*` handed to 1c. Presented with three reviewer-flagged points.

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No comments — approved as drafted. | — |

## Reviewer-flagged points (raised at draft, accepted by user)

| # | Point | Disposition |
|---|-------|-------------|
| 1 | RD-6: web-lookup is Class X → denied in `READ_ONLY` (spawns external subprocess + network). Could alternatively be treated as read-like and allowed. | **Accepted as drafted** — web-lookup denied in `READ_ONLY`. |
| 2 | AC-3.4 / AC-20.5: agent stops and surfaces after `NFR-VERIFY-MAX-ITERATIONS` failed verify attempts (vs. infinite retry). | **Accepted** — stop-and-surface is the default; bound is configurable in 1c. |
| 3 | AC-10.6 / RD-5: sub-agents do not inherit the parent's remembered grants. | **Accepted** — grants do not flow to children. |

## Decisions pinned this sub-phase

- **RD-1** `ASK_ONCE_THEN_REMEMBER` match = tool + normalized command prefix (file-writes per subtree) — resolves the §2 parked question from 1a.
- **RD-2** destructive-command denylist always prompts / never auto-approved / denied in `READ_ONLY` — closes the `rm -rf` prefix-rule hole.

## Flagged for later phases

- **1c** must pin all 7 symbolic NFRs in the "Symbolic NFRs introduced in 1b" table, plus operational NFRs (Java version, build tool, OS, timeouts, coverage gate).
- **Phase 2** permission ADR owns the full destructive-command denylist (RD-2) and the normalized-prefix matching algorithm (RD-1).
- **Phase 3** `06-formal/cli-exit-codes.md` formalizes the exit-code seed table.
