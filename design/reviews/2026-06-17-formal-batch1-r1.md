---
review_id: formal-batch1-r1
artifact: design/06-formal/cli-exit-codes.md, design/06-formal/state-machine.md
phase: 3-formal
round: 1
reviewed_on: 2026-06-17
reviewer: user
status: approved
approved_in: 296e3e2
---

# Review — Phase 3 batch 1 (behavioral contracts) r1

## Outcome

Approved as drafted. User: "good to go." `cli-exit-codes.md` and `state-machine.md` move to `status: resolved`. This is batch 1 of 2 in the `3-formal` sub-phase; batch 2 (schemas + contract-tests + fixtures) follows.

## Scope reviewed

- `06-formal/README.md` — formal-layer index + conventions.
- `06-formal/cli-exit-codes.md` — authoritative exit-code contract: 7 codes (0/1/2/3/4/5/130), §2 precedence, §3 guarantees G1–G4, §4 traceability.
- `06-formal/state-machine.md` — two formal machines: A (agent loop, S0–S8 / T1–T19, stopReason-driven) and B (conversation/compaction lifecycle, L0–L5 / LT1–LT7), with INV refs + Mermaid.

## Reviewer-flagged points (raised at draft, accepted by user)

| # | Point | Disposition |
|---|-------|-------------|
| 1 | §2 exit-code precedence (`130 > 2 > 4 > 5 > 3 > 1 > 0`) — designer-decided ordering beyond the 1b seed; edge case: Ctrl-C during a model failure yields `130` not `4`. | **Accepted as drafted** — SIGINT reflects explicit user intent; startup-detectable problems precede runtime states; `1` is the unclassified fallback so it never masks a real cause. |
| 2 | State machine A granularity (9 states / 19 transitions); S3 Gating as its own state. | **Keep as drafted** — Gating is the safety chokepoint; a named state makes INV-8 (nothing reaches S4 without passing S3) a structurally assertable property. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No comments — approved as drafted (both flagged calls accepted). | — |

## Flagged for later phases

- **Batch 2** (`3-formal`): JSON Schemas (Event, ContentBlock incl Image/Document, CommandResult, MemoryEntry, ResolvedConfig, ModelCapabilityProfile), `contract-tests.md` (indexed to these state/transition ids + INV-* + ACs), `fixtures/` (validated). 3-formal resolves when batch 2 lands.
- Contract tests should reference the `S*/T*` (machine A), `L*/LT*` (machine B), and exit-code ids defined here so behavior is assertable by symbol.
- `02`/`05` narrative exit-code matrices defer to `cli-exit-codes.md` — keep consistent on any future change.
