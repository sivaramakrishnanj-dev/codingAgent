---
adr: 0004
title: Permission model — four modes, Class R/X, destructive denylist, grant matching
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0003, ADR-0010, ADR-0001]
spec_refs: [US-9, US-10, RD-1, RD-2, RD-3, RD-4, RD-5, RD-6, AC-9.1, AC-9.2, AC-9.3, AC-9.4, AC-9.5, AC-9.6, AC-10.1, AC-10.2, AC-10.3, AC-10.4, AC-10.5, AC-10.6, OQ-E]
---

# ADR-0004 — Permission model

## Status

accepted (2026-06-15)

## Context

The agent's primary power is "write files and run commands an LLM generated" — the highest-risk surface (ADR-0003). The Permission Gate (C8) is the single chokepoint between parsing a `toolUse` and executing it (`02-architecture.md` § 2). Requirements fix the shape (US-9/10, RD-1..RD-6, AC-9.*/10.*); this ADR resolves **OQ-E** — the two pieces requirements deferred to design:

1. The **normalized-prefix matching algorithm** for `ASK_ONCE_THEN_REMEMBER` (RD-1).
2. The **concrete destructive-command denylist** (RD-2).

…plus the gate's placement, classification rule, and grant lifecycle.

## Decision

### Operation classification (RD-4)

Every tool call is classified before the gate decides:

- **Class R (read)** — `read_file`, `grep`, `glob`, `list`, memory reads. **Auto-approved in every mode** (AC-9.6). Never gated.
- **Class X (side-effecting)** — `write_file`/`edit_file`, `run_command` (incl. build/test), `web_search`/`web_fetch` (subprocess + network), `spawn_subagent`, memory writes. **Gated per the active mode.**

Classification is a static property of the tool (declared in its registry entry, C7), except `run_command`, whose **command string** is additionally tested against the destructive denylist below.

### The four modes (AC-9.1)

| Mode | Class R | Class X | Denylisted command |
|------|---------|---------|--------------------|
| `READ_ONLY` | auto | **denied** | denied (AC-9.2, RD-6: web lookup denied here too) |
| `ASK_EVERY_TIME` (**default**, RD-3) | auto | prompt every time | prompt every time |
| `ASK_ONCE_THEN_REMEMBER` | auto | prompt first match, then auto-approve matches | **always prompt** (never remembered) |
| `UNRESTRICTED` | auto | auto | **always prompt** (RD-2 carve-out) |

The denylist column encodes RD-2: a destructive command **always prompts** (even in `UNRESTRICTED`), is **never** auto-approved by a remembered grant, and is **denied outright** in `READ_ONLY`.

### Grant matching for `ASK_ONCE_THEN_REMEMBER` (RD-1 — resolves OQ-E part 1)

A remembered grant is keyed by a **normalized match key**:

- **For commands** (`run_command`, named commands): key = **tool + first executable token + canonical subcommand where applicable**, i.e. a *normalized prefix*. Algorithm:
  1. Tokenize the command (shell-word split, honoring quotes).
  2. Take the **first token** (the executable), basename it (`/usr/bin/git` → `git`).
  3. If the executable is in a small **known-subcommand set** (`git`, `mvn`, `npm`, `cargo`, `docker`, `gradle`, `aws`), also take the **first non-flag token** as the subcommand (`git` + `status`, `mvn` + `test`). Otherwise the key is just the executable.
  4. Match key = `run_command:<exe>[ <subcommand>]`. Approving `mvn test` remembers `run_command:mvn test` → later `mvn test -X`, `mvn test -pl core` match; `mvn deploy` does **not** (different subcommand) → re-prompts.
     - For executables **not** in the known-subcommand set, approving `ls -la` remembers `run_command:ls` → any later `ls …` matches. (Conservative bias: subcommand granularity for the high-blast-radius tools, executable granularity otherwise.)
- **For file writes** (`write_file`/`edit_file`): key = **tool + containing directory subtree**. Approving a write under `src/` remembers `write:src/` → later writes anywhere under `src/` match; a write to `/etc/…` does not. Subtree = the file's parent directory and its descendants, resolved to a real path inside the workspace.
- **For other Class X** (`web_search`, `spawn_subagent`, memory write): key = **tool name** (coarse — these are uniform enough that per-invocation prompting after the first adds no safety).
- **A denylisted command never produces or matches a grant** — the denylist test runs *first*; on a hit the gate prompts unconditionally and records no grant (AC-10.4).

### Destructive denylist (RD-2 — resolves OQ-E part 2)

Default denylist (configurable — users may add; removing entries requires explicit config per RD-2's spirit). A command matches if **any token sequence** matches a pattern:

| Pattern | Why |
|---------|-----|
| `rm` with `-r`/`-f`/`-rf` (or `rm` of a non-empty target) | Recursive/forced delete |
| `rmdir` | Directory delete |
| `mv`/`cp` whose **destination exists** (overwrite) | Silent data loss |
| `dd` | Raw disk write |
| `truncate` | Destructive resize |
| `>` / `>|` output redirect over an **existing** file | Overwrite-by-redirect |
| `git push --force` / `git push -f` | History overwrite on remote |
| `git reset --hard` | Working-tree/history loss |
| `git clean -f`/`-fd`/`-fdx` | Untracked-file delete |
| `git checkout -- .` / `git restore` (broad) | Working-tree discard |
| `chmod`/`chown -R` on broad paths | Permission blast radius |
| `:(){ :|:& };:` and obvious fork-bomb shapes | Resource exhaustion |
| `sudo` (any) | Privilege escalation → always confirm |
| `curl`/`wget … | sh` (pipe-to-shell) | Remote code execution |
| `kill -9`/`killall` of non-child PIDs | Killing unrelated processes |

Matching is a **conservative pattern test** (substring/regex on the tokenized command). False positives prompt unnecessarily (safe); the user can approve. The exhaustive, maintained list lives in code seeded from this table; this table is the design contract.

### Grant lifecycle (RD-5)

- Grants are **scoped to the session and its compaction-derived lineage** — held in memory, persisted with the session so a resumed session retains them, but **not shared across separate sessions** (AC-10.3).
- **Sub-agents do NOT inherit the parent's grants** (AC-10.6, RD-5) — a child runs the configured mode fresh. Prevents a child riding a broad parent grant into an unexpected action.

### Gate placement + presentation

- The gate runs **between** `toolUse` parse and execution, for every tool (Class R auto-passes; Class X evaluated). Single chokepoint (`02-architecture.md` § 2).
- On a prompt, the gate shows the **exact operation**: full command string, or file path + change summary (AC-10.1). Decision is approve/deny (AC-10.2). Denial → no side effect; the loop records a `toolResult: denied` and the model picks a next step or stops (exit 3 if blocked).
- **Every approval and denial is an event** in the session log (AC-10.5).

## Consequences

**Positive**
- Closes the `rm -rf` hole the prefix rule alone would open (the preview that motivated RD-2) while keeping `mvn *`/`git status` ergonomics.
- One classification + one chokepoint → auditable, testable safety surface; pairs cleanly with ADR-0003 (every command) and ADR-0010 (sub-agent isolation).
- Subcommand-granular matching for high-blast tools is a deliberate safety bias.

**Negative / costs**
- The denylist is heuristic — false positives (extra prompts) and the eternal risk of a missed destructive form. Mitigated: conservative matching, configurable, and `UNRESTRICTED` still prompts denylisted ops so there's a backstop.
- Shell-word tokenization must handle quoting/escaping correctly or matching is unsound — needs careful implementation + adversarial tests.
- Per-subtree write grants can still surprise (a broad `src/` grant) — acceptable; the first prompt shows the subtree scope.

**Neutral / follow-ons**
- Phase 3 may formalize the denylist + match algorithm as a contract-tested artifact (`06-formal/`) with positive/negative fixtures.
- The known-subcommand set and denylist are config-extensible — documented in `05-operations.md`.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Exact-string grant match** | Remember only byte-identical commands | Re-prompts on trivial flag changes (`mvn test` vs `mvn test -X`) — poor ergonomics; user chose tool+prefix (RD-1) |
| **First-token-only match** | One `mvn` approval covers all `mvn …` | Too coarse for high-blast tools — `mvn dependency:purge` shouldn't ride an `mvn test` grant; hence subcommand granularity |
| **No denylist, rely on mode** | Simpler; `UNRESTRICTED` means unrestricted | Reintroduces the `rm -rf` hole; user explicitly chose the RD-2 carve-out |
| **Allowlist-only (no generic exec)** | Only pre-approved commands run | Too rigid (ADR-0003 alternative); the gate + denylist bound risk without crippling capability |
| **OS sandbox / container** | Stronger isolation than a denylist | Future-work (OOS); heavy for v1; the gate is the v1 safety surface |

## Notes

- Resolves OQ-E (both parts). The match algorithm + denylist are the two things `00-requirements.md` RD-1/RD-2 explicitly deferred to this ADR.
- Web lookup is Class X and **denied in `READ_ONLY`** (RD-6, AC-11.2) — consistent with the table.
- Grant persistence rides the session store (ADR-0005); lineage scoping (RD-5) aligns with the conversation-tree model.
