---
doc: tasks-progress
last_updated: 2026-06-18
last_updated_at_commit: 0d24818
total_resolved_count: 2

last_resolved:
  task: T-0.2
  title: "Config model + resolver (layered precedence, fail-fast exit 2)"
  resolved_at: 2026-06-18
  commit: 0d24818
  iterations: { task_builder: 1 }
  dcrs_consumed: []

in_flight: null
---

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
