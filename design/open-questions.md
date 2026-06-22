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
