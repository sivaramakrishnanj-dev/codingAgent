---
slug: integration-tests-need-profile
tier: PROJECT
created: "2026-06-17T10:30:00Z"
originSession: "2026-06-17T09-00-00-abc123"
why: "Discovered mvn verify failed until -P integration was passed; approved by developer."
status: active
---

Integration tests in this repo are tagged `@Tag("integration")` and excluded from the
default Surefire profile. Run them with `mvn verify -P integration` (see `05-operations.md`
build conventions). Plain `mvn test` runs unit tests only — do not conclude integration
coverage from a green `mvn test`.
