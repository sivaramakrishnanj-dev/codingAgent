# Formal Contracts — codingAgent

Phase 3 artifacts: the machine-checkable contracts that the prose design (Phases 1–2) implies. These are **authoritative** where they overlap with narrative docs — e.g. `cli-exit-codes.md` is the source of truth for exit codes; `02`/`05` matrices defer to it.

## Contents

| Artifact | What | Status |
|---|---|---|
| `cli-exit-codes.md` | Authoritative exit-code contract (0–5, 130) | ✅ resolved (batch 1) |
| `state-machine.md` | Agent-loop (`stopReason`) + compaction lifecycle state machines, numbered states + transitions | ✅ resolved (batch 1) |
| `*.schema.json` | JSON Schemas (Draft 2020-12) for persisted shapes (Event, ContentBlock, CommandResult, MemoryEntry, ResolvedConfig, ModelCapabilityProfile) | ⬜ batch 2 |
| `contract-tests.md` | Index of positive/negative contract tests per schema + per INV-*, each traceable to an AC | ⬜ batch 2 |
| `fixtures/` | Sample artifacts (session JSONL, config YAML, memory entry, tool-use cycle), each validated against its schema | ⬜ batch 2 |

## Conventions

- JSON Schemas: Draft 2020-12. For shapes we reverse-engineer from Converse, nested objects use `additionalProperties: true` (forward-compatibility); for shapes **we** own (events, config, memory), `additionalProperties: false` (strictness) unless noted.
- Each schema carries `$id` and a `title`; fixtures reference their schema.
- Contract tests trace to ACs (`00-requirements.md`) and invariants (`03-data-model.md` § 5).
- Sources: `03-data-model.md` (types + INV-*), the ADRs, `design-progress.md` § 6.A.1 (verified Converse facts).
