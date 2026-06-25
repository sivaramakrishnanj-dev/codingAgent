---
doc: formal-contract-tests
last_reviewed: 2026-06-24
phase: 3-formal
status: resolved
review: ../reviews/2026-06-25-amendment-greenfield-implement-verify-model-r1.md
approved_in: 2518fee
amended_by: [DCR-3, DCR-4, DCR-6, DCR-7]
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
| CT-SCH-16 | resolved-config | + | a config carrying `bedrockCallConnectTimeoutSeconds` and `bedrockCallResponseTimeoutSeconds` (integers ≥ 1) validates (DCR-4) | AC-8.10, NFR-BEDROCK-CALL-TIMEOUT |
| CT-SCH-17 | resolved-config | + | when both Bedrock-timeout keys are absent, the resolver applies the defaults connect 10 / response 300 (default-when-absent; DCR-4) | AC-8.11, NFR-BEDROCK-CALL-TIMEOUT |

> **CT-SCH-16 / CT-SCH-17 note (DCR-4).** CT-SCH-16 is a pure schema-validation positive (a config object that includes both timeout keys validates against `resolved-config.schema.json`); it does not require mutating the shared `fixtures/config.global.yaml` positive corpus — the implementer supplies the timeout-bearing config object in the Phase 5 test, the same way the CT-EX/CT-INV tests construct their own inputs. CT-SCH-17 asserts **resolver behavior**, not raw schema validation: JSON-Schema `default` keywords are documentary (validators don't inject defaults), so the config resolver (C17, ADR-0009) is what must apply connect 10 / response 300 when the keys are absent — CT-SCH-17 pins that the resolved `ResolvedConfig` carries those defaults. Both exercised in Phase 5 (T-4.6).

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

Every CT cites an AC or a pinned symbol. Coverage spans: persistence/observability (US-13), permission/safety (US-9/10, RD-1/2), verification (US-20, RD-10), context (US-18/19), memory (US-12/14/21), multimodal (INV-18/19), credentials (AC-8.8), Bedrock-call timeouts (AC-8.10/8.11, NFR-BEDROCK-CALL-TIMEOUT — CT-SCH-16/17, DCR-4), the exit-code contract, and (§ 7) greenfield mid-flow resume + clobber-protection (DCR-3 — US-1/2/7, ADR-0012), gate real-breakdown recognition-coverage hardening (DCR-6 — CT-GF-3, AC-2.2/AC-2.5, ADR-0012), `write_artifact` containment to the known design-doc artifacts (DCR-6 — CT-GF-4, AC-1.4/RD-7, ADR-0012), and the greenfield IMPLEMENT-phase verify-at-end / testable-only verification model (DCR-7 — CT-GF-5 no-test-command terminal complete-with-warning, CT-GF-6 resume-skips-completed-tasks, CT-GF-7 end-verify-failure-surfaces, CT-GF-8 scaffold-first-no-hard-stop; AC-3.2/AC-3.3/AC-3.6/AC-7.6, ADR-0012). Remaining gap to revisit when the milestone is scoped: sub-agent summary-only propagation (INV-11) has loop-level CTs implied by CT-INV-* but may warrant a dedicated test. *(The greenfield-workflow phase-gating gap previously flagged here is now addressed by T-3.1's phase-gating CT and § 7's CT-GF-* — see `07-tasks.md` M3 / G3.)*

## 7. Greenfield-workflow contract tests (DCR-3, DCR-6, DCR-7)

Pin the greenfield mid-flow resume contract (ADR-0012, AC-7.6) and the D13 clobber-protection guard (AC-1.2/AC-1.5) — CT-GF-1/CT-GF-2, DCR-3 — plus the gate real-breakdown miscounting hardening (AC-2.2/AC-2.5, ADR-0012/DCR-6) and the `write_artifact` containment tightening (AC-1.4, RD-7, ADR-0012/DCR-6) — CT-GF-3/CT-GF-4 — plus the greenfield IMPLEMENT-phase **verify-at-end / testable-only** verification model (AC-3.2/AC-3.3/AC-3.6/AC-7.6, ADR-0012/DCR-7) — CT-GF-5/CT-GF-6/CT-GF-7/CT-GF-8. All are ⚙ except the gate-recognition assertions in CT-GF-3 and the path-resolution assertions in CT-GF-4, which are structural (the `TaskTraceability` parser and the `GreenfieldArtifactStore` path resolver are exercisable offline). Exercised in Phase 5 (M3) with JUnit 5 + AssertJ over a temp target repo and a mocked Bedrock client (CT-GF-1/2 in T-3.4; CT-GF-3 in T-3.7; CT-GF-4 in T-3.6; CT-GF-5/CT-GF-7 in T-3.9; CT-GF-6 in T-3.10; CT-GF-8 in T-3.8).

| CT | Element | Kind | Assertion | Traces |
|----|---------|------|-----------|--------|
| CT-GF-1 | C3 greenfield resume ⚙ | + | a fresh `--mode greenfield` run over a target project whose `design/00-requirements.md` is present **and AC-1.5 approval-stamped** reconstructs phase-state from the on-disk artifacts and **resumes at the design phase** — it does **not** restart at requirements; symmetrically, an unstamped/absent requirements artifact starts (or re-enters) requirements (retry-in-place) | AC-7.6, ADR-0012, AC-1.5 |
| CT-GF-2 | C3 / `GreenfieldArtifactStore.write()` no-clobber ⚙ | − | a new greenfield run **refuses to truncate** a prior approved + AC-1.5-stamped phase artifact (raises `ApprovedArtifactProtectedException`); the approved deliverable on disk survives unchanged | AC-1.2, AC-1.5, ADR-0012 |
| CT-GF-3 | C3 / `TaskTraceability` real-breakdown recognition (DCR-6) | + | the hardened gate **correctly counts/flags** each of the four real-breakdown shapes — recognition COVERAGE only, no strictness relaxation: (a) a **multi-line `**Refs:**` block** under a task does not cause the same-line-ref rule to mis-flag a task that does carry an on-line ref; (b) a **range heading** `T-3 through T-8` expands so `T-3`..`T-8` are each individually recognized (and each correctly flagged untraced if its line carries no valid ref, not silently collapsed to only `T-3`); (c) an **arrow/sequencing-diagram line** `T-1 -> T-2` is skipped, not read as one task; (d) a **bold-wrapped id** in a table row `\| **T-1** \|` is recognized as a task. Plus: a **full Sonnet-style breakdown** containing these shapes, every task line carrying ≥ 1 valid `US-`/`AC-`/`NFR-`/`RD-`/`INV-` ref, now **passes** the gate (it previously miscounted untraced/duplicate tasks and refused). The strict same-line-ref rule and the rejection of a loose block scan (DCR-5 Option b) both hold — a task whose own line lacks a valid ref is still flagged untraced even if a sibling line carries one. | AC-2.2, AC-2.5, ADR-0012 |
| CT-GF-4 | C3 / `GreenfieldArtifactStore`/`WriteArtifactTool` containment (DCR-6) | − | the tightened store/tool allows a write to **only** the three known design-doc artifacts (`design/00-requirements.md`, `design/01-design.md`, `design/02-tasks.md` ALLOWED) and **rejects** any other path under `design/`: `design/impl/pom.xml` REJECTED, a source file under `design/impl/src/**` REJECTED, and a bare source path (outside `design/`) REJECTED (the latter already held under the prior `startsWith(<workspaceRoot>/design)` check) | AC-1.4, RD-7, ADR-0012 |
| CT-GF-5 | C3 greenfield implement no-test-command terminal (DCR-7) ⚙ | + | a greenfield implement phase with **no configured test command** terminates **cleanly**: every task in the breakdown is implemented and marked complete, the end-of-phase verification is **skipped with a single warning**, and the outcome is **terminal** (exit 0, complete-with-warning) — the driver/REPL does **NOT** re-prompt into a fresh implement attempt (no livelock). Asserts the fixed behavior the code previously mis-cited as AC-20.6 now binds to AC-3.6. | AC-3.6, AC-3.3, ADR-0012 |
| CT-GF-6 | C3 greenfield intra-IMPLEMENT resume (DCR-7) ⚙ | + | a greenfield re-entry whose reconstructed phase is IMPLEMENT over a **partially-completed** breakdown reads back the per-task completion markers and **resumes at the first incomplete task**, terminating — it does **NOT** restart at the first task (`T-1`). Completed tasks (marked complete on implementation, AC-3.3) are skipped. | AC-7.6, AC-3.3, ADR-0012 |
| CT-GF-7 | C3 / `VerifyLoop` end-of-phase verify-failure surface (DCR-7) ⚙ | + | when the **end-of-phase** verification fails, the agent retries bounded by `NFR-VERIFY-MAX-ITERATIONS` and then **stops and surfaces** the failure (AC-3.4/AC-20.5) — the bound applies to the single end-of-phase verify, not to a per-task verify. | AC-3.2, AC-3.4, AC-20.5, ADR-0012 |
| CT-GF-8 | C3 greenfield scaffold-first breakdown (DCR-7) ⚙ | + | a **scaffold-first** breakdown (`T-1` scaffold, `T-2` pom, later tasks add testable code) implements **all** tasks in order and verifies **once at the end** — it does **NOT** hard-stop at `T-1` because the not-yet-buildable scaffold cannot pass a per-task verify (per-task verify dropped; tasks not independently testable are implemented without per-task verification). | AC-3.2, AC-3.3, ADR-0012 |

> CT-GF-1 keys the resumable session to the target project by the real AC-7.3 repo key (git remote URL else normalized abs path) — the M0 `ONE_SHOT_LINEAGE` placeholder is replaced (T-3.4). CT-GF-1/CT-GF-2 share one durable on-disk fact: the AC-1.5 approval stamp is simultaneously the resume marker (CT-GF-1) and the clobber-protection marker (CT-GF-2). **CT-GF-3 / CT-GF-4 (DCR-6)** pin the two G3-blocking real-breakdown defects: CT-GF-3 the gate's recognition-COVERAGE miscounting (dedup / arrow-line skip / range-heading expansion / bold-table-cell ids) — a hardening that changes coverage, **not** the strict same-line-ref guarantee (a loose block scan, DCR-5 Option b, stays rejected); CT-GF-4 the `write_artifact` containment allowlist (only the three known design-doc artifacts, source paths under `design/` rejected) closing the AC-1.4 pre-approval source-write hole. The single-line task row that the gate counts cleanly is itself guaranteed by the greenfield TASKS prompt (T-3.7). **CT-GF-5..CT-GF-8 (DCR-7)** pin the IMPLEMENT-phase verify-at-end / testable-only model that fixes the three G3-blocking IMPLEMENT defects: CT-GF-5 the no-test-command **terminal** complete-with-warning (no livelock re-prompt — D1, the AC-20.6→AC-3.6 mis-citation fix); CT-GF-6 **intra-IMPLEMENT resume** skipping completed tasks (D2 — markComplete-on-implementation is now read back); CT-GF-7 the **end-of-phase** verify-failure surface bounded by `NFR-VERIFY-MAX-ITERATIONS` (the bound moved from per-task to end-of-phase); CT-GF-8 the **scaffold-first** breakdown implementing all tasks then verifying once at the end (D3 — no per-task hard-stop at the not-yet-buildable scaffold). Per-task verification is dropped: a task is marked complete **on implementation** (AC-3.3), and only the single end-of-phase verify gates the phase (AC-3.2).
