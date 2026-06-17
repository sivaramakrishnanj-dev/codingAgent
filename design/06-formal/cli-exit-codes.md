---
doc: formal-cli-exit-codes
last_reviewed: 2026-06-17
phase: 3-formal
status: resolved
review: ../reviews/2026-06-17-formal-batch1-r1.md
approved_in: 296e3e2
---

# CLI Exit Codes — Authoritative Contract

> **Phase 3.** The single source of truth for codingAgent's process exit codes. The narrative matrices in `02-architecture.md` § 3.2 and `05-operations.md` § 4.1 **defer to this file** — on any disagreement, this wins. Promoted from the `00-requirements.md` § 1b seed and the `ExitCode` enum in `03-data-model.md` § 4.

## 1. Codes

| Code | Symbol | Category | Meaning | Primary triggers |
|------|--------|----------|---------|------------------|
| `0` | `OK` | success | Requested work completed; interactive session exited cleanly (`/exit`, EOF). | normal completion |
| `1` | `INTERNAL` | internal-error | Unexpected/unhandled error — a bug or environment fault not otherwise classified. | uncaught exception; event could not be persisted (AC-13.4) |
| `2` | `USAGE_CONFIG` | usage/config | Bad invocation or invalid/missing configuration — detected **before** doing work. | unknown flag; malformed/unknown config key (AC-8.5); missing required field (AC-6.4) |
| `3` | `USER_ABORTED` | user-aborted | A required action was **denied** by the user, blocking progress. | denial of a gated op the task cannot proceed without (AC-10.2) |
| `4` | `MODEL_BACKEND` | model-backend | Bedrock could not be used. | no usable SigV4 credentials (AC-8.9); auth failure (403); retries exhausted on 429/503/408/500 (`NFR-BEDROCK-MAX-RETRIES`); validation 400; model/region access denied |
| `5` | `CONTEXT_EXHAUSTED` | context-exhausted | Context limit hit and compaction could not recover. | compaction failure path (`03-data-model.md` § 6 `Failed`) |
| `130` | `INTERRUPTED` | interrupted | Terminated by SIGINT (Ctrl-C). | user interrupt (128 + SIGINT(2), POSIX convention) |

No other codes are emitted by codingAgent itself. (A wrapper script or the JVM may surface others — e.g. 137 on SIGKILL/OOM — but those are not part of this contract.)

## 2. Precedence (when multiple could apply)

Evaluated in this order; the first matching code is the exit:

1. `130` — SIGINT always wins (the user asked to stop now).
2. `2` — usage/config errors are detected at startup, before any model/tool work, so they precede runtime failures.
3. `4` — model-backend failures (incl. credential resolution) before deeper runtime states.
4. `5` — context-exhausted (a specific recoverable-attempt-failed state).
5. `3` — user-aborted (a deliberate denial during the run).
6. `1` — internal, the catch-all for anything unclassified.
7. `0` — success, only if none of the above occurred.

Rationale: startup-detectable problems (2) and "can't even talk to the model" (4) are reported before run-time states; `1` is strictly the unclassified fallback so a real, classifiable cause is never masked by the catch-all.

## 3. Guarantees

- **G1 — Stable.** These numeric codes are a stable contract within v1; meanings do not change between v1 releases. New categories, if ever needed, take new unused numbers.
- **G2 — Message accompanies non-zero.** Every non-zero exit is accompanied by a human-readable stderr line naming the cause and, where applicable, the offending key/path/credential-tier (AC-6.4, AC-8.5, AC-8.9).
- **G3 — Scriptable.** One-shot mode (`-p`) returns these codes so callers can branch (e.g. CI). Interactive mode returns `0` on clean `/exit`, `130` on Ctrl-C.
- **G4 — Not the model's verification signal.** These are the **agent process** exit codes. They are distinct from the **build/test command** exit code the agent runs internally (the verification signal, RD-10 / AC-20.4) — the latter lives inside a `CommandResult`, not the agent's own exit.

## 4. Traceability

| Code | ACs / sources |
|------|---------------|
| 0 | US-6, clean-exit |
| 1 | AC-13.4 |
| 2 | AC-6.4, AC-8.5 |
| 3 | AC-10.2 |
| 4 | AC-8.9, NFR-BEDROCK-MAX-RETRIES, ADR-0011 |
| 5 | AC-18.* failure, `03` § 6 |
| 130 | `02` § 4 (SIGINT) |

Contract tests for exit-code behavior are indexed in `contract-tests.md` (batch 2).
