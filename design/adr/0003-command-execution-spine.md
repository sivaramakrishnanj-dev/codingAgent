---
adr: 0003
title: Command execution as the verification + safety spine
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0004, ADR-0006, ADR-0001]
spec_refs: [US-20, US-5, RD-4, RD-10, NFR-CMD-TIMEOUT, NFR-VERIFY-MAX-ITERATIONS, NFR-OUTPUT-MAX-INLINE]
---

# ADR-0003 — Command execution as the verification + safety spine

## Status

accepted (2026-06-15)

## Context

By dropping AST/LSP (brainstorm decision), **build/test commands are the sole ground truth for correctness**, and "run arbitrary commands" became the agent's primary power. Command execution (C10) therefore sits at the intersection of three concerns: **verification** (US-20 — exit code is the success signal, RD-10), **safety** (every command is a gated Class X op, ADR-0004), and **context** (command output is the biggest single token producer — disposal, ADR-0006). This ADR fixes the executor's contract; ADR-0004 owns the gate, ADR-0006 owns disposal.

Open sub-questions: named vs ad-hoc commands; the captured result shape; timeout + process-tree handling; how output hands off to disposal; working directory + environment.

## Decision

**We will implement a single Command Executor (C10) with a two-layer command model and a structured, captured result; it is the only path to a subprocess and always routes through the permission gate.**

- **Two layers (extensibility "via bash"):**
  - **Named commands** — declared in config (`build_command`, `test_command`, `lint_command`, …). The agent prefers these for canonical operations (AC-20.6). Vetted, stable, the verification path.
  - **Generic `run_command(command)`** — the escape hatch for anything config didn't anticipate. Same gate, same capture.
- **Structured result** (every execution, named or ad-hoc): `{ command, exitCode, stdout, stderr, durationMs, timedOut, truncated }`. The loop reasons on `exitCode`; `truncated` signals disposal happened.
- **Verification semantics.** A zero exit from the configured **test** command = success for the unit of work (RD-10, AC-20.4). Non-zero → feed stderr/stdout back into the loop and attempt a remedy (AC-20.3), bounded by `NFR-VERIFY-MAX-ITERATIONS` (5) then stop-and-surface (AC-3.4/20.5).
- **Timeout + process tree.** Each command runs with `NFR-CMD-TIMEOUT` (default 300 s, configurable). On timeout the **whole process tree** is killed (not just the spawned parent — Maven forks), `timedOut=true`, surfaced to the model as a tool failure.
- **Output → disposal.** stdout/stderr above `NFR-OUTPUT-MAX-INLINE` (16 KB) are handed to the Context Manager (ADR-0006): the full output is persisted to the event log (AC-19.2), a reduced form enters context, `truncated=true`.
- **Execution context.** Commands run with the **workspace (repo root) as working directory**, inheriting the user's environment. The executor adds nothing implicitly. (Sandboxing beyond the permission gate is future-work per the OOS list.)
- **Java mechanism.** `java.lang.ProcessBuilder` (redirecting stdout/stderr separately), read on a bounded buffer with the timeout enforced via `Process.waitFor(timeout)` + `descendants()` for tree-kill. No shell interpolation unless a command explicitly needs a shell (then `sh -c` with the command as a single argument, and the denylist/gate applies to the whole string).

## Consequences

**Positive**
- One choke point for all subprocess side effects — clean placement for the gate (ADR-0004) and disposal (ADR-0006).
- The exit code is a first-class signal → drives verification (US-20) and outcome capture (US-16, RD-10) for free.
- Language-agnostic: "build/test" is whatever the config says, so the core needs no Maven knowledge (Java/Maven is just the first config).

**Negative / costs**
- Every edit now costs a real compile/test to verify (seconds–minutes) — no fast AST pre-check (accepted when AST was dropped).
- Verbose build output is a constant token tax → makes ADR-0006 disposal mandatory, not optional.
- Process-tree kill + stream draining are fiddly to get right (zombies, deadlock on full pipe buffers) — call out for careful implementation + tests.

**Neutral / follow-ons**
- ADR-0004 defines how a command is classified (Class X) and matched against the denylist + remembered grants.
- ADR-0006 defines the reduce/summarize strategy for `truncated` output.
- Streaming/background execution of long commands is future-work (OOS); v1 is synchronous capture with timeout.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Only named commands** (no generic exec) | Safer; only vetted commands run | Too rigid — the agent constantly needs one-off commands (git, ls, grep-via-shell); the gate already bounds risk |
| **Always run via `sh -c`** | Uniform shell semantics | Shell interpolation/injection surface; we prefer argv execution and opt into a shell only when needed |
| **Stream output into context live** | No disposal needed if streamed | Streaming verbose build logs straight into the window is exactly the context blowout US-19 prevents |
| **Pseudo-terminal (PTY)** | Better TUI/interactive command support | Complexity not justified for v1; build/test tools are non-interactive; revisit if interactive commands matter |

## Notes

- `truncated`/`timedOut` are explicit so the model can reason ("output was cut — retrieve full from log" per AC-19.3; "command timed out — try a narrower target").
- Tree-kill: use `ProcessHandle.descendants()` (Java 9+) to terminate forked children — verified-applicable on Java 21 (`NFR-PLAT-JAVA`).
- The destructive-command denylist (RD-2) that constrains `run_command` is defined in ADR-0004, not here — this ADR owns *execution*, that one owns *authorization*.
