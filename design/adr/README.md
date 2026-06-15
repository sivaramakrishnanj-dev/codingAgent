# Architecture Decision Records

One file per decision, `NNNN-<slug>.md`, using `0000-template.md`. ADRs emerge during Phase 2 (architecture) and are referenced from `02-architecture.md` § 1.2 (the `ADR` column) and § 9 (the queue). Status moves `proposed → accepted`, and `superseded` if a later ADR replaces one.

## Index

| ADR | Title | Status | Resolves | Review |
|-----|-------|--------|----------|--------|
| [0001](0001-engine-sdk-converse-owned-loop.md) | Engine: AWS SDK v2 + Converse, owned loop | accepted | C2, C7, OQ-A | adr-batch1-r1 |
| [0002](0002-model-provider-capability-layer.md) | Model-provider abstraction + capability layer | accepted | C4, C5, OQ-J | adr-batch1-r1 |
| [0003](0003-command-execution-spine.md) | Command execution as the verification + safety spine | accepted | C10 | adr-batch1-r1 |
| [0004](0004-permission-model.md) | Permission model (4 modes, Class R/X, denylist, match) | accepted | C8, OQ-E | adr-batch1-r1 |
| 0005 | Persistence: event-sourced JSONL + conversation tree | _planned (batch 2)_ | C14, C15 | — |
| 0006 | Context management: compaction-with-derivation + output disposal | _planned (batch 2)_ | C6, OQ-D, OQ-I | — |
| 0007 | Memory: two-tier markdown + index, curated writes | _planned (batch 2)_ | C12, C16, OQ-F | — |
| 0008 | Web delegation via constrained headless Claude (Responses alt) | _planned (batch 2)_ | C11 | — |
| 0009 | Configuration model + precedence | _planned (batch 2)_ | C17, OQ-G | — |
| 0010 | Sub-agent orchestration + isolation | _planned (batch 2)_ | C13, OQ-C | — |
| 0011 | Bedrock credential resolution (bearer→profile→chain) | _planned (batch 2)_ | C4 | — |
| 0012 | Greenfield workflow formality | _planned (batch 2)_ | C3, OQ-B | — |
