---
doc: tasks-progress
last_updated: 2026-06-18
last_updated_at_commit: pending
total_resolved_count: 3

last_resolved:
  task: T-0.3
  title: "Credential resolution (profile → default chain; ignore bearer; SigV4 client)"
  resolved_at: 2026-06-18
  commit: b63349c
  iterations: { task_builder: 1 }
  dcrs_consumed: []

in_flight:
  task: T-0.4
  phase: TASK_BUILDER
  loop_iter: 1
  round: null
  last_handoff_kind: null
  last_handoff_status: null
  last_review_file: null
  started_at: 2026-06-18T15:05:00+05:30
  last_updated_at: 2026-06-18T15:05:00+05:30
---

## In-flight

- task: T-0.4
  phase: TASK_BUILDER
  loop_iter: 1
  round: null
  last_handoff_kind: null
  last_handoff_status: null
  last_review_file: null
  open_action_items_for_implementer: []
  open_action_items_for_tester: []
  files_in_working_tree: []
  dcrs_consumed: []
  started_at: 2026-06-18T15:05:00+05:30
  last_updated_at: 2026-06-18T15:05:00+05:30

## Resolved tasks

## T-0.1 — Project skeleton: Maven, Java 21, com.srk.codingagent packages, JUnit 5, shaded-jar build
- commit: 47de139
- review: design/reviews/code/T-0.1-r1.md
- resolved: 2026-06-17
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: Walking-skeleton scaffold; mvn clean verify green (6 tests), empty CLI launches and exits 0. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor (non-blocking). (Resolution commit recorded as 47de139 — the prior fd2412c reference predated a history rewrite; HEAD title matches and artifacts verified present.)

## T-0.2 — Config model + resolver (layered precedence, fail-fast exit 2)
- commit: 0d24818
- review: design/reviews/code/T-0.2-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0009 layered first-wins precedence (flags>project>global>defaults) into immutable ResolvedConfig; fail-fast exit 2 naming the offending key; SnakeYAML SafeConstructor loader; JaCoCo gate raised to 0.80 (first business-logic task). 90 tests green under mvn clean verify (~97.9% config coverage). CT-SCH-13/14, CT-EX-1 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 2 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: finalize exact Opus id at T-0.5; D2: IDE artifacts not staged).

## T-0.3 — Credential resolution (profile → default chain; ignore bearer; SigV4 client)
- commit: b63349c
- review: design/reviews/code/T-0.3-r1.md
- resolved: 2026-06-18
- context_mode: narrow
- iterations: { task_builder: 1 }
- dcrs_consumed: []
- notes: ADR-0011 SigV4-only two-tier resolution (named profile -> default chain) under com.srk.codingagent.model.credentials; explicit bearer-ignore+warn (AC-8.8/INV-16); typed CredentialResolutionException -> exit 4 (MODEL_BACKEND) naming paths attempted (AC-8.9); inspectable SigV4 client seam (BedrockClientFactory) for T-0.5. AWS SDK v2 bedrockruntime pinned 2.46.7 (2.46.10 no longer resolves; ADR-0001 directed latest-stable). 114 tests green under mvn clean verify (~95.3% bundle coverage); no live AWS calls (injected SDK seams). CT-INV-13, CT-EX-2 satisfied. Self-checks: oracle-traceability=passed, reuse=passed. 1 Minor, 1 Nit (non-blocking). 2 Discussion items (D1: SDK pin 2.46.7; D2: exit-4 CLI dispatch deferred to loop task).
