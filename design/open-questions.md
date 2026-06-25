# Open questions — codingAgent (Phase 5 coordinator-logged)

Discussion-lane findings surfaced by the single-agent task-builder during Phase 5.
These are **non-blocking** — they did not gate task resolution. The coordinator
logs them here for user review; none require action to proceed. A finding with a
`suggested_amendment_kind` other than `none` is a candidate for a future
designer amendment (DCR) **only if the user decides** — the coordinator never
auto-invokes the designer.

## Discussion items from T-0.7 — 2026-06-19

### D1 — denylist rows 1 & 5 reference FS state the gate avoids (suggested: ac-update)
- task: T-0.7
- spec_refs: ADR-0004 (destructive denylist rows 1 "rm of a non-empty target" and 5 "mv/cp whose destination exists")
- finding: The ADR phrases two denylist rows in terms of filesystem state (target
  non-emptiness; destination existence) that the permission gate deliberately does
  NOT read at decision time (an FS probe would be TOCTOU-racy and couple
  authorization to FS state). The implementation realizes them as conservative,
  FS-state-independent pattern proxies: any `rm` with a recursive/forced flag is
  flagged (a bare `rm file` is not pattern-flagged but still gates as Class X); any
  `mv`/`cp` with two-or-more non-flag operands is flagged. This over-flags (safe),
  never under-flags. Recorded in T-0.7 `stated_assumptions`.
- coordinator note: the task resolved cleanly with the conservative implementation;
  this is a spec-vs-code wording alignment, not a code defect. If the user wants the
  ADR contract to state the pattern-proxy explicitly (so the formal contract matches
  the code), that would be an `ac-update`/ADR-clarification amendment — user's call.
- status: open (informational; no action required to proceed)

### D2 — fork-bomb matched by regex shape, not tokenizer
- task: T-0.7
- spec_refs: ADR-0004 (denylist row 13, fork-bomb `:(){ :|:& };:`)
- finding: The fork-bomb shape has no clean shell-token decomposition, so it is
  matched by a whitespace-stripped regex shape rather than via the ShellTokenizer
  like the other denylist patterns. Conservative; noted for consistency awareness
  only. `suggested_amendment_kind: none`.
- status: open (informational; no action required to proceed)

## Discussion items from T-1.1 — 2026-06-22

### D1 — REPL drives each prompt as an independent loop turn (cross-turn context is T-1.2's job)
- task: T-1.1
- spec_refs: 04-apis § 1.1 ("multi-turn work"), AC-7.1, AC-7.2, INV-1
- finding: `AgentLoop.run(String)` starts a fresh transcript per call (it appends a
  USER_MESSAGE and seeds a new local `messages[]` inside `run`), so the T-1.1 REPL
  drives each developer prompt as an independent model turn. Cross-turn *model-context
  continuation* (the model seeing prior turns) is the replay -> messages[] concern the
  architecture assigns to C15 / T-1.2 (Resume), and is not pinned by any AC in T-1.1's
  cited set. The task-builder surfaced this as a Discussion rather than a
  `design-change-needed`: the `ReplRunner.ReplLoop` seam is shaped to accept a
  continued-conversation driver when T-1.2 lands, with no `ReplRunner` change.
  `suggested_amendment_kind: none`.
- coordinator note: flagging for awareness as T-1.2 is the very next task — when T-1.2
  builds replay -> messages[], confirm whether the REPL should then drive a *continued*
  conversation (accumulating turns) rather than independent turns. That is a T-1.2/C2
  design decision, not a T-1.1 defect. No action required to proceed.
- status: open (informational; no action required to proceed)

## Discussion items from T-1.2 — 2026-06-22

### D1 — task row Verify cell cites CT-INV-3, but CT-INV-3 pins compaction byte-identity (M2), not replay fidelity
- task: T-1.2
- spec_refs: CT-INV-3 (07-tasks.md M1 Verify cell), AC-7.2, INV-1, INV-4, INV-6
- suggested_amendment_kind: contract-test-update
- finding: The T-1.2 row's Verify column reads "CT-INV-3 (replay fidelity)", but CT-INV-3
  as indexed in 06-formal/contract-tests.md § 2 pins INV-4 — "compaction creates a new
  session; the parent's events are byte-identical after" (Traces: US-18). That is the
  M2 compaction task's contract (T-2.2), not resume. The replay-fidelity contract T-1.2
  actually realizes is AC-7.2 (reconstruct context by replaying persisted events)
  preserving INV-1 (gap-free seq order), plus INV-6 (the replayed transcript is wire-valid:
  every toolResult pairs with a prior toolUse). The task-builder built+tested against
  AC-7.2 + INV-1 + INV-6 and did NOT pull compaction forward to satisfy the mis-cited id.
- coordinator note: this is a spec-bookkeeping inconsistency in the task table, not a code
  defect — T-1.2 resolved cleanly against the correct binding contract. If the user wants
  the task table corrected (move CT-INV-3 to T-2.2's Verify cell; replace T-1.2's Verify
  cell with AC-7.2/INV-1/INV-6), that is a `contract-test-update` / tasks-table amendment
  (DCR) — user's call. Relevant to verify at gate G1: G1's checklist lists "CT-INV-3" as a
  green criterion after M1, but CT-INV-3 (compaction byte-identity) cannot be green until
  M2 builds compaction. Flagging so the G1 gate is judged against the contracts M1 actually
  delivers (replay fidelity), not against an M2 contract.
- status: open (informational; no action required to proceed)

## Discussion items from T-1.3 — 2026-06-22

### D1 — edit_file uses the generic tool-name gate presentation, not write_file-style path presentation
- task: T-1.3
- spec_refs: AC-10.1
- suggested_amendment_kind: none
- finding: The new Class-X `edit_file` tool routes through the permission gate via
  AgentLoop.gateRequestFor's generic `forTool` path, so an approval prompt presents the
  tool name ("edit_file") rather than the file path + change summary the way `write_file`
  does (via the `forWrite` path). AC-10.1 ("present the exact operation: command string,
  or file path + change summary") is not a cited ref for T-1.3, so the task-builder left
  this as a v1 choice. The task-builder considered extending `gateRequestFor` to give
  edit_file the path-style presentation but deferred it.
- coordinator note: low-signal, non-blocking. If a future task (or the AC-10.1-owning
  T-1.1 follow-up) wants edit_file's approval prompt to show the path like write_file's,
  it is a small, narrow AgentLoop.gateRequestFor enhancement — no spec amendment needed
  (suggested_amendment_kind=none). Noted for awareness; T-1.3 resolved cleanly.
- status: open (informational; no action required to proceed)

## Discussion items from T-1.4 — 2026-06-22

### D1 — CT-SM-5 Element cell "A: T13/T15" mis-cites the compaction transitions; verify-exhausted is S7
- task: T-1.4
- spec_refs: CT-SM-5 (06-formal/contract-tests.md § 3), state-machine.md § A (S7, T13, T15), AC-3.4, AC-20.5
- suggested_amendment_kind: contract-test-update (optionally state-machine-update)
- finding: CT-SM-5's *assertion* — "verify loop stops after NFR-VERIFY-MAX-ITERATIONS and
  surfaces" (Traces AC-3.4, AC-20.5) — is exactly T-1.4's contract and is satisfied. But
  its *Element* cell reads "A: T13/T15", and in state-machine.md § A, T13/T15 are the
  COMPACTION transitions (T13: S1/S0->S6 on usage>=0.85xwindow or /compact -> machine B;
  T15: S6->S8 compaction-failed -> exit 5), not verify. The verify loop is not a numbered
  transition in machine A; the verify-exhausted surface is the S7 Surfacing state, whose
  own definition in § A explicitly lists "verify-exhausted" as one of its meanings. The
  task-builder built to the CT-SM-5 assertion + the S7 surface and did NOT contort the
  verify loop into the compaction transitions.
- coordinator note: spec-bookkeeping inconsistency in a CT Element cell, not a code defect —
  T-1.4 resolved cleanly (0 Blocker/Major/Minor/Nit). If the user wants it corrected, the
  fix is a contract-test-update (point CT-SM-5's Element at S7 verify-exhausted / US-20),
  optionally a state-machine-update to model verify-exhaustion as an explicit transition
  into S7. This is the SECOND state-machine-cite mismatch in M1 (cf. T-1.2 D1 / CT-INV-3) —
  the two together are a candidate for a single small tasks-table/contract-tests amendment
  the user may choose to run after G1. Relevant to G1 judgment: G1's checklist lists
  CT-SM-5 (and CT-INV-3) as green criteria; G1 should be judged against the contracts M1
  actually delivers (bounded verify + surface; replay fidelity), with the compaction-keyed
  Element cells corrected when M2 lands or via the amendment.
- status: open (informational; no action required to proceed)

## Discussion items from T-1.6 — 2026-06-22

### D1 — no agent-process exit code for a "verify-exhausted brownfield run"
- task: T-1.6
- spec_refs: cli-exit-codes.md (G4 / the 0-5/130 contract), AC-20.5, AC-5.3
- suggested_amendment_kind: exit-code-update
- finding: When a brownfield run completes — the agent explored, changed, and ran the
  verify loop — but the change still fails its tests after NFR-VERIFY-MAX-ITERATIONS
  (BrownfieldOutcome VERIFY_EXHAUSTED), there is no exit code in the cli-exit-codes
  contract for "the agent ran fine but the requested change did not pass verification."
  The task-builder resolved it as exit 0 with the failure output surfaced in the final
  text (AC-20.5): the agent completed its work, a verify failure is not an internal fault
  (so not exit 1), and the contract pins no other code — and the contract says new
  categories require a formal amendment, so it did not invent one.
- coordinator note: non-blocking; T-1.6 resolved cleanly (0 Blocker/Major/Minor/Nit). The
  exit-0-with-surfaced-output disposition is a defensible reading of the existing contract.
  However, a developer scripting `codingagent -p` in CI may WANT a distinct non-zero exit
  to detect "change applied but tests still red" without parsing stdout — that is the
  exit-code-update amendment candidate (a new code, e.g. for verification-not-achieved,
  distinct from 0 success / 1 internal / 3 user-aborted). User's call after G1. Relevant to
  G1: the gate's "explore->edit->verify->resume" criterion is about the cycle WORKING, not
  about a specific exit code for the verify-failed case; the v1 disposition (surface +
  exit 0) satisfies the cycle. THIRD spec-cite/contract point in M1 (cf. T-1.2 CT-INV-3,
  T-1.4 CT-SM-5) — the three are candidates for one consolidated post-G1 amendment if the
  user chooses.
- status: open (informational; no action required to proceed)

## Discussion items from T-2.1 — 2026-06-22

### D1 — concrete context-window figures are not pinned by any spec symbol (suggested: data-model-update)
- task: T-2.1
- spec_refs: ADR-0002 (capability profile carries contextWindowTokens), NFR-MODEL-CONTEXT-WINDOW
- suggested_amendment_kind: data-model-update
- finding: T-2.1 needs a concrete contextWindowTokens per model to compute the 0.85xwindow
  threshold, but no spec symbol pins the actual integers. The task-builder used Claude=200K
  and a safe-minimum fallback=100K as documented choices in ModelCapabilityProfile /
  AgentLoopFactory. ADR-0002 specifies the *mechanism* (prefix registry + conservative
  default) and NFR-MODEL-CONTEXT-WINDOW says "model-dependent; read from config at startup",
  but neither fixes the numbers.
- coordinator note: non-blocking; T-2.1 resolved cleanly (0 Blocker/Major). If the team wants
  the per-family window figures pinned (a small per-family table in 03-data-model or an NFR
  value), that is a data-model-update / nfr-update amendment — user's call. Naturally bundles
  with T-4.3 (the full capability-profile registry task) which owns CT-SCH-15 and the profile
  schema. No action required to proceed.
- status: open (informational; no action required to proceed)

### D2 — conservative-default window "read from config" deferred to T-4.3 (suggested: schema-update)
- task: T-2.1
- spec_refs: ADR-0002 ("conservative default profile ... safe minimum context window read from config NFR-MODEL-CONTEXT-WINDOW"), NFR-MODEL-CONTEXT-WINDOW
- suggested_amendment_kind: schema-update
- finding: ADR-0002 says the unknown-id default window should be read from config
  (NFR-MODEL-CONTEXT-WINDOW). T-2.1 deferred adding that config key — adding it would touch
  design/06-formal/resolved-config.schema.json + the CT-SCH-13/14 contract + ConfigKeys/
  ConfigDefaults/ConfigResolver, work that overlaps T-4.3 (the full capability registry) and
  sits outside T-2.1's minimal-viable-shape mandate and one-review-file write scope. The
  safe-minimum is currently a documented compiled-in constant
  (AgentLoopFactory.CONSERVATIVE_DEFAULT_CONTEXT_WINDOW_TOKENS = 100000) in the JaCoCo-excluded
  composition root, and the live default model resolves to a real Claude window via the registry,
  so the fallback path is off the production happy path today.
- coordinator note: non-blocking; defensible scoping. When T-4.3 lands the full
  ModelCapabilityProfile registry + schema (CT-SCH-15), add the contextWindowTokens config key
  there so the conservative default is config-sourced per ADR-0002. If the user wants the config
  key pulled in earlier, that is a schema-update amendment — user's call. No action required to proceed.
- status: open (informational; no action required to proceed)

## Discussion items from T-2.4 — 2026-06-22

### D1 — ADR-0007 prose + data-model § 2.5 casing disagree with the authoritative memory-entry schema (suggested: doc/adr-clarification)
- task: T-2.4
- spec_refs: ADR-0007 (entry-format prose example: tier `global | project` lowercase), 03-data-model.md § 2.5 (MemoryStatus enum named uppercase), memory-entry.schema.json (tier enum GLOBAL/PROJECT uppercase; status enum active/retired lowercase)
- suggested_amendment_kind: doc/adr-clarification
- finding: The authoritative formal contract — memory-entry.schema.json + the validated
  fixture memory-entry.example.md (CT-SCH-11/12) — uses tier UPPERCASE (GLOBAL/PROJECT)
  and status lowercase (active/retired). ADR-0007's entry-format prose example shows tier
  in lowercase (`tier: global | project`), and 03-data-model § 2.5 names the status values
  in uppercase. The implementation followed the SCHEMA (the formal contract + the thing the
  contract tests validate): MemoryTier {GLOBAL, PROJECT} serialized uppercase, MemoryStatus
  {ACTIVE, RETIRED} serialized lowercase via wireValue(). CT-SCH-11/12 are green.
- coordinator note: non-blocking; T-2.4 resolved cleanly against the binding schema. This is
  a spec-prose-vs-schema wording alignment, not a code defect. If the user wants the ADR-0007
  prose example and the 03-data-model § 2.5 wording brought into line with the schema casing,
  that is a doc/adr-clarification amendment (no code or schema change — the code already
  matches the schema) — user's call. Could bundle with the deferred M1 consolidated amendment.
- status: open (informational; no action required to proceed)

## Discussion items from T-3.3 — 2026-06-23

### D1 — AC-3.5 (single-specific-task request) is type Op and not implemented as a distinct request shape (suggested: ac-update)
- task: T-3.3
- spec_refs: AC-3.5 (US-3, type Op: "Where the developer requested a single specific task, the agent shall implement only that task and then stop.")
- suggested_amendment_kind: ac-update
- finding: T-3.3's GreenfieldImplementLoop implements the full approved task breakdown one task at a
  time in order (AC-3.1/3.2/3.3/3.4). AC-3.5 is an OPTIONAL ("Op" / "Where ...") criterion for the
  case where the developer asked for a single specific task; the minimal-viable implement loop does
  not model a distinct "single specific task" request shape — it runs the approved breakdown. The
  task-builder surfaced this as a Discussion (the disposition the task guidance prescribed for AC-3.5)
  rather than absorbing the extra request-shape scope into T-3.3.
- coordinator note: non-blocking; T-3.3 resolved cleanly (0 Blocker/Major/Minor). AC-3.5 is type Op
  (optional), so leaving it as a future request-shape enhancement is spec-faithful — the loop's
  seams (GreenfieldImplementLoop.run reads the tasks artifact + a per-task turn) would accept a
  single-task driver with no structural change. If the team later wants `--task <id>` / a single
  specific-task greenfield request to implement-only-that-one-and-stop, promote AC-3.5 to mandatory
  and pin a request shape (an ac-update / small CliArguments + driver enhancement). User's call.
- status: open (informational; no action required to proceed)

## Discussion items from T-2.8 — 2026-06-23

### D1 — AC-18.5 live learning-harvest seam is now wired but inert at v1 (suggested: ac-update)
- task: T-2.8
- spec_refs: AC-18.5 ("propose durable learnings for memory before archiving"), ADR-0007, AC-21.4/INV-13 (no auto-extract)
- suggested_amendment_kind: ac-update
- finding: T-2.8 wired the compaction learning-harvest seam (MemoryLearningHarvester over the
  shared MemoryStore + LearningProposer) into the live AgentLoopFactory so AC-18.5's
  harvest-before-archive ordering is structurally present on a LIVE compaction. But the seam is
  inert at v1: the extractor is `LearningExtractor.NONE` (no production heuristic exists for
  turning a compaction summary into durable-learning candidates — T-2.5 deliberately left
  extraction a seam) and the one-shot approver is `LearningApprover.DENY_ALL` (the safe default
  when no developer terminal is present on the `-p` path — AC-21.4/INV-13, never auto-extract). So
  a live threshold compaction proposes ZERO learnings today: the harvest moment is reachable but
  persists nothing. The summarize->derive->continue path itself is fully live (the G2 headline);
  only the AC-18.5 harvest sub-step is reachable-but-inert.
- coordinator note: non-blocking; T-2.8 resolved cleanly (0 Blocker/Major/Minor). This is a
  defensible scoping choice — wiring a real extraction heuristic + an interactive approver is its
  own piece of work, not a loop-wiring task, and the no-auto-extract default is the correct
  anti-poisoning stance. When the interactive REPL wires a real LearningApprover (and a later task
  adds a LearningExtractor heuristic), durable learnings will flow through this SAME already-wired
  path with no re-wiring. If the user wants AC-18.5 to explicitly note that the live extraction
  heuristic + interactive approval are a deferred sub-step (so the spec matches the reachable-
  but-inert v1 state), that is an ac-update amendment — user's call. No action required to proceed.
- status: open (informational; no action required to proceed)

## Discussion items from T-3.2-RD-D8 — 2026-06-23

### D1 — AC-9.4 "prompt before every Class X" reads absolutely; the ADR-0012-sanctioned design/-confined write_artifact pre-approval write is an exception in practice (suggested: ac-update)
- task: T-3.2-RD-D8
- spec_refs: AC-9.4 ("While in ASK_EVERY_TIME, the agent shall prompt before every Class X operation"), ADR-0012 ("the agent writes only design markdown … until the breakdown is approved"), RD-7, AC-1.2, AC-2.1
- suggested_amendment_kind: ac-update
- finding: The D8 fix auto-approves the `write_artifact` Class-X tool at the PermissionGate (without a
  per-operation prompt) within greenfield pre-approval phases, because a per-op prompt contending with
  the phase-approval gate for the developer's single shared stdin line was starving the content write
  (the D8 regression). AC-9.4 literally says "prompt before every Class X operation", which on its face
  includes write_artifact. The behaviour is ALREADY authorized by ADR-0012 (design-markdown writes are
  the one sanctioned write before the breakdown is approved) + RD-7/AC-1.2/AC-2.1 (the content MUST be
  persisted), and the carve-out is narrow (tool-name-keyed, design/-confined, source-write Class X and
  the destructive denylist keep full gating). So no spec amendment is required to SHIP the fix — the
  task-builder implemented it as code without one. The open question is purely prose precision: whether
  AC-9.4 should carry an explicit clause noting the design/-confined write_artifact pre-approval
  exception so the formal AC matches the live behaviour.
- coordinator note: non-blocking; T-3.2-RD-D8 resolved cleanly (0 Blocker/Major/Minor). If the user
  wants AC-9.4 (or ADR-0004's gate decision table) to state the write_artifact pre-approval exception
  explicitly, that is an ac-update amendment — user's call. Naturally bundles with the recurring
  greenfield-prose amendment candidates (T-3.3 D1, T-2.8 D1). A related, narrower observation: the
  tool-name-keyed carve-out also auto-approves write_artifact in READ_ONLY (vs AC-9.2's "deny all
  Class X"); write_artifact is only ever in the greenfield pre-approval registry and a READ_ONLY
  greenfield session is contradictory by design intent, so this is defensible and documented, but the
  user may wish to fold a READ_ONLY note into the same clarification. No action required to proceed.
- status: open (informational; no action required to proceed)

## OQ-design-1 — T-3.2-RD-D10 design-change requested — 2026-06-23

- kind: architecture-update
- raised_by: spec-driven-task-builder
- spec_refs_touched: AC-1.2, AC-1.4, AC-1.5, AC-2.1, AC-2.5, ADR-0012 (C3 greenfield driver), C7 (write_artifact tool note)
- problem_statement: |
  Greenfield artifact persistence (AC-1.2 requirements / AC-2.1 design+tasks markdown) was
  architected to depend on the live model emitting a `write_artifact` Class-X tool_use during each
  pre-approval phase. Four successive fixes landed (T-3.2-RD-D6 approval, D7 prompt, D8 approval-
  contention, D9 inputSchema) and all PASSED their mocked task-builder tests — but live ground truth
  (latest clean G3 run, D9 in place) shows the model reaches stopReason=END_TURN in each pre-approval
  phase WITHOUT ever emitting a write_artifact tool_use: it answers in prose and stops. write_artifact
  executes ZERO times across the whole greenfield run; the only GreenfieldArtifactStore activity is the
  gate's "Appended approval line" stamp. So design/00-requirements.md + design/01-design.md hold only
  approval stamps, design/02-tasks.md is never created, AC-2.5 traceability correctly refuses to find
  tasks, and greenfield never reaches implement. The D9 probe proved the plumbing is correct when a
  write_artifact tool_use IS scripted (dispatch->tool->store->disk works). The remaining gap is empirical
  and model-behavioral: persistence depends on a model tool call the live model does not reliably make
  from the greenfield phase prompt — a defect class the mocked tests cannot catch by construction
  (they script the tool_use the model never actually emits). Two contributing observations: (1) the
  fresh-conversation-per-phase shape means later phases (design, tasks) cannot see the approved earlier
  artifact content — transcript discontinuity; (2) the persistence mechanism (model-tool-dependent) is
  the wrong contract for a deliverable the workflow must guarantee.
- options_considered:
  - id: A
    summary: |
      Driver-authored deliverables. The GreenfieldDriver deterministically authors each pre-approval
      phase deliverable from the phase's settled output: on each pre-approval phase's END_TURN, the
      driver captures the model's final deliverable prose and writes it to the phase artifact
      (design/00-requirements.md / 01-design.md / 02-tasks.md) via GreenfieldArtifactStore.write() in
      code (truncating write of the composed content); the ArtifactApprovalGate then appends the AC-1.5
      stamp. Later phases inject the approved earlier-phase artifact content into their phase prompt to
      fix the transcript discontinuity. write_artifact stays registered/available but is no longer the
      persistence path. AC-1.4 (design/-confined; source-write tools withheld) preserved.
    pros: Persistence is driver-guaranteed in code — does not depend on a model tool call the live model won't reliably make; gives a real, mock-stable contract a test can assert deterministically; fixes transcript discontinuity by injecting approved artifacts into later phases.
    cons: Driver, not model, owns artifact composition (the model still produces the prose; the driver captures + writes it); write_artifact becomes vestigial/optional rather than the documented mechanism.
  - id: B
    summary: |
      Keep model-tool-dependent persistence; iterate the prompt/Converse wiring (lead with the tool-call
      instruction, mark the turn incomplete until write_artifact is called, possibly set Converse
      toolChoice to require/encourage the tool call) until the live model reliably calls write_artifact.
    pros: Keeps the original architecture (model authors + persists via its own tool call); no driver-authored-deliverable shift.
    cons: Empirically unproven after 4 fixes; depends on live model behavior the mocked tests cannot verify; toolChoice is set nowhere in the codebase today; remains a live-only-observable defect class with ongoing spend/iteration risk and no mock-stable contract.
- recommended_option: A
- chosen_option: A
- user_decision: approved
- user_approval:
    approved_at: 2026-06-23T12:12:58+00:00
    approver_note: |
      Approve Option A — driver deterministically authors each greenfield phase deliverable from the
      phase's settled output via GreenfieldArtifactStore.write(); gate then stamps (AC-1.5). Driver
      injects approved earlier-phase artifacts into later-phase prompts to fix transcript discontinuity.
      AC-1.4 preserved (design/-confined; source-write tools stay withheld). write_artifact becomes
      optional, not the persistence mechanism.
    revised_from_original: false
- scope_of_design_edit:
  - design/02-architecture.md § 1.2 (C3 greenfield driver authors deliverables deterministically; C7 note write_artifact optional for persistence)
  - design/adr/0012-greenfield-workflow-formality.md (record driver-authored phase-deliverable persistence; AC-1.4 design/-confinement preserved; later phases get approved earlier artifacts injected into their prompt)
  - design/00-requirements.md (AC-1.2 / AC-2.1 persistence is driver-guaranteed, not model-tool-dependent; AC-2.5 still verifies the written tasks artifact's traceability; EARS form + all traceability preserved)
  - design/07-tasks.md (T-3.2 row note: deterministic driver-authored artifact persistence)
- designer_status: amended
- amendment_commit: 67b12b6
- resumed_task_commit: 0f31b91
- ripple_unresolved: |
    Designer flagged two ripple_unresolved items, BOTH pre-existing and BOTH already recorded as
    explicitly NOT part of DCR-1: (1) the D8 discussion item (AC-9.4 / ADR-0004 gate-decision-table
    write_artifact auto-approve carve-out — with persistence now driver-authored, write_artifact is
    optional and the carve-out is even more clearly a vestigial-tool concern; left as a separate
    ac-update candidate); (2) the D1 output-token-cap follow-on (default 4096 maxTokens can truncate
    the driver-captured END_TURN prose for a large design/tasks deliverable; left as a separate
    nfr-update follow-on). Both surfaced to the user before task resume; neither blocks DCR-1 resume.

## D1 — greenfield design/tasks artifacts can exceed the model's default 4096 output-token cap (non-blocking nfr-update follow-on) — 2026-06-23

- orthogonal to: OQ-design-1 / DCR-1 (recorded separately; NOT part of DCR-1)
- kind: nfr-update (follow-on candidate)
- raised_by: main agent (observed live during T-3.2-RD-D10 investigation)
- spec_refs: NFR-OUTPUT-MAX-INLINE (output budgeting), the Converse request build (C4 ConverseWireMapper.toRequest), greenfield artifact authoring (C3)
- finding: |
  Greenfield design/tasks artifacts can exceed the model's default 4096 output-token cap — a live run
  observed stopReason=MAX_TOKENS while the model was producing artifact prose. The Converse request
  currently sets NO maxTokens / inferenceConfig (ConverseWireMapper.toRequest sets toolConfig but leaves
  inferenceConfig unset, so the backend applies its default 4096 output cap). A substantial greenfield
  design or task-breakdown deliverable can be truncated at that cap, producing a partial artifact even
  once driver-authored persistence (DCR-1 Option A) captures the END_TURN text.
- proposed follow-on: pin an explicit output-token budget on the Converse request (inferenceConfig.maxTokens),
  and/or chunk artifact authoring across turns so a large deliverable is not cut off at the default cap.
- coordinator note: explicitly NOT part of DCR-1 (DCR-1 fixes the persistence mechanism, not the output
  cap). Recorded here per user direction as a separate non-blocking follow-on candidate. User's call
  whether/when to raise it as its own nfr-update amendment.
- status: open (informational; no action required to proceed) — **folded into DCR-2's re-implementation (the
  D1 output-token-cap fix is bundled with the multi-turn-phase work since both ride the same Converse
  request path); see OQ-design-2 below.**

## OQ-design-2 — T-3.2 design-change requested (greenfield multi-turn phase dialogue + approve-to-finalize) — 2026-06-23

- kind: architecture-update
- raised_by: user via main-agent steering (the user is steering greenfield interaction design)
- spec_refs_touched: AC-1.1, AC-1.4, AC-1.5, AC-2.1, AC-2.3, AC-2.4, AC-2.5, ADR-0012 (C3 greenfield driver), C3 (02-architecture § 1.2), T-3.x (07-tasks), NFR-OUTPUT-MAX-INLINE (D1 follow-on folded in)
- problem_statement: |
  DCR-1 (driver-authored persistence) was necessary and landed correctly — requirements/design/tasks now
  write real content (3432/1720/2365 bytes live). But the live G3 smoke test after DCR-1 proved a deeper
  defect: each greenfield phase (requirements, design, tasks) runs as ONE LLM turn. Given a terse idea, the
  requirements-phase model correctly does AC-1.1 (asks clarifying questions) instead of inventing
  requirements — so the driver persists the model's *questions* as 00-requirements.md. Design reads
  questions -> more questions; tasks reads questions -> honestly refuses to fabricate; AC-2.5 correctly
  rejects 0 tasks. Every component is behaving correctly; the INTERACTION SHAPE is wrong — a phase that
  needs a multi-turn conversation to converge is being run as a single turn. The model never finishes
  shaping the deliverable before it's captured. Two contributing facts: (1) the fresh-conversation-per-phase
  shape means the model cannot see its own prior turns WITHIN a phase (transcript discontinuity within a
  phase, not just across phases); (2) the single-turn-per-phase contract is the wrong shape for a
  deliverable that converges conversationally.
- options_considered:
  - id: A
    summary: |
      Multi-turn phase dialogue with approve-to-finalize. Each greenfield pre-approval phase becomes a
      MULTI-TURN CONVERSATION: the developer converses with the agent across several REPL turns to shape the
      phase deliverable; the model refines the deliverable each round (and may ask AC-1.1 clarifying
      questions — it now has room to). The phase transcript carries across turns WITHIN the phase (fix the
      fresh-conversation-per-phase discontinuity so the model sees its own prior turns within the phase).
      FINALIZE = APPROVE (one gesture, reuse ArtifactApprovalGate / InteractiveGreenfieldApproval): each
      round the developer is offered the approval prompt; when the developer APPROVES, that is the finalize
      signal — the driver captures the model's latest substantive deliverable text in the phase
      conversation, persists it via GreenfieldArtifactStore.write() (the DCR-1 driver-authored path, kept),
      records the AC-1.5 approval timestamp, and advances. A non-approve answer keeps the phase conversation
      going (another refining turn); it does NOT persist-and-stop. Later phases inject the approved
      earlier-phase artifacts into their conversation context (DCR-1 cross-phase continuity kept). Preserve
      AC-1.4 (source tools structurally withheld pre-approval), driver-authored persistence, AC-2.5
      traceability on the written tasks artifact, AC-1.5 timestamp, AC-2.3 per-phase approval.
    pros: Matches how a phase actually converges (conversationally); gives the model room to do AC-1.1
      clarification and refine; fixes in-phase transcript discontinuity; reuses the existing approval gate as
      the finalize signal; keeps every DCR-1/prior guarantee.
    cons: Larger interaction surface to build + test (multi-turn loop state, in-phase transcript carry);
      approve-to-finalize overloads the approval gesture (mitigated: it IS the natural finalize point).
  - id: B
    summary: |
      Keep the single-turn-per-phase shape; iterate the per-phase prompt/playbook to coax the model into
      producing a complete deliverable in one turn (lead harder, forbid questions, demand a full doc).
    pros: Smallest change; no multi-turn loop machinery.
    cons: Fights AC-1.1 (the model SHOULD ask clarifying questions on a terse idea); a single turn cannot
      converge a deliverable that genuinely needs back-and-forth; empirically the wrong shape (live G3
      proved the single turn captures questions, not a deliverable). Rejected.
- recommended_option: A
- chosen_option: A
- user_decision: approved
- user_approval:
    approved_at: 2026-06-23T00:00:00+00:00
    approver_note: |
      Approve Option A — multi-turn phase dialogue with approve-to-finalize. Each greenfield phase is a
      multi-turn conversation; the phase transcript carries within the phase; approve = finalize (driver
      captures the converged deliverable, persists via GreenfieldArtifactStore.write(), stamps AC-1.5,
      advances); non-approve = another refining turn (no persist-and-stop). Preserve AC-1.4 source-withholding,
      DCR-1 driver-authored persistence, AC-2.5 traceability, AC-1.5 timestamp, AC-2.3 per-phase approval;
      later phases inject approved earlier artifacts. ALSO fold in the D1 output-token-cap fix (set a
      sensible inferenceConfig.maxTokens on the greenfield Converse request so a large requirements/design/
      tasks deliverable is not truncated at the default 4096 cap).
    revised_from_original: false
- scope_of_design_edit:
  - design/adr/0012-greenfield-workflow-formality.md (THE main change: each pre-approval phase is a multi-turn conversation; approve-to-finalize captures+persists the converged deliverable; phase transcript carries within a phase; later phases see approved earlier artifacts; driver-authored persistence retained from DCR-1; AC-1.4 source-withholding retained)
  - design/00-requirements.md (AC-1.1 multi-turn dialogue within the requirements phase; AC-1.5 approval=finalize; AC-2.3; AC-2.4 revise-and-re-request maps to a non-approve refining turn; keep EARS form + traceability)
  - design/02-architecture.md § 1.2 (C3 greenfield driver: multi-turn phase loop, approve-to-finalize, in-phase transcript continuity)
  - design/07-tasks.md (T-3.x rows: note multi-turn phase dialogue + approve-to-finalize)
- designer_status: amended
- amendment_commit: a9644b4
- resumed_task_commit: 8786a13
- ripple_unresolved: |
    Designer flagged two ripple_unresolved items, BOTH pre-existing and BOTH explicitly NOT part of DCR-2:
    (1) the D8 discussion item (AC-9.4 / ADR-0004 gate-decision-table write_artifact auto-approve carve-out)
    — with persistence driver-authored (DCR-1) and the phase now multi-turn (DCR-2), write_artifact is even
    more clearly a vestigial-tool concern; left as a separate ac-update candidate (constraints forbade
    editing ADR-0004's gate-decision table in this amendment). (2) the design/07-tasks-progress.md
    historical single-turn/first-END_TURN narrative — an intentional, correctly-frozen audit trail
    (coordinator-owned), not a live spec cross-reference; must not be retro-edited. Neither is a NEW ripple
    introduced by DCR-2; both surfaced to the user before code resume. Neither blocks DCR-2 resume.


## Discussion items from T-3.2-RD-D11 — 2026-06-23

### D1 — spec says persist "on approval"; impl writes the latest deliverable each round before the gate, then finalizes the converged one at approval (suggested: ac-update)
- task: T-3.2-RD-D11
- spec_refs: AC-1.5, ADR-0012 ("on approval the driver captures the converged deliverable ... persists it")
- suggested_amendment_kind: ac-update
- finding: AC-1.5 / ADR-0012 (DCR-2) read as "persist on approval". The implementation writes the
  latest deliverable to the phase artifact (a truncating overwrite) EACH ROUND before the gate, then
  finalizes (AC-1.5 stamp + advance) at approval. Writing each round is required by two existing
  collaborators: InteractiveGreenfieldApproval presents the artifact before asking the developer to
  confirm (present-before-confirm), and the tasks gate reads the on-disk breakdown to verify AC-2.5
  traceability before stamping — both need the current deliverable on disk at the moment of the gate.
  The CONVERGED, stamped, kept artifact is the one present at approval; a non-approve round never stamps
  and the next refining turn overwrites with the refined deliverable. So the observable contract
  ("the approved artifact holds the converged deliverable + the stamp") is met; only the literal wording
  "persist ON approval" vs "write each round, finalize at approval" differs.
- coordinator note: non-blocking; T-3.2-RD-D11 resolved cleanly (0 Blocker/Major; 1 Minor). This is a
  one-line AC/ADR wording clarification, not a code defect — the code's observable behaviour matches the
  intent. If the user wants AC-1.5 / ADR-0012 to state "the driver writes the latest deliverable each
  round (present-before-confirm + tasks-gate traceability read) and finalizes the converged one at
  approval", that is an ac-update amendment — user's call. Naturally bundles with the recurring greenfield
  prose-clarification candidates (T-3.3 D1, T-2.8 D1, T-3.2-RD-D8 D1) the user may consolidate post-G3.
- status: open (informational; no action required to proceed)

## OQ-UX-1 — REPL is single-line input only (no multi-line / paste-block) — 2026-06-23
- raised_by: main-agent (user, during G3 interactive greenfield smoke test)
- component: C1 (CLI / ReplRunner)
- spec_refs: 04-apis § 1.1/1.4 (REPL), US-1 (greenfield requirements dialogue)
- suggested_amendment_kind: nfr-update / enhancement (M4 polish)
- finding: ReplRunner reads with BufferedReader.readLine() — each Enter submits one complete
  turn; a blank line is skipped (continue), not a terminator. There is NO multi-line input mode
  (no continuation char, no paste-block delimiter, no end-of-input marker). This is the
  M1/T-1.1 single-line REPL scope. It is mildly at odds with greenfield's DCR-2 multi-turn
  dialogue, where a developer naturally wants to paste a multi-paragraph requirements/answer
  block — currently each newline in a paste submits a separate turn.
- workaround (in use during G3 test): put the whole answer on one line, OR send detail across
  several refining turns (AC-2.4) then type `y` to finalize. Confirmed adequate to drive the
  greenfield flow.
- proposed fix (M4 polish): add a multi-line input affordance to ReplRunner — e.g. a triple-quote
  paste block (`"""` … `"""`) or a `.`-on-its-own-line terminator — so a pasted multi-line prompt
  is one turn. Greenfield-relevant but applies to the whole REPL.
- status: open (deferred to M4 polish; non-blocking — does not gate G3)

## OQ-design-3 — T-3.2-RD-D12-D13 design-change requested (greenfield mid-flow resume, D12) — 2026-06-23

- kind: architecture-update
- raised_by: spec-driven-task-builder
- request_id: DCR-3 (proposed; awaiting user decision)
- spec_refs_touched: ADR-0012, AC-7.1, AC-7.2, AC-7.3, AC-7.4, AC-2.3, 02-architecture.md § 1.2 (C3 greenfield driver, C15 session/lineage store), Main.ONE_SHOT_LINEAGE (M0 placeholder)
- problem_statement: |
    D12: a transient model-backend failure (or any non-approval interruption) mid-greenfield does NOT
    resume the in-flight greenfield session at the failed phase — the next REPL prompt restarts
    greenfield from the requirements phase. Root cause: GreenfieldDriver.run() is a pure in-memory
    phase state machine that always begins at GreenfieldPhase.initial(); greenfield phase-state (which
    phases were approved, the current phase, the approved-artifact content) is never persisted in a
    resumable form. The existing resume machinery (T-1.2: SessionStore, SessionReplay, SessionLineage,
    ResumeCommand) reconstructs the BROWNFIELD conversation transcript (events -> messages[]), per
    AC-7.2 and SessionReplay's contract — it is NOT a greenfield phase-state projection. There is no
    EventType for "phase approved"/"phase advanced"/"current phase", so there is nothing on disk from
    which to reconstruct the greenfield phase machine. No AC/INV/ADR pins how greenfield phase-state is
    persisted or resumed; ADR-0012 says greenfield "inherits persistence for free" but is silent on
    resuming an interrupted greenfield session. Implementing D12 without a spec decision would require
    inventing: (1) the contract for "resume at the failed phase" vs "retry-in-place on the next prompt";
    (2) how phase-state is reconstructed (re-derive approved phases from the on-disk approval-stamped
    artifacts? add greenfield phase events to the log? both?); (3) the real session/repo identity that
    scopes a resumable greenfield session — today every run shares the fixed Main.ONE_SHOT_LINEAGE M0
    placeholder (a known stand-in for AC-7.3 git-remote/abs-path repo-keying), so runs collide.
    (D13, the destructive sibling — silent overwrite of an approved artifact — was FIXED in code this
    round as a safety stopgap, derivable from AC-1.2/AC-1.5: GreenfieldArtifactStore.write() refuses to
    truncate an already-approval-stamped artifact, throwing the new ApprovedArtifactProtectedException.
    The D13 fix also independently mitigates the worst observed D12 symptom: a restart-from-scratch run
    can no longer silently destroy the prior run's approved requirements — it now fails loud instead.
    The D13 code is in the working tree, uncommitted, pending the resumed-task commit after this DCR.)
- options_considered:
  - id: A
    summary: |
      Per-greenfield-project resume by re-deriving phase-state from the on-disk approved artifacts.
      Introduce a greenfield-resume contract (a new resume AC + ADR-0012 amendment): a greenfield
      session is keyed to the target project (AC-7.3 repo-keying brought forward to replace
      ONE_SHOT_LINEAGE), and on a fresh `codingagent --mode greenfield` the driver reconstructs its
      phase-state from the target repo's design/ artifacts — a phase whose artifact bears the AC-1.5
      approval stamp is "approved"; the current phase is the first unstamped/absent one — and resumes
      there rather than restarting at requirements. A transient timeout mid-phase is retryable in
      place: because the failed phase's artifact is not yet stamped, the next prompt re-enters that
      same phase. Pairs with the D13 refuse-to-clobber guard already shipped (the stamp is the resume
      signal AND the clobber-protection signal — one durable on-disk fact serves both).
    pros: Reuses what is already on disk (the approval-stamped artifacts) as the resume state — minimal new persistence; the D13 stamp already shipped doubles as the resume marker; gives both retry-in-place (unstamped failed phase) and resume-a-prior-project (stamped earlier phases) from one mechanism; advances AC-7.3 repo-keying which is overdue.
    cons: Re-derives only phase boundaries from artifacts, not the in-phase multi-turn transcript (an interrupted mid-phase conversation loses its in-phase turns — acceptable since AC-1.1's in-phase carry is within one process run, but a design choice to confirm); requires bringing AC-7.3 repo-keying forward (replacing ONE_SHOT_LINEAGE), a non-trivial wiring change touching C15/Main.
  - id: B
    summary: |
      First-class greenfield phase events in the session log + replay into phase-state. Add
      GREENFIELD_PHASE_APPROVED / GREENFIELD_PHASE_ADVANCED (or a single phase-state) EventType, append
      them at each gate approval/advance, and extend the resume path (a greenfield analogue of
      SessionReplay) to reconstruct the phase machine from the log (AC-7.2-shaped, but for phase-state
      rather than messages[]). Greenfield resume then lists + selects a session like brownfield (AC-7.1)
      and continues at the recorded current phase.
    pros: Phase-state is explicitly persisted (not re-derived), so it is robust to artifact edits and carries richer state (e.g. in-phase progress); fits the existing event-log/AC-7.2 replay model and the C15 lineage store directly; unifies greenfield resume under the same list-and-replay UX as brownfield (AC-7.1/7.2/7.4).
    cons: More new surface — a new EventType (touches event.schema.json + CT-SCH-2 + the EventType taxonomy), a new replay projection, and gate/driver wiring to emit the events; larger blast radius than re-deriving from artifacts; still needs the same AC-7.3 repo-keying to scope sessions (ONE_SHOT_LINEAGE today).
- recommended_option: A
- scope_of_design_edit:
  - design/adr/0012-greenfield-workflow-formality.md (add a greenfield-resume clause: phase-state is resumable; an approval-stamped artifact marks an approved phase; resume at the first unstamped/absent phase; transient mid-phase failure is retryable-in-place; keyed to the target project)
  - design/00-requirements.md (a new greenfield-resume AC under US-1/US-2 or a greenfield-scoped clause on US-7; EARS form, traced to US-1/US-2/US-7 + ADR-0012)
  - design/02-architecture.md § 1.2 (C3 greenfield driver: phase-state reconstruction on session start; C15 / repo-keying note replacing the ONE_SHOT_LINEAGE M0 placeholder per AC-7.3)
  - design/07-tasks.md (a follow-on task row for greenfield mid-flow resume + AC-7.3 repo-keying-forward, M3 scope)
  - design/06-formal/contract-tests.md (a greenfield-resume CT: a fresh greenfield run over a target project with an approved requirements artifact resumes at the design phase and does not restart at requirements; plus the D13 no-clobber CT)
- chosen_option: A
- user_decision: approved
- user_approval:
    approved_at: 2026-06-23T00:00:00+00:00
    approver_note: |
      Approve Option A — resume greenfield by re-deriving phase-state from on-disk artifacts: a
      greenfield AC-1.5 approval-stamped artifact = that phase approved; resume at the first
      unstamped/absent phase. A fresh `--mode greenfield` against a project with approved phases
      resumes there rather than restarting; transient mid-phase failure is retryable in place (the
      failed phase is unstamped). Bring AC-7.3 real repo-keying forward to replace the
      ONE_SHOT_LINEAGE placeholder (the root cause of run collisions). Accept the tradeoff that an
      interrupted mid-phase conversation loses its in-phase turns (resume at the phase boundary and
      re-converse).
    revised_from_original: false
- designer_status: amended
- amendment_commit: 7a10d31
- resumed_task_commit: fbd33c4
- ripple_unresolved: |
    Designer reported ripple_unresolved: [] (none). The two pre-existing DCR-1/DCR-2 ripple items
    (the D8 AC-9.4 / ADR-0004 gate-decision-table write_artifact carve-out; the 07-tasks-progress.md
    historical narrative) were NOT touched by DCR-3 and are not re-litigated — neither is a new ripple
    DCR-3 introduces.
- amendment_summary: |
    AC-7.6 added (EARS Ev, under US-7; traced US-1/US-2/US-7 + ADR-0012): greenfield resume re-derives
    phase-state from on-disk approval-stamped artifacts; resume at first unstamped/absent phase; transient
    mid-phase failure retryable in place. AC-1.5 augmented (stamp = dual resume + clobber-protection
    marker). C3 phase-state reconstruction on session start; C15 brings real AC-7.3 repo-keying forward
    (git remote else normalized abs path, ADR-0005), replacing Main.ONE_SHOT_LINEAGE. New M3 task T-3.4
    (greenfield mid-flow resume + AC-7.3 repo-keying-forward, deps T-3.2). New CT-GF-1 (resume at design
    over stamped requirements, no restart) + CT-GF-2 (no-clobber of a stamped artifact), § 7 of
    contract-tests.md. ADR-0012 resume clause + Option B (first-class phase events) recorded as rejected.
    Review: design/reviews/2026-06-23-amendment-greenfield-resume-r1.md.
- budget: amendment #1 of 3 for T-3.2-RD-D12-D13; DCR-3, amendment #3 of 10 for milestone M3
- status: closed (DCR-3 amended at 7a10d31; resumed task resolved + pushed at fbd33c4 — D12 greenfield mid-flow resume + AC-7.3 repo-keying-forward on the D13 clobber fix; mvn clean verify green, 1072 tests, JaCoCo 0.80)

## OQ-design-4 — T-4.6 design-change requested (close orphaned NFR-BEDROCK-CALL-TIMEOUT) — 2026-06-24

- kind: schema-update
- raised_by: user via main-agent directive (closing an orphaned NFR found in audit)
- request_id: DCR-4
- spec_refs_touched: NFR-BEDROCK-CALL-TIMEOUT, AC-8.x (config-key set), ADR-0001 (Converse client timeouts), 02-architecture.md § 1.2 (C4 Model Client), 06-formal/resolved-config.schema.json, 06-formal/contract-tests.md (CT-SCH-13/14), 07-tasks.md (new M4 task)
- problem_statement: |
    NFR-BEDROCK-CALL-TIMEOUT (00-requirements.md line 447, "connect 10 s; overall response 300 s;
    configurable; covers streaming responses incl. extended thinking; timeout counts toward retry
    budget") is ORPHANED: it is referenced by zero ACs, has no config key in
    resolved-config.schema.json, no ADR wiring describing how the Converse client applies it, no
    contract test, and no task implementing it. The mirror NFR-CMD-TIMEOUT already has a config key
    (commandTimeoutSeconds, minimum 1, default 300) and is wired; the Bedrock call timeout never was.
    The Converse client (C4, ADR-0001) is built today with no apiCallTimeout / httpClient timeouts, so
    the SDK applies its own defaults rather than the NFR-pinned 10 s connect / 300 s response budget.
- options_considered:
  - id: a
    summary: |
      Full schema-update. Add two config keys (bedrockCallConnectTimeoutSeconds default 10 minimum 1;
      bedrockCallResponseTimeoutSeconds default 300 minimum 1) to resolved-config.schema.json keeping
      additionalProperties:false; fold NFR-BEDROCK-CALL-TIMEOUT into the config-key set / AC-8.x (EARS
      form + traceability preserved); record in ADR-0001 (+ 02-architecture C4 row) that the Converse
      client sets apiCallTimeout = response timeout and an Apache httpClientBuilder with
      socketTimeout = response timeout + connectionTimeout = connect timeout; append a new M4 task
      (T-4.6) deps T-0.3 wiring the timeouts with a CT-INV-13-style wiring test; extend CT-SCH-13/14
      coverage to the two new keys (timeout config key validates + default applied when absent).
    pros: Closes the orphan fully — the NFR becomes config-backed, AC-traceable, ADR-wired, CT-covered,
      and implemented; mirrors the established commandTimeoutSeconds pattern; the wiring becomes
      unit-assertable via the existing inspectable wiring(...) seam (no live Bedrock call in tests).
    cons: Touches five design artifacts in one amendment (larger blast radius than a doc-only fix).
  - id: b
    summary: |
      Doc-only: reference NFR-BEDROCK-CALL-TIMEOUT from an existing AC (e.g. AC-8.x) without adding a
      config key, schema property, ADR wiring, or task — leaving the value un-implemented but no longer
      table-orphaned.
    pros: Smallest change; single-file edit.
    cons: The NFR stays un-implemented (the live Converse client still has no timeout wiring), so the
      orphan is only cosmetically closed; no config key means it is not actually configurable as the NFR
      requires; no CT pins it. Rejected by the user.
- recommended_option: a
- chosen_option: a
- user_decision: approved
- user_approval:
    approved_at: 2026-06-24T00:00:00+00:00
    approver_note: |
      Approve Option (a) — full schema-update. Add bedrockCallConnectTimeoutSeconds (default 10, min 1)
      and bedrockCallResponseTimeoutSeconds (default 300, min 1) keeping additionalProperties:false;
      fold NFR-BEDROCK-CALL-TIMEOUT into the AC-8.x config-key set (EARS + traceability); record the
      Converse-client timeout wiring (apiCallTimeout = response timeout; Apache httpClientBuilder
      socketTimeout = response timeout + connectionTimeout = connect timeout) in ADR-0001 / 02-arch C4;
      append M4 task T-4.6 (deps T-0.3, CT-INV-13-style wiring test); extend CT-SCH-13/14 to the two new
      keys. This unblocks the G3 live smoke test; do NOT mark G3 passed.
    revised_from_original: false
- scope_of_design_edit:
  - design/06-formal/resolved-config.schema.json (add bedrockCallConnectTimeoutSeconds + bedrockCallResponseTimeoutSeconds; keep additionalProperties:false)
  - design/00-requirements.md (fold NFR-BEDROCK-CALL-TIMEOUT into the config-key set / AC-8.x; EARS form + traceability preserved; connect 10s / response 300s / configurable)
  - design/adr/0001-engine-sdk-converse-owned-loop.md (and/or design/02-architecture.md § 1.2 C4 row) (record Converse client apiCallTimeout = response timeout + Apache httpClientBuilder socketTimeout = response timeout / connectionTimeout = connect timeout)
  - design/07-tasks.md (append M4 task T-4.6: wire NFR-BEDROCK-CALL-TIMEOUT into the Bedrock client — apiCall/socket 300s + connect 10s, configurable, CT-INV-13-style wiring test; deps T-0.3; cite NFR-BEDROCK-CALL-TIMEOUT + CT-SCH-13/14)
  - design/06-formal/contract-tests.md (extend CT-SCH-13/14 coverage to the two new keys: timeout config key validates + default applied when absent)
- designer_status: amended
- amendment_commit: 589e751
- amendment_summary: |
    Closed orphaned NFR-BEDROCK-CALL-TIMEOUT end-to-end. resolved-config.schema.json: added
    bedrockCallConnectTimeoutSeconds (int, min 1, default 10) + bedrockCallResponseTimeoutSeconds
    (int, min 1, default 300); additionalProperties:false preserved. 00-requirements.md: new
    AC-8.10 (U, configurable budget) + AC-8.11 (Ev, defaults when absent) under US-8; NFR row +
    NFR→AC coverage row cite the keys/ACs/ADR-0001; orphan-closure note. ADR-0001: "Call timeouts"
    Decision bullet (apiCallTimeout = response; Apache httpClientBuilder socketTimeout = response /
    connectionTimeout = connect); spec_refs += NFR-BEDROCK-CALL-TIMEOUT. 02-architecture.md: C4 row
    timeout wiring + new § 2 call-timeout note. 07-tasks.md: new M4 task T-4.6 (deps T-0.3,
    CT-INV-13-style wiring test); task→US mapping US-8 += T-4.6. contract-tests.md: CT-SCH-16
    (timeout keys validate) + CT-SCH-17 (resolver applies defaults when absent). NFR now referenced
    by ≥ 1 AC and ≥ 1 CT. ripple_unresolved: []. Review:
    design/reviews/2026-06-24-amendment-bedrock-call-timeout-r1.md. SHA-backfill commit 0b190e2
    (design-progress.md, mirroring the DCR-3 backfill precedent).
- resumed_task_commit: df22677
- budget: amendment #1 of 3 for T-4.6; DCR-4, amendment #4 of 10 for the milestone
- status: closed (DCR-4 amended at 589e751 [backfill 0b190e2]; T-4.6 resolved + pushed at df22677 — orphaned NFR-BEDROCK-CALL-TIMEOUT closed end-to-end: 2 config keys + Converse-client apiCall/socket/connect wiring; mvn clean verify green, 1086 tests, JaCoCo 91.12%. Unblocks the future G3 live smoke test; G3 gate left OPEN.)

## OQ-design-5 — T-3.5 design-change requested (align greenfield playbook prompts to the gate's traceability vocabulary) — 2026-06-24

- kind: ac-update + adr-clarification
- raised_by: user via main-agent directive (closing a G3-blocking greenfield traceability-vocabulary bug; independently verified by the prior coordinator — compiled + ran the production regexes)
- request_id: DCR-5
- spec_refs_touched: AC-2.2, AC-2.5, ADR-0012 (greenfield workflow formality — traceability chain), GreenfieldPlaybook (C3 per-phase prompt), TaskTraceability (the strict gate), US-2
- problem_statement: |
    A greenfield project cannot satisfy its OWN AC-2.5 traceability gate. TaskTraceability (the
    ArtifactApprovalGate's tasks-phase check, ADR-0012) is STRICT by design:
    TASK_LINE (TaskTraceability.java line 44-45) requires a stable id of the form T-<n> / T-<n>.<m>
    (the hyphen is MANDATORY), and REQUIREMENT_REF (line 51-52) accepts only the requirement-symbol
    vocabulary US- / AC- / NFR- / RD- / INV-. But GreenfieldPlaybook (C3) never tells the model that
    required vocabulary: the TASKS phase block (line 152-159) says "each with a stable identifier and
    each tracing to at least one requirement" without naming the T-<n> id form or the AC-/US-/NFR-
    symbol forms, and the REQUIREMENTS phase block (line 138-144) says "personas, user stories,
    acceptance criteria, non-functional requirements" without pinning the gate-recognizable AC-<n>.<m>
    / US-<n> / NFR-<NAME> symbol shapes. A live greenfield model accordingly emits T1/T2/T10 ids
    (no hyphen → not recognized as tasks) citing R1-R6 refs (not in the gate vocabulary → not a valid
    trace), so the strict gate correctly refuses 0-tasks / untraceable, and greenfield never reaches
    implement. Existing TaskTraceabilityTest (10 tests) only exercises gate-conformant forms, so the
    suite is green while live model output fails — a defect class the existing tests cannot catch by
    construction. Confirmed NEW (not previously in open-questions.md).
- options_considered:
  - id: a
    summary: |
      Fix the prompt, keep the gate strict. NO change to TaskTraceability's regexes. Constrain
      GreenfieldPlaybook so (1) the REQUIREMENTS phase authors acceptance criteria using the gate's
      requirement-symbol vocabulary (AC-<n>.<m> numbered ACs, US-<n> user stories, NFR-<NAME>), and
      (2) the TASKS phase emits task ids of the form T-<n> / T-<n>.<m> (hyphen mandatory) with each
      task line citing >= 1 requirement symbol authored in the requirements phase. A greenfield
      project's own model-authored requirement symbols become the traceability catalog its tasks
      trace to. Fold the contract into AC-2.2 + AC-2.5 (the prompt EMITS the gate vocabulary) and
      record it in ADR-0012 (the gate stays strict; the prompt carries the burden of conformance),
      preserving the full-rigor traceability chain ADR-0012 deliberately chose over the lightweight
      scaffold.
    pros: Keeps the strict gate (the formal traceability guarantee ADR-0012 chose) intact; makes a
      greenfield project self-consistent (its model-authored symbols are the catalog its tasks trace
      to); the fix is a production-prompt change + regression tests that pin the exact live-failing
      cases so it can never silently regress; no schema / contract-test / data-model blast radius.
    cons: ac-update + adr-clarification touches three design artifacts (00-requirements, ADR-0012,
      07-tasks); the prompt now carries the burden of teaching the model the gate's vocabulary.
  - id: b
    summary: |
      Relax the gate. Broaden TaskTraceability's TASK_LINE to also accept hyphen-less ids (T1/T10)
      and broaden REQUIREMENT_REF to accept bare R<n> refs, so the live model's natural output passes.
    pros: Smallest code change (two regexes); no prompt change.
    cons: Discards the strict formal traceability guarantee ADR-0012 deliberately chose over the
      lightweight scaffold — T1/R5 are ambiguous and collide with unrelated tokens; the gate would
      no longer pin the US→AC→NFR/ADR→task chain; a greenfield project would NOT match the
      methodology that built codingAgent itself (the reflexive-consistency value in ADR-0012).
      Rejected by the user.
- recommended_option: a
- chosen_option: a
- user_decision: approved
- user_approval:
    approved_at: 2026-06-24T00:00:00+00:00
    approver_note: |
      Approve Option (a) — FIX THE PROMPT, KEEP THE GATE STRICT. No change to TaskTraceability's
      regexes. Constrain GreenfieldPlaybook so the REQUIREMENTS phase authors AC-<n>.<m> / US-<n> /
      NFR-<NAME> symbols and the TASKS phase emits T-<n> / T-<n>.<m> ids each citing >= 1 such symbol.
      Fold into AC-2.2 + AC-2.5; record the prompt-emits-the-vocabulary contract in ADR-0012; append
      M3 task T-3.5 (deps T-3.2). This unblocks the G3 live smoke test; do NOT mark G3 passed.
    revised_from_original: false
- scope_of_design_edit:
  - design/00-requirements.md (add a clause to AC-2.2 + AC-2.5: a greenfield project's traceability vocabulary is the model-authored requirement symbols AC-<n>.<m>/US-<n>/NFR-<NAME> authored in the requirements phase, and tasks carry T-<n>/T-<n>.<m> stable ids citing >= 1 such symbol; preserve EARS form + existing traceability)
  - design/adr/0012-greenfield-workflow-formality.md (record the contract: the GreenfieldPlaybook prompt EMITS the gate's vocabulary on both phases — requirements authors gate-recognizable symbols, tasks use T-<n> ids citing them; the gate stays strict, no TaskTraceability change; preserves the full-rigor traceability chain)
  - design/07-tasks.md (append M3 task T-3.5, deps T-3.2: align greenfield playbook prompts to the gate vocabulary + regression tests; cite AC-2.5/AC-2.2 + ADR-0012 + GreenfieldPlaybook prompt; add the task→US mapping under US-2)
- designer_status: amended
- amendment_commit: bfb2ce8
- amendment_summary: |
    Folded the prompt-emits-the-gate-vocabulary contract into AC-2.2 + AC-2.5 (00-requirements.md,
    US-2; AC-2.2 widened to Refs US-2/ADR-0012) and recorded it in ADR-0012 (Decision bullet "the
    playbook prompt emits the gate's vocabulary; the gate stays strict" + rejected Option b in
    Alternatives + Notes DCR-5 + front-matter amended/review). Appended M3 task T-3.5 (deps T-3.2:
    align greenfield playbook prompts to the gate vocabulary + regression tests) to 07-tasks.md +
    the task→US greenfield-row mapping + front-matter amended_by += DCR-5. Gate stays STRICT — NO
    TaskTraceability regex change (Option b rejected, recorded explicitly). design-progress.md
    front-matter flip-and-return + § 1/§ 3/§ 5 updates. Review:
    design/reviews/2026-06-24-amendment-greenfield-playbook-traceability-vocabulary-r1.md.
    ripple_unresolved: [02-architecture.md C3 row does not yet note the DCR-5 prompt-emits-vocabulary
    contract — OUT of the approved scope_of_design_edit; NOT required for T-3.5 to land (T-3.5 cites
    ADR-0012 + AC-2.2/AC-2.5 directly); surfaced as a non-blocking future doc-fold-in candidate].
- ripple_unresolved: |
    1 item, NON-BLOCKING, within the approved scope boundary: design/02-architecture.md C3 (Workflow
    drivers) row records the DCR-1/2/3 greenfield contracts but not the DCR-5 prompt-emits-the-gate-
    vocabulary contract. It was deliberately out of the approved scope_of_design_edit (only
    00-requirements / adr-0012 / 07-tasks). The architecture's traceability semantics live in
    ADR-0012, which IS updated; T-3.5 cites ADR-0012 + AC-2.2/AC-2.5 directly, so the missing C3 note
    does not block T-3.5. Surfaced to the user as a candidate for a future tiny doc-clarification /
    adr-clarification fold-in — user's call. Not a new ambiguity, not scope creep.
- resumed_task_commit: 4748c0a
- budget: amendment #1 of 3 for T-3.5; DCR-5, amendment #5 of 10 for milestone M3 (warn threshold 8, not hit)
- status: closed (DCR-5 amended at bfb2ce8 [OQ-lifecycle commit 36390dd]; T-3.5 resolved + pushed at 4748c0a under single-agent topology, task-builder round 1 — greenfield playbook prompt now EMITS the strict gate's vocabulary [REQUIREMENTS authors AC-<n>.<m>/US-<n>/NFR-<NAME>; TASKS emits T-<n>/T-<n>.<m> ids citing them]; TaskTraceability regexes UNCHANGED; mvn clean verify green, 1093 tests, JaCoCo 91.12%; shaded jar rebuilt. ONE non-blocking ripple_unresolved (02-architecture C3 row, out of approved scope — future doc-fold-in candidate). Unblocks the future G3 live greenfield smoke test; G3 gate left OPEN.)

## OQ-design-6 — T-3.6/T-3.7 design-change requested (fix two G3-blocking greenfield bugs: write_artifact containment + TaskTraceability real-breakdown miscounting) — 2026-06-24

- kind: ac-update + adr-clarification
- raised_by: user via main-agent directive (closing two G3-blocking greenfield bugs found by the prior coordinator; both root causes independently re-verified by this coordinator against the live source)
- request_id: DCR-6
- spec_refs_touched: AC-1.4, AC-2.2, AC-2.5, RD-7, ADR-0012 (greenfield workflow formality), GreenfieldArtifactStore/WriteArtifactTool (C9 containment), TaskTraceability (the strict gate), GreenfieldPlaybook (C3 per-phase prompt), CT (new CT-GF-3), US-2
- problem_statement: |
    Two distinct G3-blocking greenfield defects, both confirmed by re-running/reading the live source:
    (1) CONTAINMENT HOLE (T-3.6). GreenfieldArtifactStore.resolveArtifact (lines 93-102) confines a
    write only by `resolved.startsWith(artifactRoot)` where artifactRoot = workspaceRoot/design — there
    is NO allowlist of the known design-doc artifacts. So a path like design/impl/pom.xml or
    design/impl/src/main/java/.../Calculator.java resolves UNDER <repo>/design and PASSES the check,
    and WriteArtifactTool.handle (line 105) passes the raw model-supplied path straight to store.write().
    Both class Javadocs already promise the tool "cannot write source files" (WriteArtifactTool lines
    19-25/68-70; GreenfieldArtifactStore lines 24-27) and AC-1.4 forbids any Class-X op against source
    files in the pre-approval dialogue — so this is a code-vs-Javadoc/AC-1.4 conformance gap, the
    pre-approval source-write hole.
    (2) GATE REAL-BREAKDOWN MISCOUNTING (T-3.7). TaskTraceability is correctly STRICT on which lines
    count as tasks (T-<n> hyphen mandatory) and which refs count as traces (US-/AC-/NFR-/RD-/INV-), but
    it MISCOUNTS the shapes a real Sonnet-style breakdown contains: (i) a repeated task id is double-
    counted in untracedTasks (line 98, no dedup); (ii) an arrow/sequencing-diagram line `T-1 -> T-2`
    is read as a single task (TASK_LINE captures only the first id); (iii) a range heading
    `T-3 through T-8` recognizes only T-3, silently dropping T-4..T-8; (iv) a bold-wrapped id in a
    table row `| **T-1** |` is not recognized at all (the `**` defeats the list/heading/table prefix).
    These are recognition-COVERAGE misses, not strictness relaxations.
- options_considered:
  - id: "1"
    summary: |
      A single mini-DCR (DCR-6) scoped to design/ ONLY that creates BOTH new M3 task rows (T-3.6
      containment, T-3.7 gate-hardening + prompt) and qualifies the AC-2.2/AC-2.5 gate wording so the
      strict-recognition guarantee is preserved while a miscounting-only hardening is permitted; then
      drive T-3.6 (containment) FIRST and T-3.7 (gate) SECOND. The gate hardening changes recognition
      COVERAGE (dedup, arrow-diagram skip, range-heading expansion, bold-table-cell ids) not STRICTNESS
      — the same-line-ref rule is NOT loosened into a block scan (the rejected DCR-5 Option b); single-
      line task rows are guaranteed by the PROMPT, and the gate hardening only stops the parser
      miscounting the shapes a real breakdown contains.
    pros: One amendment for both interlocking fixes; preserves the strict formal traceability guarantee
      ADR-0012 chose (no strictness relaxed, block-scan Option b stays rejected); both fixes land as
      source changes (T-3.6 pure containment, T-3.7 prompt + gate-coverage hardening) with regression
      tests pinning the exact failing shapes so they cannot silently regress; design/-only blast radius.
    cons: Touches four design artifacts (00-requirements, ADR-0012, 07-tasks, contract-tests) in one
      amendment; the AC wording must be qualified carefully so "unchanged" becomes "unchanged in
      strictness" without inviting a loose block scan.
  - id: "2"
    summary: |
      Relax the gate into a loose block scan (treat a block of lines as one task, scan for any ref
      anywhere in the block) so real breakdown shapes pass without per-line discipline.
    pros: Fewer recognition rules to add.
    cons: This IS the DCR-5 Option b the user already rejected — it discards the strict same-line-ref
      guarantee ADR-0012 chose, lets an untraced task pass because a sibling line carries a ref, and
      breaks the reflexive-consistency value. Rejected (stands rejected from DCR-5).
- recommended_option: "1"
- chosen_option: "1"
- user_decision: approved
- user_approval:
    approved_at: 2026-06-24T00:00:00+00:00
    approver_note: |
      APPROVED — proceed end-to-end with Option 1 + DCR-6 to fix the two G3-blocking greenfield bugs.
      A single mini-DCR (ac-update + adr-clarification) scoped to design/ only, creating BOTH new task
      rows (T-3.6 / T-3.7 under M3) and the gate-wording qualification; then drive T-3.6 (containment)
      FIRST, T-3.7 (gate + prompt) SECOND. The TaskTraceability RECOGNITION is unchanged in STRICTNESS;
      a miscounting-only hardening (dedup repeated ids, skip arrow/sequencing-diagram lines, expand
      range headings, recognize bold-wrapped ids in table rows) is permitted because it changes
      recognition COVERAGE, not strictness — the same-line-ref strictness is NOT loosened into a block
      scan (DCR-5 Option b stays rejected). This unblocks the future G3 live smoke test; do NOT mark G3
      passed. Bug 3 (live-generated CalculatorTest.java referencing CalcException unqualified) is
      DEFERRED — not fixed here; the genuine IMPLEMENT-phase verify loop is expected to catch it.
    revised_from_original: false
- scope_of_design_edit:
  - design/00-requirements.md (qualify the AC-2.2 + AC-2.5 wording that currently says the gate's TaskTraceability "is unchanged" / "regexes are unchanged" → "unchanged in STRICTNESS — which lines count as tasks / which refs count as traces is not relaxed; a miscounting-only hardening [dedup repeated ids, skip arrow/sequencing-diagram lines, expand range headings, recognize bold-wrapped ids in table rows] is permitted as it changes recognition COVERAGE not strictness; the same-line-ref strictness is NOT loosened into a block scan"; preserve EARS form + traceability)
  - design/adr/0012-greenfield-workflow-formality.md (DCR-6 note: DCR-5's "gate unchanged / no regex relaxed" is QUALIFIED — miscounting-only hardening now permitted while the strict same-line-ref guarantee + the rejection of a loose block-scan (Option b) both stand; cross-reference DCR-5)
  - design/07-tasks.md (add BOTH M3 task rows: T-3.6 [deps T-3.2] tighten write_artifact containment to the known design-doc artifacts — reject source paths under design/ e.g. design/impl/**, closing the AC-1.4 pre-approval source-write hole; cite AC-1.4, RD-7, ADR-0012, GreenfieldArtifactStore/WriteArtifactTool. T-3.7 [deps T-3.5] harden TaskTraceability against real-breakdown miscounting + extend the greenfield TASKS prompt to force single-line task rows and forbid range headings / multi-line **Refs:** blocks / arrow-diagram-as-task-list; cite AC-2.2, AC-2.5, ADR-0012/DCR-6, GreenfieldPlaybook, TaskTraceability. Add both task->US mappings under US-2)
  - design/06-formal/contract-tests.md (add a contract test [next free id, likely CT-GF-3] covering the four gate miscounting shapes [multi-line refs / range heading / arrow diagram / bold table cell] + the containment cases [design/impl/** rejected; the three real artifacts allowed]; split into two CTs if cleaner)
- designer_status: amended
- amendment_commit: e5e9b34
- amendment_summary: |
    Design-only amendment (no src/ edited). 00-requirements.md: AC-2.2 + AC-2.5 "gate's TaskTraceability
    is unchanged" / "regexes are unchanged" qualified to "recognition is unchanged in STRICTNESS — which
    lines count as tasks / which refs count as traces is not relaxed; a miscounting-only hardening (dedup
    repeated ids, skip arrow/sequencing-diagram lines, expand range headings so each task is individually
    recognized + correctly flagged if untraced, recognize bold-wrapped ids in table rows) is permitted as
    it changes recognition COVERAGE, not strictness — the same-line-ref strictness is NOT loosened into a
    block scan (DCR-5 Option b stands rejected)"; EARS U-form + (US-2, ADR-0012) Refs preserved; cites
    (ADR-0012, DCR-6). ADR-0012: Decision bullet qualified (gate unchanged in STRICTNESS + coverage-
    hardening sub-bullet) + "Amended 2026-06-24 (DCR-6)" Notes entry cross-referencing DCR-5; the
    "Relax the traceability gate (DCR-5 Option b)" Alternatives row LEFT rejected; front-matter
    amended/Status/review updated. 07-tasks.md: new M3 rows T-3.6 (deps T-3.2, C9/C3, AC-1.4/RD-7/
    ADR-0012, Verify CT-GF-4 — write_artifact containment allowlist) + T-3.7 (deps T-3.5, C3, AC-2.2/
    AC-2.5/ADR-0012-DCR-6, Verify CT-GF-3 — gate miscounting hardening + TASKS prompt), § 6 US-1/2/3
    mapping += T-3.6/T-3.7, front-matter amended_by += DCR-6. contract-tests.md: § 7 CT-GF-3 (four gate
    miscounting shapes + full Sonnet-style breakdown passes; traces AC-2.2/AC-2.5/ADR-0012) + CT-GF-4
    (containment: design/impl/pom.xml + design/impl/src/** REJECTED, three real artifacts ALLOWED;
    traces AC-1.4/RD-7/ADR-0012) — designer split the combined CT into two for clarity (permitted by the
    directive); CT-GF-1/2 intact; § 6 traceability summary + front-matter amended_by updated.
    design-progress.md flip-and-return + § 1/§ 3/§ 5. Review:
    design/reviews/2026-06-24-amendment-greenfield-containment-and-gate-miscounting-r1.md.
    SHA-backfill commit 4d1879c (design-progress § 5 Landed line + review approved_in), mirroring the
    DCR-3/DCR-4 backfill precedent. NO TaskTraceability strictness relaxed; gate same-line-ref guarantee
    stands; block-scan (Option b) stays rejected. No milestone gate touched; G3 stays OPEN.
- ripple_unresolved: |
    1 item, NON-BLOCKING, OUTSIDE the approved scope_of_design_edit (same precedent as the DCR-5 C3
    ripple): design/02-architecture.md C3 (Workflow drivers) / C7 (Tool Registry) / C9 (File tools) rows
    record the DCR-1/2/3 greenfield contracts but do not yet note the DCR-6 contracts — C3/C9 the
    write_artifact containment allowlist (only the three known design-doc artifacts; source paths under
    design/ rejected) and the gate recognition-COVERAGE hardening, C7 the same containment note. Semantic
    addition (judgment), not a mechanical cross-reference; deliberately OUT of the approved four-file
    scope (00-requirements / adr-0012 / 07-tasks / contract-tests). The architecture's traceability
    semantics live in ADR-0012, which IS updated; T-3.6/T-3.7 cite ADR-0012 + AC-1.4/AC-2.2/AC-2.5
    directly, so the missing C3/C7/C9 notes do NOT block either task. Surfaced to the user as a candidate
    for a future tiny doc-fold-in (could bundle with the still-open DCR-5 C3 ripple) — user's call.
- budget: amendment #1 of 3 for T-3.6 (creates both task rows); DCR-6, amendment #6 of 10 for milestone M3 (warn threshold 8, not hit)
- resumed_task_commit: T-3.6 → c6e3a1c, T-3.7 → ae7e624
- status: closed (DCR-6 amended at e5e9b34 [SHA backfill 4d1879c; OQ-lifecycle commits b534b92 + e0ff953]; both resumed tasks landed under single-agent topology, task-builder round 1 each: T-3.6 [write_artifact containment — strict three-artifact allowlist closing the AC-1.4 design/impl/** source-write hole; CT-GF-4] pushed at c6e3a1c, and T-3.7 [TaskTraceability recognition-COVERAGE hardening — dedup / arrow-skip / range-expand / bold-table-cell, strictness unchanged, same-line-ref holds, block-scan stays rejected; + greenfield TASKS prompt forces single-line rows and forbids range/Refs-block/arrow shapes; CT-GF-3] pushed at ae7e624. mvn clean verify green at each commit (T-3.6: 1100 tests; T-3.7: 1118 tests; JaCoCo 91.13% → 91.16%, gate 0.80). ONE non-blocking ripple_unresolved (02-architecture C3/C7/C9 rows do not yet note the DCR-6 containment + gate-coverage contracts — OUT of the approved scope; same precedent as the still-open DCR-5 C3 ripple; surfaced to the user as a future doc-fold-in candidate, could bundle the two). Shaded codingagent.jar rebuilt carrying both fixes. Bug 3 (live-generated CalculatorTest.java referencing CalcException unqualified — 8 compile errors) DEFERRED per the directive — no code written; the genuine IMPLEMENT-phase verify loop is expected to catch it on a G3 re-run. This only UNBLOCKS the future G3 live greenfield smoke test; G3 milestone gate left OPEN/untouched, NOT marked passed.)

## OQ-design-7 — T-3.8/T-3.9/T-3.10 design-change requested (greenfield IMPLEMENT-phase verification model: verify-at-end / testable-only; no-test-command = complete-with-warning) — 2026-06-25

- kind: ac-update + adr-clarification
- raised_by: user via main-agent directive (closing three G3-blocking greenfield IMPLEMENT-phase defects D1/D2/D3 found + re-verified by the prior coordinator against the live source)
- request_id: DCR-7
- spec_refs_touched: AC-3.2, AC-3.3, AC-3.6 (new), AC-7.6, AC-20.5, AC-20.6 (text unchanged — re-citation only), NFR-VERIFY-MAX-ITERATIONS, ADR-0012 (greenfield workflow formality — implement clause), GreenfieldImplementLoop / GreenfieldDriver / ReplRunner / VerifyLoop (C3/C2), TaskTraceability.tasksInOrder, US-1/2/3, US-7
- problem_statement: |
    Three distinct G3-blocking greenfield IMPLEMENT-phase defects, all re-verified against the live source:
    (D1) NO-TEST-COMMAND LIVELOCK. GreenfieldImplementLoop.run() takes a NO_TEST_COMMAND early-return
    (:184-188) that asLoopTurn wraps as "completed" (:209-211); GreenfieldDriver.runImplementPhase
    (:312-323) and ReplRunner keep-alive (:189-196 / :228-232 / :152-155) then re-prompt into a fresh
    implement attempt — a livelock rather than a terminal outcome. The three sites mis-cite AC-20.6 at
    VerifyLoop.java:129, GreenfieldImplementLoop.java:186 & :281 (AC-20.6 @ 00-requirements.md:398 is
    actually "prefer named commands over ad-hoc strings", NOT the no-test-command behavior).
    (D2) NO IMPLEMENT PROGRESS ON RESUME. GreenfieldDriver.java:211 reconstructs phase-state every turn;
    GreenfieldPhaseState.reconstruct() (:70-90) lands at IMPLEMENT on the 3 "Approved:" stamps;
    TaskTraceability.tasksInOrder() (:195-202) does NOT skip completed tasks; markComplete fires only on
    VERIFIED (:173-177 / :255-260 / :373-386) and is write-only (nothing reads completion lines back), so
    a greenfield re-entry restarts at T-1 rather than resuming at the first incomplete task.
    (D3) PER-TASK VERIFY vs SCAFFOLD-FIRST. The loop body verifies per-task (:170-190) with an EXHAUSTED
    hard-stop (:178-183) bounded by NFR-VERIFY-MAX-ITERATIONS=5 (VerifyLoop.verify() :135 / :160-162);
    a scaffold-first breakdown (T-1 scaffold, T-2 pom) hard-stops at T-1 because the not-yet-buildable
    scaffold cannot pass a per-task verify.
- options_considered:
  - id: A
    summary: |
      VERIFY AT END / TESTABLE-ONLY. The greenfield implement phase implements every task in breakdown
      order, marks each complete AS IMPLEMENTED (durable on-disk marker, read back on resume to skip
      completed tasks), and verifies ONCE AT END OF PHASE (testable-only — tasks not independently
      testable are implemented without per-task verification). Failing end verify retries bounded by
      NFR-VERIFY-MAX-ITERATIONS then stop-and-surface (AC-3.4/AC-20.5). No configured test command →
      end verify skipped with ONE warning, all tasks implemented + marked complete, phase terminates
      deterministically (no re-prompt loop) — COMPLETE-WITH-WARNING (terminal success, exit 0),
      consistent with the brownfield no-verify precedent. Intra-IMPLEMENT resume skips completed tasks
      and resumes at the first incomplete one (AC-7.6 extended). New AC-3.6 carries the correct
      no-test-command behavior the three sites currently mis-cite as AC-20.6.
    pros: Fixes all three defects with one coherent model; matches how a scaffold-first breakdown actually
      builds (scaffold → pom → testable code, verified once at the end); durable per-task completion makes
      resume real; terminal no-test outcome removes the livelock; corrects the AC-20.6 mis-citation.
    cons: ac-update + adr-clarification touches several design artifacts (00-requirements, ADR-0012,
      contract-tests, 07-tasks); end-of-phase verify is coarser than per-task (a failure points at the
      phase, not the single task) — accepted for a flat greenfield task list with no milestone substructure.
  - id: B
    summary: |
      Keep per-task verify; make the no-test-command path a hard-stop and add resume-skip separately.
    pros: Smaller per-fix changes.
    cons: Does NOT fix D3 (scaffold-first still hard-stops at T-1 because the scaffold cannot pass a
      per-task verify); a hard-stop on no-test-command is inconsistent with the brownfield no-verify
      precedent (complete-with-warning) and would fail a valid greenfield run that simply has no test
      command configured. Rejected.
- recommended_option: A
- chosen_option: A
- user_decision: approved
- user_approval:
    approved_at: 2026-06-25T00:00:00+00:00
    approver_note: |
      APPROVED end-to-end — proceed with Option A + DCR-7. Verification model: VERIFY AT END /
      TESTABLE-ONLY. No-test-command: COMPLETE-WITH-WARNING (terminal success, exit 0; all tasks
      implemented + marked complete; end verify skipped with ONE warning; phase terminates
      deterministically — NO re-loop; NOT a hard-stop; consistent with the brownfield no-verify
      precedent). Verify boundary: END-OF-PHASE (single configured build/test run after the last task;
      the greenfield implement phase is a flat task list with no milestone substructure). New ACs/CTs/
      tasks use the next free ids (designer assigns; expected shape T-3.8/T-3.9/T-3.10, AC-3.6, CT-GF-5).
      Then implement all three tasks to completion: T-3.8 (loop rework — implement every task, mark each
      complete as implemented, drop per-task verify + per-task EXHAUSTED hard-stop + per-task
      NO_TEST_COMMAND early return; resolves D3); T-3.9 (end-of-phase / testable-only verify + no-test
      TERMINAL behavior; fix AC-20.6 mis-citation → AC-3.6 at VerifyLoop:129, GreenfieldImplementLoop:186
      & :281; resolves D1); T-3.10 (intra-IMPLEMENT resume skips completed tasks; resolves D2). Brownfield
      no-test sites OUT of scope. This unblocks the future G3 live smoke test; do NOT mark G3 passed.
      Bug 3 (live-generated CalculatorTest referencing CalcException unqualified) now expected to be
      caught by the end-of-phase verify rather than separately fixed.
    revised_from_original: false
- scope_of_design_edit:
  - design/00-requirements.md (AC-3.2 → verify ONCE AT END OF PHASE, not per task; tasks not independently testable implemented without per-task verification; keep EARS + refs. AC-3.3 → each task MARKED COMPLETE AS IMPLEMENTED, completion read back on resume to skip completed tasks, end-of-phase verification gates the PHASE not each task; keep EARS + US-3. NEW AC-3.6 [next free id under US-3]: no configured test command → skip end verify with a single warning, having implemented + marked complete every task, terminate deterministically [no re-prompt loop]; this is the correct AC the three sites mis-cite as AC-20.6; trace US-3 + NFR-VERIFY context. AC-7.6 → extend to cover intra-IMPLEMENT resume: re-entry skips completed tasks + resumes at first incomplete, terminating rather than restarting at T-1; keep the pre-approval phase-boundary tradeoff wording. AC-20.6 TEXT UNCHANGED — DCR only records the code re-cites the no-test-command behavior to the new AC-3.6 not AC-20.6.)
  - design/adr/0012-greenfield-workflow-formality.md (line 48 implement clause rewrite: tasks implemented one at a time in breakdown order, marked complete ON IMPLEMENTATION; verify runs ONCE AT END OF PHASE [testable-only]; failing end verify bounded by NFR-VERIFY-MAX-ITERATIONS then stop-and-surface [AC-3.4]; no test command → end verify skipped with a warning, phase terminates [AC-3.6, replacing the AC-20.6 mis-citation]; intra-IMPLEMENT resume skips completed tasks [AC-7.6]. Add DCR-7 Notes entry + front-matter amended:.)
  - design/06-formal/contract-tests.md (§7 add CT-GF-5 [designer may split CT-GF-5..8]: (i) no-test-command terminates cleanly — all tasks implemented + marked complete, end verify skipped with warning, terminal outcome [no re-loop]; (ii) resume skips completed tasks — re-entry over a partially-completed breakdown resumes at first incomplete + terminates; (iii) end-verification failure surfaces — failing end verify bounded by NFR-VERIFY-MAX-ITERATIONS then stop-and-surface [AC-3.4]; (iv) scaffold-first breakdown [T-1 scaffold, T-2 pom] implements all tasks then verifies at end, no hard-stop at T-1. Trace AC-3.2/3.3/3.6/7.6 + ADR-0012; update §6 summary.)
  - design/07-tasks.md (append the three M3 tasks T-3.8/T-3.9/T-3.10 [designer assigns actual next free ids] + task→US mapping US-3.)
- designer_status: amended
- amendment_commit: 4667724
- amendment_summary: |
    Design-only amendment (no src/ edited). 00-requirements.md: AC-3.2 rewritten (greenfield IMPLEMENT
    phase verifies ONCE AT END OF PHASE, testable-only — tasks not independently testable implemented
    without per-task verify); AC-3.3 rewritten (each task MARKED COMPLETE AS IMPLEMENTED — durable marker;
    completion read back on resume to skip completed tasks; end-of-phase verify gates the PHASE not each
    task); AC-3.4 scoped to end-of-phase verify; NEW AC-3.6 (EARS Un, US-3 — no configured test command →
    skip end verify with ONE warning, all tasks implemented + marked complete, terminate deterministically
    [no re-prompt loop]; the correct AC the three code sites mis-cite as AC-20.6); AC-7.6 extended (intra-
    IMPLEMENT resume skips completed tasks + resumes at first incomplete, terminating not restarting at T-1;
    pre-approval phase-boundary tradeoff wording kept; Refs += US-3); 1b symbolic-NFR + 1c NFR→AC coverage
    rows for NFR-VERIFY-MAX-ITERATIONS += AC-3.6; front-matter amended_by += DCR-7 / review repointed.
    **AC-20.6 TEXT UNCHANGED** (the DCR only records the re-citation). ADR-0012: Decision "Implementation"
    bullet rewritten to "Implementation — verify at end / testable-only (DCR-7)" + new Notes entry "Amended
    2026-06-25 (DCR-7, T-3.8/T-3.9/T-3.10)" + front-matter amended[]/Status/spec_refs (+AC-3.4/AC-3.6/AC-20.5/
    NFR-VERIFY-MAX-ITERATIONS)/review. contract-tests.md § 7: designer SPLIT the single requested CT-GF-5 into
    four (per the directive's split permission) — CT-GF-5 (no-test terminal), CT-GF-6 (resume skips completed),
    CT-GF-7 (end-verify failure surfaces), CT-GF-8 (scaffold-first, no hard-stop at T-1); § 7 heading/intro/
    closing-note + § 6 traceability summary extended; CT-GF-1..4 intact; front-matter amended_by += DCR-7.
    07-tasks.md: new M3 tasks T-3.8 (M, deps T-3.3, C3/C2 — implement-loop rework, resolves D3), T-3.9 (M,
    deps T-3.8, C3/C2 — end-of-phase/testable-only verify + no-test TERMINAL + AC-20.6→AC-3.6 mis-citation fix,
    resolves D1), T-3.10 (M, deps T-3.9, C3/C15 — intra-IMPLEMENT resume skips completed tasks, resolves D2);
    § 6 US-1/2/3 row += T-3.8/T-3.9/T-3.10, US-7 row += T-3.10; front-matter amended_by += DCR-7; G-gate table
    untouched. design-progress.md flip-and-return + § 1/§ 3/§ 5 (Landed 4667724). Review:
    design/reviews/2026-06-25-amendment-greenfield-implement-verify-model-r1.md. SHA-backfill commit beb563e
    (design-progress § 5 + review approved_in), mirroring the DCR-3/DCR-4/DCR-6 backfill precedent. NO milestone
    gate touched; G3 stays OPEN.
- ripple_unresolved: |
    1 item, NON-BLOCKING, OUTSIDE the approved four-file scope (same precedent as the still-open DCR-5 C3 and
    DCR-6 C3/C7/C9 ripples): design/02-architecture.md C3 (Workflow drivers, line 91) / C15 (Session/Lineage
    store, line 103) rows record the DCR-1/2/3 greenfield contracts but do not yet note the DCR-7 IMPLEMENT-
    phase verify-at-end / testable-only model (mark-complete-on-implementation, end-of-phase verify, no-test
    complete-with-warning, intra-IMPLEMENT resume skipping completed tasks). Semantic addition (judgment), not
    a mechanical cross-reference; deliberately OUT of the approved scope_of_design_edit. ADR-0012 carries the
    architecture's greenfield-implement semantics and IS updated; T-3.8/T-3.9/T-3.10 cite ADR-0012 +
    AC-3.2/AC-3.3/AC-3.6/AC-7.6 directly, so the missing C3/C15 notes do NOT block any task. Surfaced to the
    user as a candidate for a future tiny doc-fold-in — could bundle the three open C3 ripples (DCR-5, DCR-6,
    DCR-7) into one. Not a new ambiguity, not scope creep. (Designer inspected-and-dismissed several other
    sites — 02-architecture failure matrix, 05-operations, 04-apis + ADR-0003 AC-20.6 prefer-named-commands,
    01-overview, 03-data-model — all generic/unrelated and still correct.)
- assigned_ids: { new_tasks: [T-3.8, T-3.9, T-3.10], new_ac: AC-3.6, new_cts: [CT-GF-5, CT-GF-6, CT-GF-7, CT-GF-8] }
- budget: amendment #1 of 3 for T-3.8 (creates all three task rows); DCR-7, amendment #6 of 10 for milestone M3 (warn threshold 8, not hit)
- resumed_task_commit: (pending — T-3.8/T-3.9/T-3.10 driven in sequence after this amendment)
- status: open (DCR-7 amended at 4667724 [backfill beb563e]; resuming — coordinator drives T-3.8 → T-3.9 → T-3.10 under single-agent topology, each follow-on code commit carrying "Spec amendment: 4667724 (DCR-7)" per shared.md § 7.3; will close with the three resolution SHAs)
