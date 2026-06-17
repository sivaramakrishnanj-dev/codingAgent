---
doc: design-progress
last_updated: 2026-06-17
last_updated_at_commit: 296e3e2
current_phase: 3
current_sub_phase: 3-formal
current_sub_phase_status: drafting
next_action: Batch 1 (cli-exit-codes + state-machine) RESOLVED (review formal-batch1-r1). Next: draft batch 2 (data contracts) under 06-formal/ — JSON Schemas Draft 2020-12 for: event.schema.json (+ the 13 event types), content-block.schema.json (incl Image/Document), command-result.schema.json, memory-entry.schema.json, resolved-config.schema.json, model-capability-profile.schema.json; contract-tests.md (positive/negative per schema + per INV-*, referencing S*/T*/L*/LT* + exit-code ids, each traceable to an AC); fixtures/ (session JSONL, config YAML, memory entry md, a tool-use cycle) validated against schemas. Present as batch 2; 3-formal resolves on approval.
next_artifact_to_touch: design/06-formal/event.schema.json
---

# Design progress — codingAgent

## 1. Where we are

Phase 1a (personas + user stories) is **resolved** — approved by user ("good to go"), reviewed in `reviews/2026-06-14-requirements-1a-r1.md`, committed as the project's first commit. 3 personas (P1 Developer, P2 Operator, P3 The Agent) and 21 user stories (US-1..US-21) are baselined.

Phase 1b (EARS acceptance criteria) is **resolved** — approved by user ("good to go"), reviewed in `reviews/2026-06-14-acceptance-criteria-1b-r1.md`. Baselined: RD-1..RD-10 behavioral defaults, CLI exit-code seed (0/1/2/3/4/5/130), 80 EARS-tagged ACs (AC-1.1..AC-21.4) across US-1..US-21, and a 7-symbol `NFR-*` table for 1c. `ASK_ONCE_THEN_REMEMBER` resolved as RD-1 (tool + normalized prefix) with RD-2 destructive-denylist carve-out — the §2 parked question is now closed.

**Phase 1 is complete.** All three sub-phases resolved: 1a (3 personas, 21 stories), 1b (10 RD defaults, exit-code seed, 80 EARS ACs), 1c (all NFRs pinned). 1c approval also folded in a user-directed AWS-credential requirement (RD-11, AC-8.6–8.8, NFR-AWS-CREDENTIALS): named-profile-first, fall back to the AWS default credential chain, fail to exit 4 only if neither yields usable credentials.

In **Phase 2 — Design**. `01-overview.md` is **resolved** (review: `reviews/2026-06-14-overview-r1.md`). Approval folded in a user-directed scope refinement: **provider-agnostic by design, Claude-only validated/shipped in v1** (`NFR-MODEL-PROVIDER`, OQ-J). Converse API verified facts live in § 6.A.1; raw docs in `research/`.

`02-architecture.md` (the doc) is **resolved** (review: `reviews/2026-06-15-architecture-r1.md`). 17 components (C1-C17) in a 5-layer monolith, agent-loop sequence (log-before-act, gate-in-the-middle), stopReason→action + error→exit matrices, concurrency/shutdown, `com.srk.codingagent.*` package layout, ADR queue. Three reviewer flags accepted as drafted (keep 17 components, CLI does one-shot+REPL, keep 12 ADRs separate).

The **`2-architecture` sub-phase is fully RESOLVED** — `02-architecture.md` doc + all 12 ADRs (batch 1: 0001-0004; batch 2: 0005-0012), reviews `architecture-r1` / `adr-batch1-r1` / `adr-batch2-r1`. **All open questions OQ-A..OQ-J are resolved.** Key decisions of record: owned Converse loop (0001) · capability-profile provider seam, Claude-only-v1 (0002) · command spine (0003) · 4-mode permission + RD-1 match + RD-2 denylist (0004) · event-sourced JSONL + conversation-tree (0005) · compaction-derives-new-session + head/tail disposal + cache placement (0006) · two-tier curated markdown memory (0007) · headless-claude web delegate, Responses rejected (0008) · YAML two-tier config (0009) · in-process sub-agents N=1 (0010) · SigV4-only credentials, bearer ignored (0011) · full-spec-driven greenfield (0012).

`03-data-model.md` is **resolved** (review: `data-model-r1`). 8 core entities, 13 EventTypes, 10 enums, **19 numbered INV-*** (added INV-18 doc-name-sanitization, INV-19 capability-gated-attachments with the multimodal addition), compaction state machine, wire-format boundary. **Multimodal input (image + document) added to v1 scope** — verified Converse formats (Word/Excel native); propagated to `00`/`01`/`design-progress §6.A.1`.

`04-apis.md` is **resolved** (review: `apis-r1`). CLI command names accepted as proposed (binary `codingagent`; subcommands resume/sessions/memory/config; `-p` one-shot; slash-commands). 12 tool contracts (Class R/X), Converse boundary prose, web-delegate + on-disk contracts. Two minor flags: named-command exposure deferred to Phase 4; memory subcommands kept.

**PHASE 2 (Design) is COMPLETE** — all six artifacts resolved: `01-overview`, `02-architecture` (doc + 12 ADRs), `03-data-model`, `04-apis`, `05-operations`. All OQ-A..OQ-J resolved. The system is fully designed: a layered-monolith Java/Maven CLI on Bedrock Converse with an owned agent loop, 4-mode permission gate + command spine, event-sourced JSONL persistence + conversation-tree + compaction-with-derivation, two-tier curated memory, in-process sub-agents (N=1), headless-claude web delegate, SigV4-only creds, full-spec-driven greenfield, multimodal (image+document) input.

In **Phase 3 — Formal Contracts** (`06-formal/`), reviewed in **two batches** (user choice). **Batch 1 (behavioral contracts) is DRAFTED, pending review:** `cli-exit-codes.md` (authoritative exit-code contract 0–5/130, precedence rules, G1–G4 guarantees, traceability) + `state-machine.md` (two formal machines — A: agent loop S0–S8 / T1–T19 driven by stopReason; B: conversation/compaction lifecycle L0–L5 / LT1–LT7 promoted from 03 §6 — with INV refs + Mermaid). Plus `06-formal/README.md`. Batch 2 (schemas + contract-tests + fixtures) follows on approval.

Per-unit progress for 3-formal:
- README + cli-exit-codes.md + state-machine.md (batch 1): **resolved** (review: `formal-batch1-r1`, `296e3e2`)
- schemas + contract-tests.md + fixtures/ (batch 2): **not started** (next)

Per-unit progress for 2-architecture (all resolved):
- 02-architecture.md doc — `2f5a25b`
- ADRs 0001-0004 (batch 1) — `3f048f2`
- ADRs 0005-0012 (batch 2) — `9bf5060`

ADR batch 1 notes: 0001 pins AWS SDK v2 `bedrockruntime:2.46.10` (confirm at impl) + owned Converse loop + Opus 4.8 default; 0002 = `ModelCapabilityProfile` registry (feature-detect, Claude-only-v1, thin seam); 0003 = command executor contract (two-layer, structured result, tree-kill timeout, output→disposal); 0004 = permission model resolving OQ-E (RD-1 normalized-prefix match algorithm: tool+exe+subcommand for high-blast tools, tool+subtree for writes; RD-2 destructive denylist table).

## 2. Deferred decisions

- **Project name** — working name is `codingAgent` (the directory). Not yet decided as the product name. Resolve before/at Phase 4 config-generation (`project_name` in `.kiro/spec-driven.yaml`).
- **Parallel sub-agent execution detail** — v1 starts with one sub-agent, config for N; whether N>1 run concurrently vs sequentially-with-isolation is a Phase 2 architecture detail. Capability captured in US-17, bound by `NFR-SUBAGENT-MAX` (1c).
- ~~**`ASK_ONCE_THEN_REMEMBER` matching semantics**~~ — **RESOLVED in 1b as RD-1** (tool + normalized command prefix; file-writes per subtree) with RD-2 destructive-denylist carve-out.
- ~~**Compaction trigger threshold value**~~ — moved to 1c as `NFR-CONTEXT-COMPACT-THRESHOLD`.

## 3. Carry-forwards

Cross-phase scope facts established during brainstorming that later phases must honour (each cites the symbol that pins it):

- Java is the first and only code-generation target for v1 (US-1..US-5 scope; "non-Java targets" is an OOS-list entry in `00-requirements.md`). The *core* is language-agnostic but only a Java/Maven config ships.
- AWS Bedrock is the only model backend for v1 (OOS-list entry "non-Bedrock providers").
- Single-user local CLI, one repo at a time (US-6; OOS-list entries for multi-user/daemon and IDE/GUI).
- Memory write policy is **explicit + agent-proposed-and-approved** for v1 (US-12 explicit; US-21 proposed; OOS-list entry "auto-extraction").
- Permission model: 4 modes + Class R/X taxonomy (RD-4), default `ASK_EVERY_TIME` (RD-3, `NFR-PERMISSION-DEFAULT`), `ASK_ONCE_THEN_REMEMBER` = tool+prefix (RD-1), destructive-denylist always-prompt/never-remember/READ_ONLY-denied (RD-2), grants not persisted cross-session nor inherited by sub-agents (RD-5, AC-10.6).
- Verification contract: zero exit from configured test command = success (RD-10, AC-20.4); agent stops & surfaces after `NFR-VERIFY-MAX-ITERATIONS` (AC-3.4, AC-20.5).
- Web-lookup denied in `READ_ONLY` (RD-6, AC-11.2).
- AWS credentials (RD-11 final): **SigV4 only — named profile → AWS default credential chain**; fail (exit 4) only if neither resolves. **`AWS_BEARER_TOKEN_BEDROCK` explicitly ignored even if set** (wrong-account footgun; AC-8.8 makes the ignore testable; warn if present). Read/invoke-only Bedrock — no AWS write verbs. → ADR-0011 (`ProfileCredentialsProvider` → `DefaultCredentialsProvider`, explicit SigV4 client construction).
- Base Java package = `com.srk.codingagent.*` (user-directed 2026-06-15; set in `02-architecture.md` § 6). Seeds Maven groupId/artifactId at Phase 4/5.
- All NFR numeric values pinned in 1c: Opus 4.x default (`NFR-MODEL-DEFAULT`, exact id Phase 2), 5 verify retries (`NFR-VERIFY-MAX-ITERATIONS`), 1 sub-agent (`NFR-SUBAGENT-MAX`), 0.85 compaction (`NFR-CONTEXT-COMPACT-THRESHOLD`), 16 KB output cap (`NFR-OUTPUT-MAX-INLINE`), 3 Bedrock retries (`NFR-BEDROCK-MAX-RETRIES`), Java 21 (`NFR-PLAT-JAVA`), 80% coverage gate (`NFR-TEST-COVERAGE`).

## 4. Recovery notes

_(none yet)_

## 5. Landed — historical

- 1a-user-stories — resolved (review: `reviews/2026-06-14-requirements-1a-r1.md`) — `19cbe08`
- 1b-acceptance-criteria — resolved (review: `reviews/2026-06-14-acceptance-criteria-1b-r1.md`) — `96f754b`
- 1c-nfrs — resolved, **Phase 1 closed** (review: `reviews/2026-06-14-nfrs-1c-r1.md`) — `e03b032`
- 2-overview — resolved (review: `reviews/2026-06-14-overview-r1.md`) — `7c458ef`
- 2-architecture (doc) — resolved (review: `reviews/2026-06-15-architecture-r1.md`) — `2f5a25b`
- 2-architecture ADRs batch 1 (0001-0004 + template) — resolved (review: `reviews/2026-06-15-adr-batch1-r1.md`) — `3f048f2`
- 2-architecture ADRs batch 2 (0005-0012) — resolved, **2-architecture sub-phase complete** (review: `reviews/2026-06-15-adr-batch2-r1.md`) — `9bf5060`
- 2-data-model — resolved, multimodal input added (review: `reviews/2026-06-15-data-model-r1.md`) — `f864cef`
- 2-apis — resolved (review: `reviews/2026-06-16-apis-r1.md`) — `da02464`
- 2-operations — resolved, **PHASE 2 COMPLETE** (review: `reviews/2026-06-16-operations-r1.md`) — `4cfb111`
- 3-formal batch 1 (cli-exit-codes + state-machine) — resolved (review: `reviews/2026-06-17-formal-batch1-r1.md`) — `296e3e2`

## 6. Phase 2 carry-forward material (pre-explored ADRs & mechanisms)

The brainstorm pre-explored a lot of **Phase 2 (architecture/ADR)** ground. None of it belongs in Phase 1 (requirements = *what/for whom*, not *how*). Captured here verbatim so it resurfaces as ADRs/architecture and **nothing is lost**. Each item notes its destined artifact.

### A. Engine & role
- **AWS SDK for Java v2, Bedrock Converse API, direct** (no LangChain4j/Spring AI). We own the loop: build request → parse text + `toolUse` → dispatch tools → append `toolResult` → repeat. → *ADR: engine / SDK choice.*

#### A.1 Bedrock Converse — VERIFIED facts (read 2026-06-14 from docs.aws.amazon.com via ReadInternalWebsites; ground the architecture in these, not memory)
- **Stateless API.** "Bedrock doesn't store any text… maintain context by including all messages in subsequent requests." → this is the *justification* for our persistence/event-log/resume design. Resume = replay events → rebuild `messages[]`.
- **Request shape:** `modelId`, `messages[]` (role + typed `content[]` blocks), `system[]` (separate from messages), `inferenceConfig` (maxTokens/temperature/topP/stopSequences), `additionalModelRequestFields` (model-specific: Claude extended-thinking budget, top_k), `toolConfig` (tools[] + toolChoice). Response: `output.message.content[]`, `stopReason`, `usage`, `metrics.latencyMs`.
- **ContentBlock types:** text, toolUse, toolResult, reasoningContent, cachePoint, image, document, (video = OOS v1).
- **Multimodal INPUT in scope v1 (user-directed 2026-06-15):** image + document input — load-bearing for greenfield/spec-driven (design diagrams US-1, PDF/Word use-case docs). **VERIFIED formats** (read 2026-06-15, DocumentBlock/ImageBlock API refs): `ImageBlock` = png|jpeg|gif|webp; `DocumentBlock` = pdf|csv|doc|docx|xls|xlsx|html|txt|md (**Word/Excel attach natively, no conversion**). Raw-bytes source (SDK base64-encodes; S3-source OOS for local CLI). Capability-gated via ModelCapabilityProfile (added supportsImageInput/supportsDocumentInput). `DocumentBlock.name` is a prompt-injection surface → must be neutral/sanitized (INV-18). Still OOS: image/video *generation* (output) + *video input* (VideoBlock). → 03-data-model § 2.3, INV-18/19; reflected in 00-requirements OOS + 01-overview scope.
- **Client-side tool-use loop (OUR LOOP):** send toolConfig → model returns `stopReason: tool_use` + `toolUse {toolUseId,name,input}` → **permission gate here** → execute → append `toolResult {toolUseId,content,status?}` as user-role msg → re-call → until `stopReason: end_turn`. Each tool = `toolSpec {name, description, inputSchema:<JSON Schema>}`.
- **stopReason values:** `end_turn | tool_use | max_tokens | stop_sequence | guardrail_intervened | content_filtered | malformed_model_output | malformed_tool_use | model_context_window_exceeded`. → drives loop state machine (Phase 3). `model_context_window_exceeded` → triggers compaction.
- **usage returned every call:** inputTokens, outputTokens, totalTokens, cacheReadInputTokens, cacheWriteInputTokens, cacheDetails. → `NFR-CONTEXT-COMPACT-THRESHOLD` is MEASURED not estimated.
- **ConverseStream events:** messageStart → (contentBlockStart, contentBlockDelta×N [text/reasoning/toolUse partial JSON], contentBlockStop) per block → messageStop(stopReason) → metadata(usage). → CLI streams; toolUse input assembled from partial-JSON deltas.
- **⚠ GOTCHA — reasoning signatures:** `reasoningContent` blocks carry a `signature` (hash over conversation); MUST resend signature + all prior messages unchanged or the call errors. → transcript stores reasoning blocks verbatim; **compaction must start a fresh derived conversation, cannot edit history in place** (validates `derived-from` design — and explains WHY it's mandatory).
- **Prompt caching (`cachePoint`):** caches static prefix, order tools→system→messages; cached tokens billed reduced + don't count vs rate limit; changing earlier section invalidates later. Opus 4.5/4.6 need ≥4096 tokens/checkpoint, max 4; Opus 4.5 supports 1h TTL (others 5m). → **cost mitigation for Opus default**; tool defs + system prompt + memory index are ideal cache targets. → *its own ADR.*
- **Error taxonomy:** ThrottlingException 429, ModelTimeoutException 408, ModelNotReadyException 429 (SDK auto-retries 5×), ServiceUnavailableException 503, InternalServerException 500 (all retryable); ValidationException 400, AccessDeniedException 403 (not retryable); ModelErrorException 424. → `NFR-BEDROCK-MAX-RETRIES` + exit 4.
- **VERIFIED model IDs (current GA):** Opus 4.6 = `anthropic.claude-opus-4-6-v1` (newest); Opus 4.5 = `anthropic.claude-opus-4-5-20251101-v1:0`; Sonnet 4.5 = `anthropic.claude-sonnet-4-5-20250929-v1:0`; Haiku 4.5 = `anthropic.claude-haiku-4-5-20251001-v1:0`. Cross-region inference-profile form `us.anthropic.claude-…` for availability. → **resolves deferred `NFR-MODEL-DEFAULT` exact id; pin in engine ADR.**
- **No managed web-search tool in Converse** (tool use is client-side only). → **CONFIRMS** the "delegate web search to headless claude" decision (was ⚠ unverified in § F, now verified TRUE). Bonus: Bedrock exposes Anthropic-native `bash_*`/`text_editor_*`/`memory_*` tool types but via the Anthropic Messages API format (a different mode than Converse) → alternative-considered in the tool ADR; we stay on Converse for model-agnosticism.
- **Also available, likely OOS v1 (note in ADRs):** Guardrails (`guardrailConfig`), structured outputs (`outputConfig.textFormat`), `requestMetadata` tagging, service tiers, server-side tool use (Lambda/AgentCore Gateway).

#### A.2 Bedrock platform — VERIFIED facts (read 2026-06-14 from what-is-bedrock / apis / inference / api-keys / inference-prereq / inference-reasoning)
- **Two endpoints, five inference APIs:**
  - `bedrock-runtime.{region}.amazonaws.com` → **Converse**, **Invoke**, + Messages/Chat-Completions. (AWS-native; our endpoint.)
  - `bedrock-mantle.{region}.amazonaws.com` → **Responses API** (recommended for agentic apps; *stateful*, built-in tools incl. **search + code-interpreter**, multimodal), **Chat Completions**, **Messages API** (Anthropic-native). OpenAI/Anthropic-compatible.
  - API-decision matrix says: "consistent interface across all models" → **Converse** (our choice, validated). Converse = `bedrock-runtime`, model-agnostic, client-side tools.
- **⚠ Responses API alternative (note in engine ADR as alternative-considered):** `bedrock-mantle` Responses API offers *server-side stateful conversations* + *built-in search/code-interpreter*. We deliberately DON'T use it: (a) server-side state is the opposite of our client-owned event-log/resume/observability design; (b) OpenAI-compat format, not the unified Converse interface; (c) ties to specific models. But its built-in search is a real counterpoint to our "delegate web search to headless claude" — record the tradeoff. v1 stays Converse + delegate.
- **100+ models, 6+ providers** (Amazon Nova, Anthropic Claude, DeepSeek, Kimi/Moonshot, MiniMax, OpenAI GPT). Confirms the provider-agnostic-by-design / Claude-only-v1 stance has real runway.
- **⚠ MODEL ID UPDATE:** newest GA Opus is **Claude Opus 4.7** (`anthropic.claude-opus-4-7`), GA ~2026-04 — *newer* than the Opus 4.6 I pinned earlier from the prompt-caching table (docs there lag). Quickstart code uses `anthropic.claude-opus-4-7`. Inference-profile form `us.anthropic.claude-…`. → **engine ADR pins exact id at impl time** (availability moves fast; 4.8 may appear). NFR-MODEL-DEFAULT note updated to 4.7.
- **CREDENTIAL PATHS — API keys / bearer token exist but are DELIBERATELY UNUSED (RD-11 final, user-directed 2026-06-15):** Bedrock supports bearer-token auth via `AWS_BEARER_TOKEN_BEDROCK` (short-term ≤12h / long-term), plus a Java token generator (`software.amazon.bedrock:aws-bedrock-token-generator:1.1.0`). **Decision history:** 2026-06-14 set RD-11 = bearer→profile→chain (3-tier); **2026-06-15 REVERSED to SigV4-only: profile → default chain, bearer explicitly IGNORED even if `AWS_BEARER_TOKEN_BEDROCK` is set.** Rationale: a stray bearer token from another account would silently auth this tool against the wrong AWS account — a footgun + production-safety hazard. **Implementation must explicitly prevent SDK-native bearer pickup** (testable; AC-8.8) and warn if the env var is present. → ADR-0011 (rewritten).
- **IAM actions for read/invoke-only (grounds the operations/IAM ADR + AWS-safety):** Converse needs `bedrock:InvokeModel`; ConverseStream needs `bedrock:InvokeModelWithResponseStream`; inference profiles need `bedrock:GetInferenceProfile` + `bedrock:ListInferenceProfiles`. Managed policy `AmazonBedrockFullAccess` covers it (broad); least-privilege = just the Invoke + inference-profile reads. NO create/delete/update verbs — confirms our read/invoke-only posture (AWS-safety rule).
- **Reasoning (extended thinking) is model-gated** and adds latency + output tokens; "not all models allow configuring reasoning output-token budget." → capability-layer feature-detects it (OQ-J); Claude supports it.
- **Platform features confirmed OOS v1 (note + defer):** model customization/fine-tuning, Provisioned Throughput, imported/custom models, Prompt Management, Guardrails, Knowledge Bases, Agents/Flows, batch inference, evaluation, CloudTrail logging (note: all API calls logged in CloudTrail; bearer keys not logged).
- **Reading scope note:** read the orienting/architecture-relevant pages (overview, apis, endpoints, inference, reasoning, api-keys, inference-prereq). Did NOT exhaust every sub-page (KBs, Agents, Guardrails depth, customization, evaluation) — those are OOS for a local CLI coding agent. Raw Converse capture in `research/`; can deep-read specific areas on demand.

#### A.3 Responses API + web-search investigation — VERIFIED facts (read 2026-06-14: bedrock-mantle, tool-use-server-side, models-api-compatibility) — ⚠ CONTRADICTS the "Responses API for web search" plan
User asked: "Converse for everything, Responses API only for web search + fetch." Verification found three blockers + one ambiguity:
- **⚠ BLOCKER 1 — Responses API does NOT support Claude.** The API-compatibility matrix shows the **Responses** column = NO for *every* Anthropic Claude model (Opus 4.1/4.5/4.6/4.7/4.8, Sonnet 4/4.5/4.6, Haiku 4.5, Fable 5, Mythos 5). Responses = YES only for **OpenAI** models (GPT-5.5, GPT-5.4, gpt-oss-20b/120b). So "web search via Responses API" = web search runs on a **non-Claude (OpenAI) model** → a deliberate exception to the Claude-only-v1 constraint (defensible: search is auxiliary, not the coding brain — but must be explicit).
- **⚠ BLOCKER 2 — different endpoint + SDK + auth.** Responses API is on `bedrock-mantle.{region}.api.aws` (NOT `bedrock-runtime`), uses the **OpenAI SDK** (`OPENAI_BASE_URL`+`OPENAI_API_KEY`) or HTTP, and requires a **Bedrock API key / bearer token** (the path we just added in RD-11). Converse (bedrock-runtime, AWS SDK v2, SigV4) is a separate client. So "Responses for web search" = a **second model-client** in the codebase + the bearer cred path is now load-bearing, not optional.
- **⚠ AMBIGUITY — is there even a managed web_search tool?** The `apis.html` overview *claims* Responses offers "built-in tool use (search, code interpreter)." But the **detailed** server-side-tools page documents only: AWS built-ins **`notes` + `tasks`** (gpt-oss-20b/120b only), **custom Lambda** (MCP/JSON-RPC), and **AgentCore Gateway**. **No `web_search` built-in is documented on Bedrock Mantle.** OpenAI's own Responses API has `web_search_preview`, but I could NOT confirm Bedrock-Mantle exposes it. → MUST verify before committing to this path; do not assert.
- **⚠ BLOCKER 3 — statefulness.** Responses API stores state 30 days by default (`store=true`); for a stateless search call we'd set `store=false` (no retention). Minor but must be set explicitly.
- **⚠ MODEL ID UPDATE (supersedes § 6.A.2):** API-compat table lists **Claude Opus 4.8** (`model-card-anthropic-claude-opus-4-8`) as GA — newest Opus (newer than 4.7). Also GA: Claude Fable 5, Mythos 5. Coding-specialized models exist (Qwen3 Coder, Mistral Devstral 2) but we're Claude-only v1. → NFR-MODEL-DEFAULT note: "current newest Opus (4.8 as of 2026-06-14)"; pin exact id at impl time.
- **DECISION (user, 2026-06-14): web-search backend = headless Claude CLI delegate** (reverts to the original brainstorm plan after the Responses-API investigation surfaced 3 blockers). `web_search`/`web_fetch` are **Converse client-side tools with a swappable backend**; v1 backend = a **constrained `claude -p` subprocess** (print-mode, tools restricted to web-only, timeout `NFR-NET-WEBLOOKUP-TIMEOUT`), returns summarized text. Stays Claude end-to-end; no 2nd SDK/endpoint/bearer dependency forced. → delegation ADR.
  - **Responses API = alternative-considered, REJECTED** (record in delegation + engine ADRs): no Claude support (OpenAI models only), separate endpoint (`bedrock-mantle`) + OpenAI SDK + bearer auth, and no confirmed managed `web_search` tool. Properly investigated, not hand-waved.
  - **Bearer-token cred path (RD-11 tier 1) is RETAINED** — it's a valid general Bedrock auth option the user chose, just no longer *forced* by a Responses dependency. Converse honors `AWS_BEARER_TOKEN_BEDROCK` too.
- **Primary coder**, not orchestrator — our loop is the coder; external CLIs are capability delegates. → *ADR: delegation model / design principle.*
- **Principle: "own your core, rent your periphery."** → *overview / architecture design principle.*

### B. Code comprehension & verification
- **Pure agentic search** (grep/glob/read on demand). **No AST, no JDT/LSP** — the LLM reads code as text. → *ADR: code representation (text-primary, no static analysis).*
- **Build tools are ground truth.** `mvn compile` / `mvn test` via command execution verify correctness; no pre-compile validation. → *ADR / architecture (verification spine).*
- **Language-agnostic core** falls out of dropping AST/LSP — comprehension is text+grep, verification is configured commands. → *design principle.*

### C. Command execution & safety (the spine)
- **Command execution is the spine** — safety (permission gate), context (output is the biggest token producer), and learning (exit code = reward signal) all converge on it. → *ADR: command execution as verification + safety spine.*
- **Two-layer command model:** named commands in config (`build_command`, `test_command`, …) + generic gated `run_command` escape hatch. Structured result `{command, exit_code, stdout, stderr, duration, truncated}`. → *architecture + config schema.*
- **`PermissionMode` enum (4 values):** `UNRESTRICTED`, `READ_ONLY`, `ASK_EVERY_TIME`, `ASK_ONCE_THEN_REMEMBER`. Reads auto-approved; writes/exec gated. Every tool routes through one permission gate. → *ADR: permission model + data-model enum.*

### D. Persistence, context & lineage
- **User-global store** `~/.codingagent/`, keyed by repo (git-remote URL else normalized path). **JSONL per conversation = single source of truth.** Survives `git clean`. → *ADR: persistence + data-model.*
- **Storage tree:** `config.yaml`; global `memory/` (INDEX.md + `<slug>.md`); `projects/<repo-key>/` → `project.yaml`, `sessions/<id>.jsonl` + `<id>.meta.json`, `memory/`, `lineage.json`. → *data-model / operations.*
- **Conversation tree** data model: a Conversation has a `parent` + edge-type (`derived-from`, `spawned-by`). Main session, compacted continuation, and sub-agent are the same shape with one lineage edge. → *data-model.*
- **Event-sourced log:** append-only JSONL; every event (user msg, LLM text, thinking, `toolUse`, `toolResult`, sub-agent spawn, compaction marker, token counts, timings). **Resume = replay events; debug = read same file unfiltered.** → *ADR: event sourcing + data-model.*
- **Outcome fields on events (rung-2):** success/fail/iterations/accept-reject captured from day one → free RL-substrate insurance. → *data-model.*
- **Compaction:** auto at token threshold **+** manual command; summarize → seed fresh derived conversation → archive old (preserved) → link lineage. **Compaction is the learning-harvest trigger** (reflect → distil durable learnings before archiving). → *architecture + state-machine.*
- **Output disposal:** big tool/command outputs truncated/summarized/stored-and-referenced before hitting the window. "Every token-producer needs a disposal story." → *architecture.*
- **Models:** configurable, default a current Claude on Bedrock; a sub-agent may run a different/cheaper model than its parent. → *config + architecture.*
- **Provider-agnostic by design, Claude-only in v1 (added 2026-06-14, user direction — refined):** the model boundary is *designed* to reach any Converse-compatible Bedrock provider (Nova/Llama/Mistral…) via a **feature-detected model-capability layer**, but **v1 targets, validates, and ships CLAUDE ONLY**. Non-Claude Bedrock providers = architectural seam, post-v1 (not a tested path). Non-Bedrock providers fully OOS. → `NFR-MODEL-PROVIDER`, OQ-J, **model-provider ADR**. *Tension the capability layer isolates: core loop is provider-agnostic ~free (Converse), but extended-thinking/signatures + prompt-cache minimums + top_k are Claude-specific. Because v1 is Claude-only, the layer can be thin (a seam + a Claude profile) — full multi-provider profiles are post-v1, bounding validation cost now.*
- **Sub-agents:** nested instance of our loop, isolated context, returns a summary (context isolation). Start with one; config for N; may run in parallel. → *architecture.*
- **Memory re-read fresh on resume** — live side-channel, not baked into a transcript; a resumed session sees latest learnings. → *architecture.*

### E. Memory & learning
- **Four-type memory framing:** working (context window) / episodic (event log) / semantic (learning store) / procedural (playbooks → RL later). → *overview / architecture.*
- **Two memory tiers:** global `~/.codingagent/memory/` + project `projects/<repo-key>/memory/`. **Markdown-per-learning + INDEX.md one-liners, selective load** into system prompt; full file pulled on demand. **No embeddings/vector DB for v1.** → *ADR: memory model + data-model.*
- **Write policy:** explicit ("remember that…") + agent-proposed-and-approved. **No auto-extraction in v1** (poisoning risk) — deferred to a later stage. → *architecture / operations.*
- **Memory failure modes + mitigations:** poisoning (human-editable/deletable, provenance), staleness (cite the symbol so re-verifiable; prefer durable conventions over volatile line numbers), index bloat (project-scoping, pruning, retrieval later). → *operations / ADR.*
- **Memory writes are events** — logged, auditable, rollback-able; gives a free "learning history." → *data-model.*
- **RL ladder:** v1 = rung 1 (curated semantic memory) + rung 2 (outcome capture). Rung 3 (trajectory/few-shot retrieval) later; rungs 4–5 (reward model / DPO / weight RL) future-work, gated on proven need. → *overview / future-work.*

### F. External delegation
- **Web search/summary via headless Claude CLI delegate** (rent the periphery). **✅ VERIFIED + RE-CONFIRMED 2026-06-14:** Converse has no managed web-search tool; the Responses API alternative was investigated and rejected (no Claude support, separate endpoint/SDK/bearer auth, no confirmed `web_search` tool — see § 6.A.3). v1 backend = constrained `claude -p` subprocess. `web_search`/`web_fetch` stay Converse client-side tools with a swappable backend. → *ADR: delegation (with Responses API as documented alternative-considered) + operations.*
- **Delegate must be constrained:** run print-mode, output text, tools restricted to web-only, timeout — don't let a foreign agent loose in the repo. Internal sub-agent vs external delegate are two backends behind one delegation seam. → *architecture / operations.*

### G. Staging (informs Phase 4 milestones — not binding yet)
- Stage 0: Bedrock Converse + loop + read/write/run + permission gate + event-log persistence + CLI (brownfield).
- Stage 1: resume + agentic search + build/test command loop + output disposal.
- Stage 2: compaction/derivation + sub-agent (one, config N) + semantic memory (tiers, index+load, explicit & proposed writes).
- Stage 3: greenfield spec-driven workflow.
- Stage 4: external-CLI delegation (web search via headless claude).
- Stage 5 (future): reflection sub-agent (auto-harvest) + trajectory retrieval (rung 3).

### H. Open / future-work flags
- **MCP-compatible tool registry** — future-work, not v1.
- **Container/Docker sandboxing** — future-work; v1 safety = permission gate.
