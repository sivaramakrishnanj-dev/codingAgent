---
doc: tasks-progress
last_updated: 2026-06-22
last_updated_at_commit: 0fa658d
total_resolved_count: 9

last_resolved:
  task: T-0.9
  title: "CLI one-shot (-p), exit codes, SIGINT->130 (C1)"
  resolved_at: 2026-06-22
  commit: 0fa658d
  iterations: { task_builder: 1 }
  dcrs_consumed: []

in_flight: null
---

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

## T-0.7 — Permission gate: 4 modes, Class R/X, destructive denylist, grant matching (RD-1)
- commit: 870a938
- review: design/reviews/code/T-0.7-r1.md
- resolved: 2026-06-19
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0004 Permission Gate (C8) under com.srk.codingagent.permission — standalone PermissionGate the loop consults BEFORE ToolRegistry.dispatch. Eval order: Class R auto (AC-9.6) -> denylist test for run_command (AC-10.4) -> 4-mode table (AC-9.1-9.5). RD-1 grant matching: quote-honoring ShellTokenizer -> executable basename + known-subcommand-set normalization -> MatchKey (run_command:<exe>[ <subcmd>] | write:<subtree> | <tool>); ASK_ONCE_THEN_REMEMBER auto-approves matches. RD-2 conservative denylist (denylist-first, basename+case-fold, per-segment chaining; rm-noempty/mv-cp-dest realized as FS-state-independent pattern proxies). INV-9 enforced structurally in GateDecision ctor (denylisted => no matchedGrant). Lineage-scoped GrantStore; forSubAgent mints fresh empty store (INV-10/AC-10.6). Injected Approver seam (REPL UI is T-1.1). Loop S3->S4 wiring + PERMISSION_DECISION/TOOL_RESULT(denied) event emission deferred to T-0.8. Adversarial tokenizer+denylist tests (quoting, ;/&&/| chains, rm -rf $HOME, casing, /usr/bin/rm, redirect, curl|sh, sudo, fork-bomb, kill -9). 353 tests green under mvn clean verify (92.54% bundle, 97.58% permission pkg). CT-INV-7/8/9, CT-SM-2 satisfied (gate-level; loop wiring T-0.8). Reuses persistence.OperationClass/PermissionDecisionPayload + config.PermissionMode (no duplication). Self-checks: oracle-traceability=passed, reuse=passed. 2 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: ADR denylist rows 1/5 vs FS-state-independent pattern proxy — logged in open-questions; D2: fork-bomb regex-shape vs tokenizer).

## T-0.8 — Agent loop: stopReason dispatch (tool_use<->end_turn), log-before-act (C2)
- commit: 0b0661a
- review: design/reviews/code/T-0.8-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0001 owned-loop C2 under com.srk.codingagent.loop. AgentLoop drives state-machine A: T1 append USER_MESSAGE -> seed transcript; per turn S1 ModelClient.converse -> T2/T3 append MODEL_RESPONSE+MODEL_USAGE before acting (INV-2); dispatch on StopReason. tool_use (T2->S2): per toolUse block log TOOL_USE digest -> PermissionGate.evaluate -> append PERMISSION_DECISION before exec (INV-8 gate-in-the-middle) -> on approve ToolRegistry.dispatch + TOOL_RESULT (INV-6 toolUseId pairing); on deny TOOL_RESULT(denied), no handler run (CT-SM-2/T8); batch results as one user msg, re-call (T10). end_turn/stop_sequence -> LoopOutcome.completed(finalText); edge reasons (max_tokens, ctx-exceeded, guardrail, content_filtered, malformed_*) -> LoopOutcome.surfaced(stopReason) — compaction body (S6/machine B), bounded repair-retry (CT-SM-3), SIGINT (T18), exit dispatch (S8/T-0.9) deferred to clean seams. BudgetGuard T13 seam injected (NONE = no-compaction prod wiring). ADR-0005 injected clock Supplier — loop never calls Instant.now(). Fail-closed gating: unknown/coarse toolUse gated SIDE_EFFECTING (never auto-approved as read) before dispatch. ToolRegistry.operationClass(name) accessor added so the loop builds the right GateRequest without a parallel classifier. Pure composition of T-0.4 EventLog / T-0.5 ModelClient / T-0.6 ToolRegistry / T-0.7 PermissionGate (no reimplementation). Scripted hand-rolled BedrockRuntimeClient double — no live AWS call. 380 tests green under mvn clean verify (93.1% bundle line, 100% loop line / 89.7% loop branch). CT-SM-1, CT-SM-2, CT-INV-2 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor, 1 Nit (non-blocking). 1 Discussion item (D1: loop folds differentiated max_tokens/malformed_* handling into uniform SURFACED — exactly the deferred scope, not a defect; suggested_amendment_kind=none).

## T-0.9 — CLI one-shot (-p), exit codes, SIGINT->130 (C1)
- commit: 0fa658d
- review: design/reviews/code/T-0.9-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: 04-apis §1 + cli-exit-codes.md under com.srk.codingagent.cli (C1). Main.run(String[]) extended: CliArguments.parse dispatches by shape (one-shot -p / info / interactive); usage/config faults map to exit 2 BEFORE any model call (ADR-0009 fail-fast preserved, CT-EX-1). OneShotRunner runs the AgentLoop (T-0.8) on the -p prompt and maps LoopOutcome + thrown exceptions to ExitCode honoring cli-exit-codes §2 precedence enforced structurally by catch order: InterruptedRunException->130 caught FIRST (always wins, CT-EX-4), then CredentialResolutionException/ModelBackendException->4 (CT-EX-2 preserved, names paths), UserAbortedException->3, catch-all->1; completed outcome->0 (CT-EX-6). Exit 3 (blocking denial, CT-EX-3) realized via the existing Approver seam: NonInteractiveApprover throws UserAbortedException when a non-interactive one-shot gate must prompt (AC-10.2 'gated op the run cannot proceed without'; the loop never surfaces a denial, so this is the spec-faithful blocking signal). SIGINT->130 is a logic-only mapping seam at M0 (InterruptedRunException; interrupt status re-asserted) asserted without OS signal delivery — the signal handler + stream/subprocess cancellation are T-1.1; event log flushes per event (T-0.4) so resumability needs no special M0 teardown. Surfaced edge reasons: model_context_window_exceeded->5 CONTEXT_EXHAUSTED (no compaction at M0), guardrail/content_filtered/malformed_*->1 INTERNAL (contract pins no other code). run-and-map (OneShotRunner, fully unit-tested via injected loop + scripted Bedrock double) split from the live-AWS composition root (AgentLoopFactory, JaCoCo-excluded like Main bootstrap). 421 tests green under mvn clean verify (+41; 91.9% bundle line; OneShotRunner 100% line; 0.80 floor met). CT-EX-3/4/6 satisfied; CT-EX-1/2 preserved. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor (non-blocking). 1 Discussion item (D1: SIGINT->130 logic-only seam at M0, no production thrower yet — T-1.1 scope, not a defect; suggested_amendment_kind=none).
