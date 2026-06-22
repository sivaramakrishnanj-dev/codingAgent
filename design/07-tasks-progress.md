---
doc: tasks-progress
last_updated: 2026-06-22
last_updated_at_commit: 0380973
total_resolved_count: 16

last_resolved:
  task: T-1.5
  title: "Output disposal: head+tail truncation, full->log, retrieval"
  resolved_at: 2026-06-22
  commit: 0380973
  iterations: { task_builder: 1 }
  dcrs_consumed: []

in_flight: null
---

## Milestone gates

### G0 (after M0 — Walking skeleton) — ✅ PASSED 2026-06-22
- Auto checks: `mvn clean verify` green (424 tests, JaCoCo ≥0.80); shaded `codingagent.jar` builds; G0 contract tests (CT-SCH-1..4/9/10/13/14, CT-INV-1/7/8/9, CT-EX-1..4/6) green.
- **Manual real-Bedrock smoke test (main agent):** `AWS_PROFILE=awsBedRockProfile`, us-east-1, `java -jar target/codingagent.jar -p "…read pom.xml…"` → **EXIT 0**, full cycle: call1 `stopReason=TOOL_USE` (read_file) → gate auto-approve (Class R) → toolResult(text) → call2 `stopReason=END_TURN`. Correct grounded answer ("Apache Maven, Java 21"). Session JSONL persisted (MODEL_RESPONSE/TOOL_USE/PERMISSION_DECISION/TOOL_RESULT logged).
- Smoke test found + fixed 2 live-only defects before passing: D1 (default model id must be `us.` inference-profile form → T-0.2-RD1, commit 218da28) and D2 (toolResult plain-string → `text` member, not `json` → T-0.5-RD2, commit 5111f78). Both now regression-tested.
- Verdict: **M0 truly complete; G0 passed.** Cleared to proceed to M1 on user direction.

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

## T-0.2-RD1 — Regression fix (DEFECT D1): default model id -> us. inference-profile form
- commit: 218da28
- review: design/reviews/code/T-0.2-RD1-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- regression-of: T-0.2 (Config model + resolver)
- notes: Correction to resolved M0 task T-0.2, found by the manual real-Bedrock smoke test that the G0 assertion needs but mocked tests structurally cannot make. ConfigDefaults.MODEL_ID was the BARE id "anthropic.claude-opus-4-8", which on-demand Converse rejects with a 400 ValidationException ("Invocation of model ID ... with on-demand throughput isn't supported. Retry ... with the ID or ARN of an inference profile ..."). With no ~/.codingagent/config.yaml present, the compiled-in default is the live value, so the fix is in the default itself. Changed MODEL_ID to the cross-region inference-profile form "us.anthropic.claude-opus-4-8" (verified ACTIVE via aws bedrock list-inference-profiles; with it the first Converse call SUCCEEDS, observed stopReason=TOOL_USE). ADR-0001 already named the us. form preferred-for-availability; aligned its pinned-default note + ConfigDefaults/ResolvedConfig Javadoc to the us. form (spec-consistency, NOT a DCR). Regression test added: modelId_isInferenceProfileFormNotBareOnDemandId asserts the default is region/scope-prefixed (us./global.) and NOT a bare anthropic. on-demand id (so a future bare-id regression fails the build). Updated the two ConfigResolverTest resolver-default assertions that read the default through the constant. Fixture config.global.yaml + test-local MODEL_ID constants intentionally keep the bare id (AC-8.1 configurable / arbitrary test values, not the NFR-MODEL-DEFAULT pinned default). 422 tests green under mvn clean verify (JaCoCo 0.80 gate met). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major/Minor/Nit. 1 Discussion item (D1: fixture/test-local bare-id literals left intentionally; suggested_amendment_kind=none).

## T-0.5-RD2 — Regression fix (DEFECT D2): toolResult content -> text member for plain strings
- commit: 5111f78
- review: design/reviews/code/T-0.5-RD2-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- regression-of: T-0.5 (Model Client: Converse wire-format mapping)
- notes: Correction to resolved M0 task T-0.5, found by the real-Bedrock smoke test full cycle once D1 was fixed. The SECOND Converse call failed with a 400 ValidationException ("The format of the value at messages.2.content.0.toolResult.content.0.json is invalid. Provide a json object for the field and try again."). Root cause: ConverseWireMapper.toWireToolResult unconditionally wrapped the tool output in a Converse toolResult content json member via ToolResultContentBlock.fromJson(...), but Converse requires json to be a JSON OBJECT; a plain-text (String) tool result (e.g. read_file contents) must use the text member. Fix: toWireToolResult now branches on the domain content runtime type — a Map (structured object, e.g. CommandResult) maps to the json member; any other non-null content maps to the text member via String.valueOf (conservative: a non-object json would re-trigger the same 400). null content still adds no content block. content-block.schema.json already sanctions content as "text, or a structured object", so this is spec-faithful, NOT a DCR. Two explicit regression tests added (mapsStringToolResultContentToTextMember: String -> text member, json() absent; mapsStructuredObjectToolResultContentToJsonMember: Map -> json object member, text() absent) — exactly the content-member assertions the structurally-blind existing tests (toolUseId/status only) never made, which is why the bug shipped. INV-6 pairing (CT-INV-5) preserved. 424 tests green under mvn clean verify (+2; 91.91% bundle line; ConverseWireMapper 98.18% line; 0.80 gate met). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major/Minor. 1 Nit (non-blocking). 0 Discussion items.

## T-1.1 — REPL: interactive loop, inline approval prompts, slash-commands, real SIGINT->130
- commit: 26b2944
- review: design/reviews/code/T-1.1-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- milestone: M1 (first task)
- notes: Real interactive REPL replacing Main's stub, plus the real SIGINT->130 handler + in-flight cancellation T-0.9 left as a seam. New testable types ReplRunner (read-eval loop; /exit, /mode, /permission show-only, unrecognized-reported; 100% line+branch) and InteractiveApprover (inline approval present-before-decide, AC-10.1, via GateRequest.presentation(); 100% line+branch), split from production-only composition in Main.runInteractive (live Bedrock client + sun.misc.Signal("INT") handler + stdin read; JaCoCo-excluded like AgentLoopFactory). SIGINT mechanism: sun.misc.Signal (jdk.unsupported, no compiler flags; pom has no forbidden-apis/enforcer), isolated in Main; ReplRunner maps both the injected interrupt flag and a caught InterruptedRunException (the existing OneShotRunner seam) to exit 130 by catch order, reconciling the real handler with the modelled seam. Streaming = render the completed LoopOutcome final text per turn (ModelClient is the sync Converse call; no token-stream source to feed). A failed turn keeps the REPL alive (developer chooses next step, AC-10.2 spirit); only SIGINT (->130) and a fatal PersistenceException (->1, AC-13.4) end the session. Stub-era interactive tests in MainTest/MainConfigTest/MainOneShotTest repointed to the INFO path (the no-arg shape now enters real REPL composition, not unit-reproducible); config fail-fast->exit-2 coverage retained. 450 tests green under mvn clean verify (+26 net: -4 stub-era, +30 new; JaCoCo 0.80 gate met). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major, 1 Minor, 0 Nit, 1 Discussion. Discussion D1 (suggested_amendment_kind=none): REPL drives each prompt as an independent AgentLoop.run turn; cross-turn model-context continuation is C15/T-1.2's replay->messages[] job, not pinned by T-1.1's ACs — surfaced as an explicit seam (the ReplLoop seam accepts a continued driver when T-1.2 lands).

## T-1.2 — Resume: list sessions, replay events -> messages[], latest-continuation default
- commit: 534f3b3
- review: design/reviews/code/T-1.2-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- milestone: M1
- notes: Three resume behaviors over C15/C1, offline JSONL replay throughout (no live model call). New SessionReplay.replay(List<Event>) -> List<ConverseMessage> maps the USER_MESSAGE + MODEL_RESPONSE events in seq order to user/assistant turns (audit events TOOL_USE/PERMISSION_DECISION/TOOL_RESULT/MODEL_USAGE/SESSION_START/OUTCOME excluded), reversing 03-data-model § 7; replay preserves INV-1 seq order and INV-6 toolUse<->toolResult pairing so the reconstructed messages[] is wire-valid for a continued Converse call. New SessionStore.listSessions(repoKey) (the AC-7.1 gap — there was no enumerate op) orders most-recent-first by log mtime desc (tie-broken by id desc) — chosen over reverse-lexical id sort because the M0 "one-shot" id is not timestamp-prefixed. New SessionLineage.latestContinuation walks DERIVED_FROM edges (not SPAWNED_BY), cycle-safe via visited-set (INV-3), built+tested with synthetic metas; goes live when M2 compaction writes DERIVED_FROM. New ResumeCommand (list()/resume(), 98% line) + CliArguments RESUME/SESSIONS kinds + sessionId() (95% line); Main dispatches resume/sessions before the config gate (pure persistence/replay; no config or model call). Listing scoped to repoKey "one-shot" (the key the system writes under today; real git-remote repo-key derivation is a deferred session task). Continuation-wiring (feeding the replayed messages[] into a live continued loop) NOT built — it needs the production Bedrock composition (AgentLoopFactory); left as the documented next-task seam, no C2 structural change forced. 492 tests green under mvn clean verify (+42; JaCoCo 0.80 gate met; new classes 98-100% line). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major, 1 Minor, 1 Nit, 1 Discussion. Discussion D1 (suggested_amendment_kind=contract-test-update): the task row's Verify cell cites CT-INV-3, but CT-INV-3 pins INV-4 compaction byte-identity (US-18) = M2/T-2.2's lane, not T-1.2; the binding replay-fidelity contract is AC-7.2 + INV-1 (+ INV-6 wire-validity). Suggest correcting the Verify cell and moving CT-INV-3 to T-2.2 — user's call (logged to open-questions).

## T-1.3 — Search tools: grep, glob, list, edit_file
- commit: 29330ff
- review: design/reviews/code/T-1.3-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- milestone: M1
- notes: Four C9 file tools in com.srk.codingagent.tool, wired into AgentLoopFactory's production registry so a live codingagent/-p run exposes them. grep = java.util.regex over file lines (AC-4.2 textual, no AST), result rows "relativePath:lineNumber:lineText" (1-based, newline-joined; no match -> empty). glob = FileSystems PathMatcher over workspace-relative paths (sorted, newline-joined). list = Files.newDirectoryStream non-recursive entry names (sorted, trailing "/" on dirs). All three OperationClass.READ -> AC-4.4 non-gated for free via AgentLoop.gateRequestFor's generic forTool path (gate auto-approves READ). edit_file = literal UNIQUE-substring splice (not whole-file replace, that is write_file): exactly-one match applies + "ok:" summary (write_file style); 0 match -> error "no match"; >1 -> error "ambiguous" (AC-5.4 spirit, no silent guess); missing file -> error (AC-4.3). edit_file SIDE_EFFECTING -> AC-5.2 gated for free. All paths confined via the reused WorkspacePaths; inputs via ToolInputs (added optionalBoolean/optionalString); schemas authored in ToolSchemas (added grep/glob/list/editFile + booleanProperty) — reuse targets reused, not reimplemented (reuse_self_check passed). No gate change. 527 tests green under mvn clean verify (+35; JaCoCo 0.80 gate met; new-class line 77-100%, EditFileTool 77% gap = IO-failure catch branches like ReadFileTool/WriteFileTool). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major, 1 Minor, 0 Nit, 1 Discussion. Discussion D1 (suggested_amendment_kind=none): edit_file uses the generic forTool gate presentation (tool-name) not write_file-style path presentation; AC-10.1 is not a T-1.3 cited ref, left as a v1 choice for a later task to revisit.

## T-1.4 — Verify loop: run configured test cmd, react to exit, bounded retries (<=5) then surface
- commit: 8cec682
- review: design/reviews/code/T-1.4-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- milestone: M1
- notes: VerifyLoop delivered as a standalone, injectable, fully unit-tested unit in com.srk.codingagent.loop (NOT bolted into AgentLoop, NOT in JaCoCo-excluded wiring). Bounded run->check->remedy->retry: runs the configured test command via an injected CommandRunner seam (CommandRunner.over(executor, command, timeout) is the production delegate to the real CommandExecutor; tests script exit-code sequences), success iff exitCode == 0 (RD-10/INV-17, CT-INV-14 asserted both directions incl. timeout's 124 = failure), and on non-zero invokes the injected RemedyAttempt seam (the model-driven "feed failure back and attempt a remedy" of AC-20.3 is the workflow driver's job, T-1.6/T-3.3 — RemedyAttempt.NONE is the no-op default) between attempts only, retrying up to config.verifyMaxIterations() (default 5, NFR-VERIFY-MAX-ITERATIONS — no literal). VerifyOutcome = { VERIFIED, EXHAUSTED, NO_TEST_COMMAND } carrying iterations-used + the final CommandResult; EXHAUSTED surfaces the relevant failure output (AC-20.5), is surfaced-not-fatal (02-arch § 3.2 — workflow decides), and is asserted at the exact boundary (N-1 fails then pass = VERIFIED; N fails = EXHAUSTED, does NOT run N+1). Unconfigured test command (commands().test()==null) -> distinct NO_TEST_COMMAND outcome (no crash, no invented ad-hoc command, AC-20.6). VerifyLoop.forConfig(executor, config, remedy) is the composition seam T-1.6/T-3.3 call; mirrored the BudgetGuard / OneShotLoop injected-seam idiom (not overloaded). 554 tests green under mvn clean verify (+27; JaCoCo 0.80 gate met; VerifyLoop/VerifyOutcome/RemedyAttempt 100% line, CommandRunner 100% line/4-of-5 branch). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major/Minor/Nit, 1 Discussion. Discussion D1 (suggested_amendment_kind=contract-test-update): CT-SM-5's Element cell "A: T13/T15" is a mis-cite (T13/T15 are the compaction transitions S1/S0->S6 and S6->S8/exit5, not verify); verify-exhausted is the S7 Surfacing state (state-machine.md § A explicitly names "verify-exhausted"). Built to the CT-SM-5 ASSERTION (AC-3.4/AC-20.5) + the S7 surface; suggest correcting the Element cell (and optionally modelling verify-exhaustion as an explicit S7 transition) — user's call (logged to open-questions). Relevant to G1: like CT-INV-3, this is a state-machine-cite bookkeeping point; G1 should judge against the contract M1 delivers (bounded verify + surface).

## T-1.5 — Output disposal: head+tail truncation, full->log, retrieval
- commit: 0380973
- review: design/reviews/code/T-1.5-r1.md
- resolved: 2026-06-22
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- milestone: M1
- notes: Output disposal half of C6 (the Context Manager; first code in the new com.srk.codingagent.context package — compaction half is M2). OutputDisposer.reduceForContext does head+tail UTF-8-byte truncation over config.outputMaxInlineBytes() (default 16384, NFR-OUTPUT-MAX-INLINE — no literal; cut on code-point boundaries, never split a multi-byte char), inserting a truncation marker naming how much was elided + the fullRef; the marker is additive overhead, not subtracted from the cap, so a small cap never drops the required head/tail. OutputRetrieval.retrieve reads the full output back from the session event log (AC-19.3 round-trip: dispose -> persist -> retrieve -> equals original). FullRef = "evt:<seq>" (session-relative, schema-valid as event.schema.json toolResult.fullRef is an unconstrained string). Uniform disposal at the loop's tool-result boundary (covers file reads AND verbose command output in one place), wired into AgentLoop.handleToolUse exactly where 02-arch § 2 annotates "result maybe > cap -> CM disposes": full output -> log FIRST (capturing the appended seq for the fullRef), then reduced copy -> model context. Reduced content is always a plain String -> wire mapper routes it to the Converse toolResult.content.text member (D2-safe; never a structured object the json member rejects). AgentLoop constructor gained an 8th arg (OutputDisposer) — PUBLIC API CHANGE; AgentLoopFactory wires it via OutputDisposer.forConfig(config), and AgentLoopTest/OneShotRunnerTest/ReplRunnerTest updated for the new arity (all green). Tier (3) summarize-via-model-call NOT built (the explicit ADR-0006 escalation, OOS for v1). 587 tests green under mvn clean verify (+33; JaCoCo 0.80 gate met; OutputDisposer 92% line, OutputRetrieval/FullRef 100%; CT-SCH-1 extended to validate full + truncated/fullRef-bearing + reduced-string disposal events against event.schema.json). Self-checks: oracle-traceability=passed, reuse=passed. 0 Blocker/Major/Minor/Nit, 0 Discussion.
