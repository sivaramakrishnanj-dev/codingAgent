---
doc: tasks-progress
last_updated: 2026-06-19
last_updated_at_commit: pending
total_resolved_count: 6

last_resolved:
  task: T-0.6
  title: "Tool registry + 3 tools: read_file, write_file, run_command (+ CommandResult, tree-kill timeout)"
  resolved_at: 2026-06-19
  commit: 4c19987
  iterations: { task_builder: 1 }
  dcrs_consumed: []

in_flight:
  task: T-0.7
  phase: TASK_BUILDER
  loop_iter: 1
  round: null
  last_handoff_kind: null
  last_handoff_status: null
  last_review_file: null
  started_at: 2026-06-19T09:30:00+05:30
  last_updated_at: 2026-06-19T09:30:00+05:30
---

## In-flight

- task: T-0.7
  phase: TASK_BUILDER
  loop_iter: 1
  round: null
  last_handoff_kind: null
  last_handoff_status: null
  last_review_file: null
  open_action_items_for_implementer: []
  open_action_items_for_tester: []
  files_in_working_tree: []
  dcrs_consumed: []
  started_at: 2026-06-19T09:30:00+05:30
  last_updated_at: 2026-06-19T09:30:00+05:30

## Resolved tasks

## T-0.1 — Project skeleton: Maven, Java 21, com.srk.codingagent packages, JUnit 5, shaded-jar build
- commit: 47de139
- review: design/reviews/code/T-0.1-r1.md
- resolved: 2026-06-17
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: Walking-skeleton scaffold; mvn clean verify green (6 tests), empty CLI launches and exits 0. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor (non-blocking). (Resolution commit recorded as 47de139 — the prior fd2412c reference predated a history rewrite; HEAD title matches and artifacts verified present.)

## T-0.2 — Config model + resolver (layered precedence, fail-fast exit 2)
- commit: 0d24818
- review: design/reviews/code/T-0.2-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0009 layered first-wins precedence (flags>project>global>defaults) into immutable ResolvedConfig; fail-fast exit 2 naming the offending key; SnakeYAML SafeConstructor loader; JaCoCo gate raised to 0.80 (first business-logic task). 90 tests green under mvn clean verify (~97.9% config coverage). CT-SCH-13/14, CT-EX-1 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 2 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: finalize exact Opus id at T-0.5; D2: IDE artifacts not staged).

## T-0.3 — Credential resolution (profile → default chain; ignore bearer; SigV4 client)
- commit: b63349c
- review: design/reviews/code/T-0.3-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0011 SigV4-only two-tier resolution (named profile -> default chain) under com.srk.codingagent.model.credentials; explicit bearer-ignore+warn (AC-8.8/INV-16); typed CredentialResolutionException -> exit 4 (MODEL_BACKEND) naming paths attempted (AC-8.9); inspectable SigV4 client seam (BedrockClientFactory) for T-0.5. AWS SDK v2 bedrockruntime pinned 2.46.7 (2.46.10 no longer resolves; ADR-0001 directed latest-stable). 114 tests green under mvn clean verify (~95.3% bundle coverage); no live AWS calls (injected SDK seams). CT-INV-13, CT-EX-2 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: SDK pin 2.46.7; D2: exit-4 CLI dispatch deferred to loop task).

## T-0.4 — Event log + session store (JSONL append, flush-per-event, ids/ts at boundary)
- commit: 50713e5
- review: design/reviews/code/T-0.4-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0005 event-sourced persistence under com.srk.codingagent.persistence (C14 EventLog + C15 SessionStore). Append-only JSONL writer assigns monotonic gap-free seq (INV-1; no update/delete API), flushes per event before returning (INV-2), surfaces persist failures (AC-13.4); ids/ts captured at the boundary (no in-process clock/UUID). Jackson for JSON; sealed Event/EventPayload/ContentBlock hierarchy (minimal text/toolUse/toolResult blocks — full Converse round-trip deferred to T-0.5). networknt json-schema-validator (test-only) validates CTs against the formal event.schema.json. 181 tests green under mvn clean verify (94.28% bundle, 93.4% persistence). CT-SCH-1/2/3/4, CT-INV-1 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 2 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: future shared validation/path utility; D2: streaming read for T-1.2 replay).

## T-0.5 — Model Client: Converse request/response + wire-format mapping (text/toolUse/toolResult blocks)
- commit: ff1f93c
- review: design/reviews/code/T-0.5-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0001 Model Client (C4) under com.srk.codingagent.model.converse — non-streaming Converse request-build + response-parse (03 §7 wire-format boundary, §6.A.1 facts). Maps our ContentBlock (text/toolUse/toolResult) <-> SDK ContentBlock, StopReason wire<->domain, usage->ModelUsagePayload. INV-6 toolUse<->toolResult pairing enforced via order-preserving scan (rejects orphan/out-of-order toolResult before any backend call); CT-INV-5 negative + INV-6 positive covered. Injected BedrockRuntimeClient seam -> request/parse fully unit-tested (hand-rolled stub, no Mockito, no live AWS call); reuses T-0.3 BedrockClientFactory + T-0.4 persistence types (no duplication). Typed ModelBackendException -> exit 4, ToolProtocolException for INV-6 faults. Streaming + reasoning/image/document/cachePoint mapping deferred to owning tasks (T-1.1/T-2.x/T-4.2). 229 tests green under mvn clean verify (95.24% bundle, 98.01% converse pkg). CT-INV-5 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor, 1 Nit (non-blocking). 1 Discussion item (D1: ConverseMessage/Role placement).

## T-0.6 — Tool registry + 3 tools: read_file, write_file, run_command (+ CommandResult, tree-kill timeout)
- commit: 4c19987
- review: design/reviews/code/T-0.6-r1.md
- resolved: 2026-06-19
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0001/0003 under com.srk.codingagent.tool. C7 ToolRegistry (name/description/JSON inputSchema + Class R/X marker, renders SDK ToolConfiguration that T-0.5's ConverseWireMapper consumes, dispatches ContentBlock.ToolUse->ContentBlock.ToolResult, unknown tool->structured error). C9 read_file (R; path+offset/limit, workspace-confined) + write_file (X; path+content, ok/diff summary). C10 run_command (X) via ProcessBuilder(sh -c) with SEPARATE stdout/stderr redirect, concurrent drain (no pipe deadlock), Process.waitFor(timeout)+ProcessHandle.descendants() tree-kill on NFR-CMD-TIMEOUT (timedOut=true, exit 124). CommandResult matches command-result.schema.json; exitCode captured faithfully (INV-17/CT-INV-14). Reuses persistence.OperationClass for R/X marker (no parallel enum). Permission gate (T-0.7), disposal (T-1.5), verify loop (T-1.4), other tools deferred. 270 tests green under mvn clean verify (90.93% bundle, 77.91% tool pkg). CT-SCH-9/10, CT-INV-14 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 3 Minor, 1 Nit (non-blocking). 1 Discussion item (D1: ToolInputs vs Payloads non-blank checks).
