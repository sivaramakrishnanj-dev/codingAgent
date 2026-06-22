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
