# Design — codingAgent

Spec-driven design baseline for **codingAgent**, an LLM-based coding agent (local Java CLI on AWS Bedrock). This directory is the authoritative design; Phase 5 implementation is driven from `07-tasks.md`.

**Resuming a design session?** Read `design-progress.md` **first** — its front-matter is the single source of truth for "where are we." Then confirm the next move before editing.

## Phase status

| Phase | Sub-phase | Artifact | Status |
|---|---|---|---|
| 1 — Requirements | 1a user stories | `00-requirements.md` § 1a | ✅ resolved |
| 1 — Requirements | 1b EARS acceptance criteria | `00-requirements.md` § 1b | ✅ resolved |
| 1 — Requirements | 1c NFRs | `00-requirements.md` § 1c | ✅ resolved |
| 2 — Design | overview | `01-overview.md` | ✅ resolved |
| 2 — Design | architecture (doc) | `02-architecture.md` | ✅ resolved |
| 2 — Design | architecture ADRs (batch 1: 0001–0004) | `adr/` | ✅ resolved |
| 2 — Design | architecture ADRs (batch 2: 0005–0012) | `adr/` | ✅ resolved |
| 2 — Design | data model | `03-data-model.md` | ✅ resolved |
| 2 — Design | apis | `04-apis.md` | ✅ resolved |
| 2 — Design | operations | `05-operations.md` | ⬜ not started |
| 3 — Formal | schemas, state machine, exit codes, contract tests, fixtures | `06-formal/` | ⬜ not started |
| 4 — Tasks | milestones + task breakdown | `07-tasks.md` | ⬜ not started |

## Map

- `design-progress.md` — cross-session state (Phases 1–4). **Authoritative on resume.**
- `00-requirements.md` — Phase 1 (personas, user stories, EARS ACs, NFRs).
- `01-`…`05-` — Phase 2 design artifacts (written in Phase 2).
- `06-formal/` — Phase 3 formal contracts (written in Phase 3).
- `07-tasks.md` — Phase 4 milestone + task breakdown (written in Phase 4).
- `adr/` — Architecture Decision Records (emerge during Phase 2).
- `reviews/` — the approval artifacts; one file per reviewed sub-phase/artifact. See `reviews/README.md`.

## Conventions

- Every artifact carries front-matter (`doc`, `status`, `phase`, `review`, `approved_in`).
- Diagrams are Mermaid only.
- Acceptance criteria use EARS templates (Phase 1b onward).
- NFRs are numeric/versioned/named (Phase 1c).
- No phase advances without explicit user approval, recorded as a review file.
