---
review_id: formal-batch2-r1
artifact: design/06-formal/*.schema.json, contract-tests.md, fixtures/
phase: 3-formal
round: 1
reviewed_on: 2026-06-17
reviewer: user
status: approved
approved_in: 2518fee
---

# Review — Phase 3 batch 2 (data contracts) r1

## Outcome

Approved as drafted. User: "Good to go on batch 2." **This approval resolves the `3-formal` sub-phase and closes Phase 3 (Formal Contracts).**

## Scope reviewed

- **6 JSON Schemas** (Draft 2020-12): `event.schema.json` (envelope + per-type payloads, discriminated by `type`, `$ref` into content blocks), `content-block.schema.json` (oneOf over 7 variants incl Image/Document + INV-18 name pattern), `command-result.schema.json`, `memory-entry.schema.json`, `resolved-config.schema.json`, `model-capability-profile.schema.json`.
- `contract-tests.md` — 44 indexed CTs: schema (CT-SCH-1..15), invariant (CT-INV-1..16), state-machine (CT-SM-1..7), exit-code (CT-EX-1..6); each traces to an AC; ⚙ marks loop/model-dependent tests for Phase 5.
- `fixtures/` — `session-tool-use-cycle.jsonl`, `config.global.yaml`, `memory-entry.example.md`.

## Validation performed (2026-06-17)

Ran `jsonschema` (Draft 2020-12, v4.26) + `pyyaml` in a venv:
- All 6 schemas pass the meta-schema (`check_schema`). ✅
- `session-tool-use-cycle.jsonl` — 10 events validate against `event.schema.json`, including the cross-schema `$ref` to `content-block.schema.json`. ✅
- Embedded `CommandResult` (seq 6), `config.global.yaml`, `memory-entry` front-matter — all validate. ✅
- **Validation found + fixed a fixture bug:** YAML auto-parsed the unquoted `created:` timestamp into a datetime (not a string), failing the `date-time` string check — quoted it, re-ran, all green.

## Reviewer-flagged points (accepted by user)

| # | Point | Disposition |
|---|-------|-------------|
| 1 | `$id` uses placeholder base `https://codingagent.srk/schemas/...` (not a resolvable URL). | **Keep** — it's an identifier for `$ref` resolution, not a fetch target. |
| 2 | `contract-tests.md` §6 notes two coverage gaps (greenfield phase-gating ADR-0012, sub-agent summary-only INV-11) to revisit at Phase 4. | **Defer** — milestone-scoped; revisit at task breakdown. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| — | — | No comments — approved as drafted (both flags accepted). | — |

## Phase 3 closure

With this approval, `3-formal` is resolved (batch 1: `formal-batch1-r1` — exit codes + state machine; batch 2: this review — schemas + contract tests + fixtures). **Phases 1–3 complete.** Only **Phase 4 — Tasks** remains: `07-tasks.md` (milestones + task breakdown with AC/INV traceability) and `.kiro/spec-driven.yaml` (config generation), then handoff to the Phase 5 coordinator.

## Flagged for later phases

- **Phase 4** task breakdown should add dedicated CTs (or confirm coverage) for greenfield phase-gating and sub-agent summary-only propagation (§6 gaps).
- **Phase 5** exercises the ⚙ CTs with JUnit 5 + AssertJ and a mocked Bedrock client (SUT never mocked); schema/structural CTs run from fixtures.
- The placeholder `$id` base may be swapped for the real repo URL at packaging time (cosmetic).
