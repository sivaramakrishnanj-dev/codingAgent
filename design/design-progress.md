---
doc: design-progress
last_updated: 2026-06-14
last_updated_at_commit: e03b032
current_phase: 2
current_sub_phase: 2-overview
current_sub_phase_status: not-started
next_action: Begin Phase 2 — draft 01-overview.md (purpose, problem, scope in/out, actors for design-reader, C4 L1 Mermaid context diagram, external-contracts prose, quality-attributes table from NFRs, operating envelope, open questions OQ-A..N, future-work, reading-onward pointers). Verify the exact current Bedrock model id (WebFetch) before/as part of the engine ADR, not here.
next_artifact_to_touch: design/01-overview.md
---

# Design progress — codingAgent

## 1. Where we are

Phase 1a (personas + user stories) is **resolved** — approved by user ("good to go"), reviewed in `reviews/2026-06-14-requirements-1a-r1.md`, committed as the project's first commit. 3 personas (P1 Developer, P2 Operator, P3 The Agent) and 21 user stories (US-1..US-21) are baselined.

Phase 1b (EARS acceptance criteria) is **resolved** — approved by user ("good to go"), reviewed in `reviews/2026-06-14-acceptance-criteria-1b-r1.md`. Baselined: RD-1..RD-10 behavioral defaults, CLI exit-code seed (0/1/2/3/4/5/130), 80 EARS-tagged ACs (AC-1.1..AC-21.4) across US-1..US-21, and a 7-symbol `NFR-*` table for 1c. `ASK_ONCE_THEN_REMEMBER` resolved as RD-1 (tool + normalized prefix) with RD-2 destructive-denylist carve-out — the §2 parked question is now closed.

**Phase 1 is complete.** All three sub-phases resolved: 1a (3 personas, 21 stories), 1b (10 RD defaults, exit-code seed, 80 EARS ACs), 1c (all NFRs pinned). 1c approval also folded in a user-directed AWS-credential requirement (RD-11, AC-8.6–8.8, NFR-AWS-CREDENTIALS): named-profile-first, fall back to the AWS default credential chain, fail to exit 4 only if neither yields usable credentials.

Now entering **Phase 2 — Design**. First artifact is `01-overview.md`. The brainstorm pre-explored most Phase 2 ground — see § 6 below; that material becomes the overview, architecture, and ADRs. Per-file review in Phase 2.

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
- AWS credentials: named-profile-first, fall back to AWS default credential provider chain, fail (exit 4) only if neither resolves (RD-11, AC-8.6–8.8, NFR-AWS-CREDENTIALS). Read/invoke-only Bedrock — no AWS write verbs. **→ needs a Phase 2 ADR for SDK v2 provider wiring** (`ProfileCredentialsProvider` → `DefaultCredentialsProvider`).
- All NFR numeric values pinned in 1c: Opus 4.x default (`NFR-MODEL-DEFAULT`, exact id Phase 2), 5 verify retries (`NFR-VERIFY-MAX-ITERATIONS`), 1 sub-agent (`NFR-SUBAGENT-MAX`), 0.85 compaction (`NFR-CONTEXT-COMPACT-THRESHOLD`), 16 KB output cap (`NFR-OUTPUT-MAX-INLINE`), 3 Bedrock retries (`NFR-BEDROCK-MAX-RETRIES`), Java 21 (`NFR-PLAT-JAVA`), 80% coverage gate (`NFR-TEST-COVERAGE`).

## 4. Recovery notes

_(none yet)_

## 5. Landed — historical

- 1a-user-stories — resolved (review: `reviews/2026-06-14-requirements-1a-r1.md`) — `19cbe08`
- 1b-acceptance-criteria — resolved (review: `reviews/2026-06-14-acceptance-criteria-1b-r1.md`) — `96f754b`
- 1c-nfrs — resolved, **Phase 1 closed** (review: `reviews/2026-06-14-nfrs-1c-r1.md`) — `e03b032`

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
