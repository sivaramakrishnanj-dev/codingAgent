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
