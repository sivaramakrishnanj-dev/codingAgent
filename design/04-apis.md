---
doc: apis
last_reviewed: 2026-06-16
phase: 2-design
status: resolved
review: reviews/2026-06-16-apis-r1.md
approved_in: pending
---

# APIs & Contracts — codingAgent

> **Phase 2, artifact 4 of 5.** The contract reference: every boundary the system **provides** (the CLI) and **consumes/invokes** (Bedrock Converse, the built-in tools, the web delegate). Prose + signatures — **JSON schemas are Phase 3** (`06-formal/`). Decisions live in the ADRs; this doc documents the *surfaces* they imply, not the decisions. CLI command names below are **proposed** (flagged for review).

## 1. CLI contract (provided)

The product's primary interface (C1). Single binary, single-user, one repo per invocation (`AC-6.1`, `AC-6.2`). Two interaction shapes — **interactive REPL** and **one-shot** — both resolved in-scope per OQ-H.

### 1.1 Invocation shapes

| Shape | Form | Use |
|-------|------|-----|
| **Interactive (REPL)** | `codingagent [options]` → enters a prompt loop | the default; multi-turn work, approvals inline |
| **One-shot** | `codingagent -p "<prompt>" [options]` | scripting / single task; runs to `end_turn` then exits |

Working directory = the target repository (`AC-6.2`). Configuration resolved at startup per ADR-0009 precedence (flags > project > global > defaults); malformed/missing config → exit `2` before any model call (`AC-6.3`, `AC-6.4`).

### 1.2 Subcommands (proposed)

| Command                                                          | Purpose                                                                                              | Refs                 |
| ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- | -------------------- |
| `codingagent`                                                    | start interactive session (mode from config/flag)                                                    | US-6                 |
| `codingagent -p "<prompt>"`                                      | one-shot run                                                                                         | US-6                 |
| `codingagent --mode greenfield\|brownfield`                      | choose workflow (C3)                                                                                 | US-1..5              |
| `codingagent resume [<session-id>]`                              | list resumable sessions (most-recent first) or resume one; latest compaction-continuation by default | US-7, AC-7.1/7.2/7.4 |
| `codingagent sessions`                                           | list past sessions for this repo (incl. compacted)                                                   | US-15, AC-15.2       |
| `codingagent memory [list\|show <slug>\|edit <slug>\|rm <slug>]` | inspect/curate memory (also hand-editable on disk)                                                   | US-14, AC-14.1/14.3  |
| `codingagent config [show\|path]`                                | show resolved config / file locations                                                                | US-8                 |

### 1.3 Common options (proposed)

| Flag                                                                                  | Effect                                         | Refs             |
| ------------------------------------------------------------------------------------- | ---------------------------------------------- | ---------------- |
| `-p, --prompt <text>`                                                                 | one-shot prompt                                | US-6             |
| `--mode <greenfield\|brownfield>`                                                     | workflow mode                                  | US-1..5          |
| `--model <id>`                                                                        | override model id                              | AC-8.1           |
| `--permission-mode <UNRESTRICTED\|READ_ONLY\|ASK_EVERY_TIME\|ASK_ONCE_THEN_REMEMBER>` | override permission mode                       | US-9, AC-9.1     |
| `--profile <name>` / `--region <r>`                                                   | AWS profile / region (SigV4 only; ADR-0011)    | AC-8.6           |
| `--attach <path>` (repeatable)                                                        | attach an image/document to the prompt (§ 1.5) | US-1, multimodal |
| `--debug`                                                                             | DEBUG-level internals to stderr                | NFR-LOG (DEBUG)  |
| `--version` / `--help`                                                                | standard                                       | —                |

### 1.4 In-REPL commands (proposed)

Slash-commands inside the interactive loop (distinct from shell subcommands):

| In-REPL | Effect | Refs |
|---------|--------|------|
| `/compact` | force compaction now (regardless of utilization) | US-18, AC-18.2 |
| `/attach <path>` | attach an image/document to the next message | multimodal |
| `/remember <text>` | explicitly store a memory entry | US-12, AC-12.1 |
| `/mode`, `/model`, `/permission` | show/adjust current settings | US-8/9 |
| `/exit` (or Ctrl-D) | end session cleanly (exit 0); Ctrl-C interrupts current step (exit 130) | §02 §4 |

### 1.5 Multimodal attachments (input)

`--attach <path>` (CLI) / `/attach <path>` (REPL) attaches a local file to the prompt. The CLI infers the kind from extension and builds the matching block (`03-data-model.md` § 2.3):
- **Image** (`png/jpeg/gif/webp`) → `ImageBlock`.
- **Document** (`pdf/csv/doc/docx/xls/xlsx/html/txt/md`) → `DocumentBlock` with a **sanitized neutral `name`** (INV-18).
- Read as raw bytes (SDK base64-encodes). Unsupported extension → clear error. If the active model lacks image/document support (capability profile, INV-19) → decline with a message, don't send.

### 1.6 Output & exit codes

- Interactive: streamed assistant text (`ConverseStream`), inline approval prompts showing the exact operation (`AC-10.1`), progress notes for compaction/long commands.
- Library modules never write to stdout/stderr directly; the CLI layer owns user-facing output (`NFR-LOG` — library/CLI split).
- Exit codes per `03-data-model.md` § 4 `ExitCode` (0 ok · 1 internal · 2 usage/config · 3 user-aborted · 4 model-backend · 5 context-exhausted · 130 interrupted). Authoritative contract: `06-formal/cli-exit-codes.md` (Phase 3).

## 2. Bedrock Converse boundary (consumed)

Prose only — the verified field-level facts are in `design-progress.md` § 6.A.1; the decision is ADR-0001; credentials ADR-0011. The Model Client (C4) is the sole owner of this boundary; the wire-format mapping is `03-data-model.md` § 7.

- **Operations.** `Converse` (sync) and `ConverseStream` (interactive). Endpoint `bedrock-runtime`, AWS SDK for Java v2.
- **Request we send.** `modelId` (resolved config); `messages[]` (replayed/accumulated from events); `system[]` (persona + tool instructions + memory index — the cacheable static prefix); `toolConfig` (registry → `toolSpec`s, § 3); `inferenceConfig` (maxTokens/temperature/…); `additionalModelRequestFields` (capability-gated: extended-thinking budget, `top_k` — ADR-0002); `cachePoint` after the static prefix (ADR-0006).
- **Response we read.** `output.message.content[]` (text/toolUse/reasoning blocks); `stopReason` (loop selector — `02-architecture.md` § 3); `usage` (measured tokens → compaction); `metrics.latencyMs`.
- **Auth.** SigV4 via profile → default chain; **`AWS_BEARER_TOKEN_BEDROCK` ignored** (ADR-0011, INV-16). Region from config (default `us-east-1`).
- **Errors.** Retryable (429/503/408/500) with backoff ≤ `NFR-BEDROCK-MAX-RETRIES` → exit 4 on exhaustion; non-retryable (400/403) → exit 4 (`02-architecture.md` § 3.2).
- **IAM.** Read/invoke only: `bedrock:InvokeModel`, `bedrock:InvokeModelWithResponseStream`, inference-profile reads. No write verbs (ADR-0011).

## 3. Tool contracts (provided to the model)

Each built-in tool is a registry entry (C7) → Converse `toolSpec {name, description, inputSchema}` (OQ-A, ADR-0001). The model emits `toolUse`; the permission gate (C8, ADR-0004) classifies + authorizes; the handler runs. **Class** = R (read, auto-approved) or X (gated). Input shapes are prose here; JSON schemas in `06-formal/`.

| Tool | Class | Input (shape) | Result | Refs |
|------|-------|---------------|--------|------|
| `read_file` | R | `path`, `offset?`, `limit?` | file text (disposal if > cap) | US-4 |
| `grep` | R | `pattern`, `path?`, `glob?`, flags | matches | US-4, AC-4.1 |
| `glob` | R | `pattern`, `path?` | matching paths | US-4 |
| `list` | R | `path` | dir entries | US-4 |
| `write_file` | X | `path`, `content` | ok/diff summary | US-5, AC-5.2 |
| `edit_file` | X | `path`, `old`, `new` (or patch) | ok/diff summary | US-5 |
| `run_command` | X | `command` (string) | `CommandResult` (`03` § 2.4) | US-20, ADR-0003 |
| `web_search` | X | `query` | summarized text | US-11, ADR-0008 |
| `web_fetch` | X | `url` | summarized text | US-11, ADR-0008 |
| `spawn_subagent` | X | `prompt`, `model?`, `budget?` | summary | US-17, ADR-0010 |
| `read_memory` | R | `slug` | entry markdown | US-12, ADR-0007 |
| `write_memory` | X | `tier`, `slug`, `body`, `why` | ok (after approval) | US-12/21, ADR-0007 |

Notes:
- **Named project commands** (build/test/lint from config) are exposed to the model as distinct tool entries or as `run_command` invocations the model prefers (AC-20.6); both route through the gate. Concrete shape (separate tools vs. annotated `run_command`) finalized in Phase 4.
- `run_command` results carry `exitCode` — the verification signal (RD-10). Output over `NFR-OUTPUT-MAX-INLINE` is disposed (ADR-0006); full output retrievable from the log (AC-19.3).
- Tool errors return as `toolResult {status: error}` (§ 6.A.1) so the model can react, not crash.

## 4. Web-delegate contract (invoked)

The backend behind `web_search`/`web_fetch` (ADR-0008, C11). Interface `WebLookupBackend`; v1 implementation = constrained headless Claude.

- **Invocation.** `claude -p "<lookup task>" --output-format text` (exact flags confirmed at implementation), spawned via the Command Executor's subprocess machinery (ADR-0003).
- **Constraints.** Sandboxed: web-only tools, CWD = scratch/temp dir (not the workspace), hard timeout `NFR-NET-WEBLOOKUP-TIMEOUT` (120 s), no repo write. Returns **text**.
- **Failure.** Absent on `PATH` / error / timeout → failure result; the agent reports it, never fabricates (AC-11.3).
- **Permission.** Class X; denied in `READ_ONLY` (RD-6, AC-11.2). Logged as events (AC-11.4).
- **Swappable.** The interface allows a future direct-search-API backend without changing the tool contract.

## 5. Provided on-disk contracts (consumed by the Operator)

Not RPC, but stable formats the Operator reads/edits directly (ADR-0005/0007/0009):
- **Session JSONL** `~/.codingagent/projects/<repo-key>/sessions/<id>.jsonl` — the event stream (US-13/15).
- **Memory markdown** `~/.codingagent/{memory,projects/<repo-key>/memory}/<slug>.md` + `INDEX.md` — hand-editable (US-14).
- **Config YAML** global + project (US-8).

Schemas/field-level contracts: `06-formal/` (Phase 3).

## 6. Reading onward

- `05-operations.md` — build, run, observability, failure remediation, packaging.
- `06-formal/` — JSON schemas for tools/events/config, `cli-exit-codes.md`, the formal state machine, contract tests.
- ADRs: 0001 (engine), 0003 (command), 0004 (permission), 0008 (delegate), 0009 (config), 0011 (credentials).
- `03-data-model.md` — the types these contracts move.
