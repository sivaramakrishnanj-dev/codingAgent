---
doc: formal-contract-tests
last_reviewed: 2026-06-23
phase: 3-formal
status: resolved
review: ../reviews/2026-06-23-amendment-greenfield-resume-r1.md
approved_in: 2518fee
amended_by: [DCR-3]
---

# Contract Tests — Index

> **Phase 3 (batch 2).** The index of contract tests the Phase 5 implementation must satisfy. Each test cites the artifact it pins — a schema, an invariant (`INV-*`, `03-data-model.md` § 5), a state/transition (`S*/T*/L*/LT*`, `state-machine.md`), or an exit code (`cli-exit-codes.md`) — and traces to an AC. **Positive** = valid input accepted / behavior holds; **negative** = invalid input rejected / violation prevented. This is the *what to test*; the JUnit/AssertJ implementation is Phase 5. Schemas + fixtures here are validated (see § 5).

## 1. Schema contract tests

Validate the persisted shapes against their JSON Schemas (`*.schema.json`). Fixtures under `fixtures/` are the positive corpus.

| CT | Schema | Kind | Assertion | Traces |
|----|--------|------|-----------|--------|
| CT-SCH-1 | event | + | `fixtures/session-tool-use-cycle.jsonl` — every line validates | AC-13.1, AC-13.3 |
| CT-SCH-2 | event | − | an event with unknown `type` is rejected | AC-13.1 |
| CT-SCH-3 | event | − | a `MODEL_RESPONSE` with a `stopReason` outside the enum is rejected | §6.A.1 |
| CT-SCH-4 | event | − | non-monotonic / missing `seq` flagged (INV-1 — structural check) | INV-1 |
| CT-SCH-5 | content-block | + | each block variant (text/toolUse/toolResult/reasoning/image/document/cachePoint) validates | §2.3 |
| CT-SCH-6 | content-block | − | `DocumentBlock.name` with disallowed chars / > 200 chars rejected | INV-18 |
| CT-SCH-7 | content-block | − | `ImageBlock.format` outside png/jpeg/gif/webp rejected | §6.A multimodal |
| CT-SCH-8 | content-block | − | `DocumentBlock.format` outside the 9 formats rejected | §6.A multimodal |
| CT-SCH-9 | command-result | + | `fixtures` embedded CommandResult validates | ADR-0003 |
| CT-SCH-10 | command-result | − | missing `exitCode` rejected (it's the verification signal) | RD-10, INV-17 |
| CT-SCH-11 | memory-entry | + | `fixtures/memory-entry.example.md` front-matter validates | AC-12.2 |
| CT-SCH-12 | memory-entry | − | `tier` outside GLOBAL/PROJECT rejected | RD-9 |
| CT-SCH-13 | resolved-config | + | `fixtures/config.global.yaml` validates | AC-8.1 |
| CT-SCH-14 | resolved-config | − | unknown config key rejected (fail-fast → exit 2) | AC-8.5 |
| CT-SCH-15 | model-capability-profile | + | a Claude profile + the conservative default profile both validate | ADR-0002, OQ-J |

## 2. Invariant contract tests

Behavioral invariants from `03-data-model.md` § 5. Some are structural (testable offline); some (marked ⚙) need a running loop or a live/mock model.

| CT | INV | Kind | Assertion | Traces |
|----|-----|------|-----------|--------|
| CT-INV-1 | INV-1 | + | event log append assigns monotonic gap-free `seq`; no update/delete API exists | US-13 |
| CT-INV-2 | INV-2 ⚙ | + | each side-effecting step's event is flushed before the effect (log-before-act) | AC-13.1 |
| CT-INV-3 | INV-4 ⚙ | + | compaction creates a new session; the parent's events are byte-identical after | US-18 |
| CT-INV-4 | INV-5 | + | original conversation file still present after compaction | RD-8, AC-18.3 |
| CT-INV-5 | INV-6 | − | a `TOOL_RESULT` whose `toolUseId` has no prior `TOOL_USE` is rejected | §6.A.1 |
| CT-INV-6 | INV-7 ⚙ | + | a replayed `ReasoningBlock` keeps its `signature`; mutating it fails the call | §6.A.1 |
| CT-INV-7 | INV-8 ⚙ | + | no `ExecutingTool` (S4) without a preceding `PERMISSION_DECISION` (S3) | US-10, T7 |
| CT-INV-8 | INV-9 | − | a denylisted command never yields/auto-approves via a Grant | RD-2, AC-10.4 |
| CT-INV-9 | INV-10 | + | a Grant is not read by a separate root session, nor by a sub-agent | RD-5, AC-10.6 |
| CT-INV-10 | INV-12 | + | concurrent sub-agents ≤ NFR-SUBAGENT-MAX (default 1) | AC-17.3 |
| CT-INV-11 | INV-13 | − | no MemoryEntry persists without explicit/approved write (no auto-extract) | AC-21.4 |
| CT-INV-12 | INV-14 ⚙ | + | an externally edited/deleted memory entry is honored on next load | AC-14.2 |
| CT-INV-13 | INV-16 ⚙ | + | the Bedrock client uses SigV4; an ambient `AWS_BEARER_TOKEN_BEDROCK` is NOT used (warned) | AC-8.8, ADR-0011 |
| CT-INV-14 | INV-17 | + | unit-of-work success ⟺ zero exit from the configured test command | RD-10, AC-20.4 |
| CT-INV-15 | INV-18 | − | document attachment with a non-sanitized name is rejected before send | INV-18 |
| CT-INV-16 | INV-19 ⚙ | + | image/document attachment declined when capability profile lacks support | INV-19 |

## 3. State-machine contract tests

Reference `state-machine.md` ids. Mostly ⚙ (need the loop).

| CT | Element | Kind | Assertion | Traces |
|----|---------|------|-----------|--------|
| CT-SM-1 | A: T2→T10 ⚙ | + | `stopReason: tool_use` drives gate→exec→append-result→re-call until `end_turn` | AC-3.2, AC-20.1 |
| CT-SM-2 | A: T8 ⚙ | + | a gate denial appends `TOOL_RESULT(denied)` and the loop continues (no side effect) | AC-10.2 |
| CT-SM-3 | A: T5 ⚙ | + | `malformed_tool_use` triggers bounded repair-retry, then surfaces | §6.A.1 |
| CT-SM-4 | A: T18 ⚙ | + | SIGINT from any state cancels in-flight work, flushes, exits 130 | exit 130 |
| CT-SM-5 | A: T13/T15 ⚙ | + | verify loop stops after NFR-VERIFY-MAX-ITERATIONS and surfaces | AC-3.4, AC-20.5 |
| CT-SM-6 | B: LT2→LT3 ⚙ | + | threshold (0.85×window) triggers compaction → derived session | AC-18.1 |
| CT-SM-7 | B: LT4→exit5 ⚙ | + | compaction failure path exits 5 (context-exhausted) | cli-exit-codes 5 |

## 4. Exit-code contract tests

Reference `cli-exit-codes.md`. One-shot (`-p`) mode makes these scriptable (G3).

| CT | Exit | Kind | Assertion | Traces |
|----|------|------|-----------|--------|
| CT-EX-1 | 2 | + | malformed/unknown config key → exit 2, message names the key | AC-8.5 |
| CT-EX-2 | 4 | + | no usable SigV4 credentials → exit 4, names paths attempted | AC-8.9 |
| CT-EX-3 | 3 | + | a blocking required-action denial → exit 3 | AC-10.2 |
| CT-EX-4 | 130 | + | Ctrl-C → exit 130; session remains resumable | §02 §4 |
| CT-EX-5 | precedence | + | SIGINT during a model-backend failure → 130 (not 4) | cli-exit-codes §2 |
| CT-EX-6 | 0 | + | clean `/exit` and successful one-shot → exit 0 | US-6 |

## 5. Validation status (this batch)

Run with `jsonschema` (Draft 2020-12) + `pyyaml` on 2026-06-17:

- **All 6 schemas** pass the Draft 2020-12 meta-schema (`check_schema`). ✅
- **`session-tool-use-cycle.jsonl`** — all 10 events validate against `event.schema.json` (incl. the cross-schema `$ref` to `content-block.schema.json`). ✅
- **Embedded `CommandResult`** (seq 6) validates against `command-result.schema.json`. ✅
- **`config.global.yaml`** validates against `resolved-config.schema.json`. ✅
- **`memory-entry.example.md`** front-matter validates against `memory-entry.schema.json`. ✅ (after quoting the `created` timestamp so YAML keeps it a string — a fixture fix found *by* this validation.)

> The ⚙-marked CTs (loop/model-dependent) are specified here but exercised in Phase 5 with JUnit 5 + AssertJ and a mocked Bedrock client (the SUT is never mocked); the schema/structural CTs are runnable from fixtures today.

## 6. Traceability summary

Every CT cites an AC or a pinned symbol. Coverage spans: persistence/observability (US-13), permission/safety (US-9/10, RD-1/2), verification (US-20, RD-10), context (US-18/19), memory (US-12/14/21), multimodal (INV-18/19), credentials (AC-8.8), the exit-code contract, and (§ 7, DCR-3) greenfield mid-flow resume + clobber-protection (US-1/2/7, ADR-0012). Remaining gap to revisit when the milestone is scoped: sub-agent summary-only propagation (INV-11) has loop-level CTs implied by CT-INV-* but may warrant a dedicated test. *(The greenfield-workflow phase-gating gap previously flagged here is now addressed by T-3.1's phase-gating CT and § 7's CT-GF-* — see `07-tasks.md` M3 / G3.)*

## 7. Greenfield-workflow contract tests (DCR-3)

Pin the greenfield mid-flow resume contract (ADR-0012, AC-7.6) and the D13 clobber-protection guard (AC-1.2/AC-1.5). Both are ⚙ (need the greenfield driver + a target-repo `design/` tree on disk); exercised in Phase 5 (M3, T-3.4) with JUnit 5 + AssertJ over a temp target repo and a mocked Bedrock client.

| CT | Element | Kind | Assertion | Traces |
|----|---------|------|-----------|--------|
| CT-GF-1 | C3 greenfield resume ⚙ | + | a fresh `--mode greenfield` run over a target project whose `design/00-requirements.md` is present **and AC-1.5 approval-stamped** reconstructs phase-state from the on-disk artifacts and **resumes at the design phase** — it does **not** restart at requirements; symmetrically, an unstamped/absent requirements artifact starts (or re-enters) requirements (retry-in-place) | AC-7.6, ADR-0012, AC-1.5 |
| CT-GF-2 | C3 / `GreenfieldArtifactStore.write()` no-clobber ⚙ | − | a new greenfield run **refuses to truncate** a prior approved + AC-1.5-stamped phase artifact (raises `ApprovedArtifactProtectedException`); the approved deliverable on disk survives unchanged | AC-1.2, AC-1.5, ADR-0012 |

> CT-GF-1 keys the resumable session to the target project by the real AC-7.3 repo key (git remote URL else normalized abs path) — the M0 `ONE_SHOT_LINEAGE` placeholder is replaced (T-3.4). The two CTs share one durable on-disk fact: the AC-1.5 approval stamp is simultaneously the resume marker (CT-GF-1) and the clobber-protection marker (CT-GF-2).
