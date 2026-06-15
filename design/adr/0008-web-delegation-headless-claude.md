---
adr: 0008
title: Web delegation via constrained headless Claude CLI
status: accepted
date: 2026-06-15
deciders: [user, designer]
phase: 2-architecture
supersedes: []
superseded_by: []
related: [ADR-0004, ADR-0003, ADR-0001]
spec_refs: [US-11, AC-11.1, AC-11.2, AC-11.3, AC-11.4, RD-6, NFR-NET-WEBLOOKUP-TIMEOUT]
---

# ADR-0008 — Web delegation via constrained headless Claude CLI

## Status

accepted (2026-06-15)

## Context

The agent needs current web information beyond its training cutoff (US-11). Converse has **no managed web-search tool** (§ 6.A.1), so search must come from somewhere we wire up. We investigated the Bedrock **Responses API** (the obvious "stay in Bedrock" option) and found it unsuitable (§ 6.A.3): it supports **OpenAI models only — not Claude**, lives on a different endpoint (`bedrock-mantle`) with the OpenAI SDK + bearer auth, and has **no confirmed managed `web_search` tool** (only `notes`/`tasks`, custom Lambda, AgentCore Gateway). The user chose the **headless Claude CLI delegate** (the original brainstorm plan). This ADR fixes how that delegate is invoked and constrained.

Key principle from brainstorming: a "headless claude" is a *full agent*, not a narrow search function. For a search-and-summarize job it must be **constrained** to do only that and hand back text — otherwise our "search tool" is a second agent loose in the repo.

## Decision

**We will back the `web_search` / `web_fetch` tools with a constrained headless Claude CLI subprocess, behind a swappable backend interface.**

- **Tool surface (unchanged seam).** `web_search(query)` and `web_fetch(url)` are **Converse client-side tools** (ADR-0001) in the registry (C7). Their *backend* is an interface (`WebLookupBackend`); v1's implementation shells out to headless Claude. Swapping the backend later (direct search API, etc.) touches one class, not the tool contract — the delegation seam the brainstorm designed.
- **Invocation.** Spawn `claude -p "<task>" --output-format text` (or equivalent print/headless flags) via the Command Executor's subprocess machinery (ADR-0003), with the lookup task as a constrained prompt that asks for a summarized result.
- **Constraints (the "don't let a foreign agent loose" rule).** The subprocess is launched **sandboxed**: restricted to web/search tools only (no file write, no arbitrary exec in the repo), CWD set to a scratch/temp dir (not the workspace), and a hard **timeout** `NFR-NET-WEBLOOKUP-TIMEOUT` (120 s). It returns **text** to the parent; nothing it does touches the repo or our event log except the summarized result we capture.
- **Permission class (RD-6, AC-11.2).** `web_search`/`web_fetch` are **Class X** (subprocess + network) — gated by the active mode, and **denied in `READ_ONLY`**. So a web lookup prompts in the asking modes and is blocked read-only.
- **Failure (AC-11.3).** If the delegate is absent (not on `PATH`), errors, or times out → return a failure result to the loop; the agent **reports it, never fabricates** an answer.
- **Logging (AC-11.4).** The invocation and the summarized result are logged as events (ADR-0005) — `tool_use`/`tool_result` — so lookups are auditable like any tool.

## Consequences

**Positive**
- Stays Claude end-to-end; no second model SDK/endpoint/bearer dependency forced into our code (vs the Responses path).
- Reuses the subprocess machinery (ADR-0003) and the permission gate (ADR-0004) — no new infra.
- The swappable backend keeps the door open for a direct search API later without touching the tool contract.
- Renting the periphery (the CLI's built-in, already-authed web search) instead of building search+scrape+summarize ourselves.

**Negative / costs**
- **External dependency**: the headless Claude CLI must be installed on `PATH` and authenticated — an operating-envelope requirement (`01-overview.md` § 8). Absent ⇒ web lookup unavailable (degrade, don't crash).
- Search results are the delegate's judgment, not ours (the "control vs rent" tension named in brainstorming) — acceptable for an auxiliary capability, not the coding brain.
- Subprocess + cold-start latency per lookup; bounded by timeout.

**Neutral / follow-ons**
- The `WebLookupBackend` interface is the extension point; alternative backends (Brave/Tavily/SerpAPI, or a future managed Bedrock search) are post-v1 swaps.
- Constraining the delegate (tool restriction, scratch CWD) depends on the headless CLI's flags — implementation confirms the exact invocation at Phase 5.

## Alternatives considered

| Option | Summary | Why not chosen |
|--------|---------|----------------|
| **Bedrock Responses API** (`bedrock-mantle`) | "Stay in Bedrock" managed search | No Claude support; separate endpoint+SDK+bearer; no confirmed `web_search` tool (§ 6.A.3). Recorded here as the investigated, rejected alternative |
| **Direct search API** (Brave/Tavily/SerpAPI) + HTTP fetch + summarize | Full control, stays Claude/Converse | More to build (API integration + scrape + summarize); external key+dep. Kept as a future swappable backend, not v1 |
| **Custom Lambda via Responses** | Works within AWS | Heavy (deploy + maintain a Lambda) for a local CLI; still the Responses/OpenAI-model path |
| **No web lookup in v1** | Smallest scope | US-11 is an accepted requirement; the delegate is cheap given the CLI already exists |

## Notes

- Responses-API rejection rationale: `design-progress.md` § 6.A.3 (verified 2026-06-15).
- The constrain-the-delegate discipline (print-mode, web-only tools, scratch CWD, timeout) is the load-bearing safety property — a headless agent with repo write access would bypass our permission gate entirely.
- Exact `claude -p` flags + sandboxing mechanism confirmed at implementation; the backend interface insulates the rest of the system from those specifics.
