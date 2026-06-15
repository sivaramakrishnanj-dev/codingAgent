# Architecture Decision Records

One file per decision, `NNNN-<slug>.md`, using `0000-template.md`. ADRs emerge during Phase 2 (architecture) and are referenced from `02-architecture.md` § 1.2 (the `ADR` column) and § 9 (the queue). Status moves `proposed → accepted`, and `superseded` if a later ADR replaces one.

## Index

| ADR | Title | Status | Resolves | Review |
|-----|-------|--------|----------|--------|
| [0001](0001-engine-sdk-converse-owned-loop.md) | Engine: AWS SDK v2 + Converse, owned loop | accepted | C2, C7, OQ-A | adr-batch1-r1 |
| [0002](0002-model-provider-capability-layer.md) | Model-provider abstraction + capability layer | accepted | C4, C5, OQ-J | adr-batch1-r1 |
| [0003](0003-command-execution-spine.md) | Command execution as the verification + safety spine | accepted | C10 | adr-batch1-r1 |
| [0004](0004-permission-model.md) | Permission model (4 modes, Class R/X, denylist, match) | accepted | C8, OQ-E | adr-batch1-r1 |
| [0005](0005-persistence-event-sourcing.md) | Persistence: event-sourced JSONL + conversation tree | accepted | C14, C15 | adr-batch2-r1 |
| [0006](0006-context-management-compaction-disposal.md) | Context management: compaction-with-derivation + output disposal | accepted | C6, OQ-D, OQ-I | adr-batch2-r1 |
| [0007](0007-memory-two-tier-markdown.md) | Memory: two-tier markdown + index, curated writes | accepted | C12, C16, OQ-F | adr-batch2-r1 |
| [0008](0008-web-delegation-headless-claude.md) | Web delegation via constrained headless Claude (Responses alt) | accepted | C11 | adr-batch2-r1 |
| [0009](0009-configuration-model-precedence.md) | Configuration model + precedence | accepted | C17, OQ-G | adr-batch2-r1 |
| [0010](0010-subagent-orchestration-isolation.md) | Sub-agent orchestration + isolation (in-process) | accepted | C13, OQ-C | adr-batch2-r1 |
| [0011](0011-bedrock-credential-resolution.md) | Bedrock credential resolution (bearer→profile→chain) | accepted | C4 | adr-batch2-r1 |
| [0012](0012-greenfield-workflow-formality.md) | Greenfield workflow formality (full spec-driven) | accepted | C3, OQ-B | adr-batch2-r1 |
