---
review_id: data-model-r1
artifact: design/03-data-model.md
phase: 2-data-model
round: 1
reviewed_on: 2026-06-15
reviewer: user
status: approved
approved_in: f864cef
---

# Review — 03-data-model.md (Phase 2 — data model) r1

## Outcome

Approved as drafted, with one in-review scope addition (multimodal input). User: "Good to go on 03-data-model.md."

## Scope reviewed

`03-data-model.md`: ER diagram; 8 core entities (Conversation, Event, ContentBlock, CommandResult, MemoryEntry, ModelCapabilityProfile, Grant, ResolvedConfig); the 13-kind EventType taxonomy; 10 enums (incl. ExitCode); numbered invariants INV-1..INV-19; compaction lifecycle state machine; wire-format boundary table (our types ↔ Converse).

## In-review scope addition (user-directed)

**Multimodal input (image + document) added to v1** — load-bearing for greenfield/spec-driven (ADR-0012): developers share design diagrams (US-1) and PDF/Word use-case docs. Verified against the Converse API refs (2026-06-15):
- `ImageBlock` formats: png | jpeg | gif | webp.
- `DocumentBlock` formats: pdf | csv | doc | docx | xls | xlsx | html | txt | md — **Word/Excel attach natively, no conversion**.
- Raw-bytes source (SDK base64-encodes; S3-source OOS for a local CLI).
- Capability-gated via `ModelCapabilityProfile` (+`supportsImageInput`/`supportsDocumentInput`); graceful decline when unsupported (INV-19).
- `DocumentBlock.name` sanitized/neutral — prompt-injection surface flagged by AWS docs (INV-18).
- Still OOS: image/video **generation** (output) and **video input** (VideoBlock).

Propagated to: `03-data-model.md` §2.3 (two new ContentBlock variants), §2.6 (profile fields), §5 (INV-18/19), §7 (wire-format rows); `00-requirements.md` OOS table (multimodal in/out boundary made explicit); `01-overview.md` §3.1 in-scope; `design-progress.md` §6.A.1.

**Note (traceability):** this addition edited two already-`resolved` docs — `00-requirements.md` (OOS row) and `01-overview.md` (scope bullet) — a small additive clarification, consistent with prior post-resolution refinements (NFR-AWS-CREDENTIALS, NFR-MODEL-PROVIDER).

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No comments beyond the multimodal-input addition above — otherwise approved as drafted. | — |

## Reviewer-flagged points (raised at draft)

| # | Point | Disposition |
|---|-------|-------------|
| 1 | EventType: 14 rows for "13 logical kinds" (subagent spawn+result split). | **Keep as drafted** — §3 explains the 13-logical/14-row framing inline; no ADR-0005 churn for a cosmetic count. |
| 2 | ContentBlock excluded multimodal. | **Resolved by user** — image + document input now in scope (above). |
| 3 | 19 invariants — which become Phase 3 contract tests deferred to Phase 3. | **Accepted** — Phase 3 selects; some (INV-7 signature replay) need a live model to test. |

## Flagged for later phases

- **Phase 3** (`06-formal/`) formalizes the persisted shapes as JSON schemas (Event, ContentBlock incl. Image/Document, CommandResult, MemoryEntry, ResolvedConfig, ModelCapabilityProfile), promotes the §6 compaction state machine into `state-machine.md`, and selects which INV-* become contract tests.
- INV-18 (document-name sanitization) + INV-19 (capability-gated attachments) are Phase 5 testable requirements.
- Next: `04-apis.md` (CLI contract, Converse boundary in prose, tool/delegate contracts).
