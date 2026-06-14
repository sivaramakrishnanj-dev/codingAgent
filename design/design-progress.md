---
doc: design-progress
last_updated: 2026-06-14
last_updated_at_commit: acf7818
current_phase: 1
current_sub_phase: 1b-acceptance-criteria
current_sub_phase_status: not-started
next_action: Draft Phase 1b — 3-8 EARS acceptance criteria per user story (US-1..US-21), with a resolved-behavioral-defaults table at the top and symbolic NFR-* references (pinned later in 1c). Pin ASK_ONCE_THEN_REMEMBER matching semantics here.
next_artifact_to_touch: design/00-requirements.md
---

# Design progress — codingAgent

## 1. Where we are

Phase 1a (personas + user stories) is **resolved** — approved by user ("good to go"), reviewed in `reviews/2026-06-14-requirements-1a-r1.md`, committed as the project's first commit. 3 personas (P1 Developer, P2 Operator, P3 The Agent) and 21 user stories (US-1..US-21) are baselined.

Next move is **Phase 1b — EARS acceptance criteria**: 3–8 ACs per user story, each tagged with one EARS template, symbolic `NFR-*` references (pinned numerically in 1c), and a resolved-behavioral-defaults table at the top. Must pin `ASK_ONCE_THEN_REMEMBER` matching semantics (§ 2) while writing US-9/US-10 ACs.

## 2. Deferred decisions

- **Project name** — working name is `codingAgent` (the directory). Not yet decided as the product name. Resolve before/at Phase 4 config-generation (`project_name` in `.kiro/spec-driven.yaml`).
- **`ASK_ONCE_THEN_REMEMBER` matching semantics** — what counts as "the same command" for approval reuse (exact string / prefix glob like `mvn *` / tool-type)? Resolve in Phase 1b (acceptance criteria) or the permission-model ADR (Phase 2).
- **Parallel sub-agent execution detail** — v1 starts with one sub-agent, config for N; whether N>1 run concurrently vs sequentially-with-isolation is a Phase 2 architecture detail. Capability captured in US-17.
- **Compaction "matching"/trigger threshold value** — numeric token threshold is a Phase 1c NFR, not 1a.

## 3. Carry-forwards

Cross-phase scope facts established during brainstorming that later phases must honour (each cites the symbol that pins it):

- Java is the first and only code-generation target for v1 (US-1..US-5 scope; "non-Java targets" is an OOS-list entry in `00-requirements.md`). The *core* is language-agnostic but only a Java/Maven config ships.
- AWS Bedrock is the only model backend for v1 (OOS-list entry "non-Bedrock providers").
- Single-user local CLI, one repo at a time (US-6; OOS-list entries for multi-user/daemon and IDE/GUI).
- Memory write policy is **explicit + agent-proposed-and-approved** for v1 (US-12 explicit; US-21 proposed; OOS-list entry "auto-extraction").

## 4. Recovery notes

_(none yet)_

## 5. Landed — historical

- 1a-user-stories — resolved (review: `reviews/2026-06-14-requirements-1a-r1.md`) — `acf7818`

## 6. Phase 2 carry-forward material (pre-explored ADRs & mechanisms)

The brainstorm pre-explored a lot of **Phase 2 (architecture/ADR)** ground. None of it belongs in Phase 1 (requirements = *what/for whom*, not *how*). Captured here verbatim so it resurfaces as ADRs/architecture and **nothing is lost**. Each item notes its destined artifact.

### A. Engine & role
- **AWS SDK for Java v2, Bedrock Converse API, direct** (no LangChain4j/Spring AI). We own the loop: build request → parse text + `toolUse` → dispatch tools → append `toolResult` → repeat. → *ADR: engine / SDK choice.*
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
- **Web search/summary via headless claude/kiro-cli delegate** (rent the periphery). **⚠ Unverified claim to check in Phase 2:** that Bedrock Converse has *no managed web-search tool* (the reason we delegate). Verify via WebFetch before asserting in an ADR. → *ADR: delegation + operations.*
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
