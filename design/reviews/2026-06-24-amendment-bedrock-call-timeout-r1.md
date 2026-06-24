---
review_id: amendment-bedrock-call-timeout-r1
phase: amendment
triggered_by: T-4.6
request_id: DCR-4
raised_by: spec-driven-implementer
kind: schema-update
approved_by_user_at: 2026-06-24T00:00:00+00:00
artifact: design/06-formal/resolved-config.schema.json, design/00-requirements.md, design/adr/0001-engine-sdk-converse-owned-loop.md, design/02-architecture.md, design/07-tasks.md, design/06-formal/contract-tests.md
reviewer: user (approved via coordinator preview)
status: approved
approved_in: pending
---

# Amendment review — DCR-4 close orphaned NFR-BEDROCK-CALL-TIMEOUT (full schema-update) (r1)

## Outcome

Approved (Option (a), user-approved 2026-06-24T00:00:00+00:00 via the coordinator preview). Executed
non-conversationally in amendment mode. `NFR-BEDROCK-CALL-TIMEOUT` — introduced in the 1c NFR table at
design time but, unlike its wired mirror `NFR-CMD-TIMEOUT`, referenced by **zero ACs**, backed by **no
config key**, recorded by **no ADR**, pinned by **no contract test**, and implemented by **no task** — is now
closed end-to-end across the five design surfaces so an M4 task can wire it into the Converse client. The NFR
is now referenced by **≥ 1 AC** (AC-8.10, AC-8.11) and **≥ 1 CT** (CT-SCH-16, CT-SCH-17). This unblocks the
future G3 live smoke test only; **no milestone gate (G0–G4) is touched, marked, or re-judged — G3 stays
OPEN.**

## Trigger

T-4.6 (raised by `spec-driven-implementer`). The implementer found that the Converse client built by
`BedrockClientFactory.create(...)` sets only `.credentialsProvider(...)` and `.region(...)` — no
`.overrideConfiguration(apiCallTimeout)` and no `.httpClientBuilder(...)` — so the AWS SDK applies its own
default timeouts rather than the NFR-pinned 10 s connect / 300 s response budget. The mirror command-side
budget `NFR-CMD-TIMEOUT` already had a config key (`commandTimeoutSeconds`, integer min 1, default 300) and
was wired from M0; the Bedrock call timeout never was. With no AC, config key, ADR, CT, or task pinning it,
the implementer could not wire it without first amending the design. The user approved the full schema-update
(Option (a)) over the doc-only alternative (Option (b)), so the NFR is implemented, not merely cross-referenced.

## Decision recorded (Option (a) — full schema-update)

1. **`resolved-config.schema.json`** — two new optional integer properties, each `minimum: 1` with a
   description citing `NFR-BEDROCK-CALL-TIMEOUT` + ADR-0001:
   - `bedrockCallConnectTimeoutSeconds` (default 10) — TCP/TLS connect; wired to the Apache httpClient
     `connectionTimeout`.
   - `bedrockCallResponseTimeoutSeconds` (default 300) — overall response budget incl. streaming/extended
     thinking; wired to `apiCallTimeout` and the Apache httpClient `socketTimeout`; counts toward the retry
     budget.
   `additionalProperties: false` preserved; neither key added to `required` (so the existing positive-corpus
   fixture stays valid).
2. **`00-requirements.md`** — folded the NFR into the US-8 config-key set: new **AC-8.10** (U — configurable
   connect/response budget, response covers streaming + extended thinking, counts toward
   `NFR-BEDROCK-MAX-RETRIES`) and **AC-8.11** (Ev — defaults connect 10 / response 300 when the keys are
   absent). The line-447 NFR row now cites the two config keys + AC-8.10/8.11 + ADR-0001. The NFR → AC
   coverage table gains a `NFR-BEDROCK-CALL-TIMEOUT` row plus a DCR-4 orphan-closure note. No Phase-1a user
   story edited.
3. **`adr/0001-engine-sdk-converse-owned-loop.md`** — new "Call timeouts" Decision bullet recording
   `apiCallTimeout` = response timeout + Apache `httpClientBuilder` (`software.amazon.awssdk:apache-client`)
   with `socketTimeout` = response / `connectionTimeout` = connect, both from `ResolvedConfig`; a
   Consequences follow-on on the Apache-client dependency choice; `spec_refs` += `NFR-BEDROCK-CALL-TIMEOUT`;
   `amended_by: [DCR-4]`.
4. **`02-architecture.md`** — C4 Model Client row records the timeout wiring (Refs +
   `NFR-BEDROCK-CALL-TIMEOUT`, ADR + ADR-0001); a new § 2 "Call timeouts on the Converse client" note mirrors
   the existing output-token-budget note; `amended_by` += DCR-4.
5. **`07-tasks.md`** — new M4 task **T-4.6** (size S, deps T-0.3) wiring the two timeouts with a
   CT-INV-13-style SUT-not-mocked wiring test; G-gate table untouched; task → US mapping US-8 += T-4.6;
   `amended_by` += DCR-4.
6. **`06-formal/contract-tests.md`** — new **CT-SCH-16** (positive: a config with both timeout keys
   validates) and **CT-SCH-17** (default-when-absent: resolver applies connect 10 / response 300); a note
   clarifying CT-SCH-16 needs no shared-fixture mutation and CT-SCH-17 asserts resolver behaviour (JSON-Schema
   `default` is documentary); § 6 traceability summary updated; `amended_by` += DCR-4.

Rejected alternative recorded: **Option (b) — doc-only reference** (cite `NFR-BEDROCK-CALL-TIMEOUT` from an
existing AC without adding config keys, leaving the NFR un-implemented). Rejected by the user because it
would leave the Converse client running on SDK-default timeouts — the live-call hang the orphan would
otherwise allow — without a wired, testable budget.

Plus `design/design-progress.md` (same-commit amendment lifecycle per shared.md § 9.2 / § 9.5): front-matter
briefly flipped to `amendment-DCR-4` then returned to `handed-off-to-coordinator`; § 1 DCR-4 prose; § 3
carry-forward (citing AC-8.10/8.11, the two config keys, ADR-0001, CT-SCH-16/17, T-4.6); § 5 Landed line for
DCR-4.

## Scope reviewed (files edited)

| File | Change |
|------|--------|
| `design/06-formal/resolved-config.schema.json` | Two optional integer keys (`bedrockCallConnectTimeoutSeconds` default 10, `bedrockCallResponseTimeoutSeconds` default 300), each min 1, NFR-citing description; `additionalProperties: false` preserved. |
| `design/00-requirements.md` | New AC-8.10 (U) + AC-8.11 (Ev) under US-8; line-447 NFR row cites the two keys + ACs + ADR-0001; NFR → AC coverage row + orphan-closure note; front-matter `amended_by` (+DCR-4)/`last_reviewed`/`review`. |
| `design/adr/0001-engine-sdk-converse-owned-loop.md` | "Call timeouts" Decision bullet; Apache-client Consequences follow-on; `spec_refs` += NFR-BEDROCK-CALL-TIMEOUT; `amended_by: [DCR-4]`. |
| `design/02-architecture.md` | C4 row timeout wiring (Refs/ADR updated); new § 2 call-timeout note; front-matter `amended_by` (+DCR-4). |
| `design/07-tasks.md` | New M4 task T-4.6; task → US mapping US-8 += T-4.6; front-matter `amended_by` (+DCR-4)/`last_reviewed`/`review`. |
| `design/06-formal/contract-tests.md` | New CT-SCH-16 (+) / CT-SCH-17 (+); CT-SCH-16/17 note; § 6 traceability updated; front-matter `amended_by` (+DCR-4)/`last_reviewed`/`review`. |

## Comments

| # | Severity | Comment | Resolution |
|---|----------|---------|------------|
| 1 | — | EARS for the new ACs: AC-8.10 is ubiquitous (`The agent shall apply … both configurable via …`); AC-8.11 is event-driven (`When … is not configured, the agent shall use the … defaults …`). | Satisfied. |
| 2 | — | Numeric for the NFR: connect 10 s / overall response 300 s, both integer ≥ 1, configurable — already pinned in 1c; the two config keys carry the same numeric defaults. No new symbolic NFR minted. | Verified. |
| 3 | — | Traceability preserved: AC-8.10 → NFR-BEDROCK-CALL-TIMEOUT + ADR-0001; AC-8.11 → NFR-BEDROCK-CALL-TIMEOUT; the previously-orphaned NFR is now referenced by ≥ 1 AC (AC-8.10/8.11) **and** ≥ 1 CT (CT-SCH-16/17). No AC renumbered; no other NFR newly orphaned. | Verified. |
| 4 | — | New CTs appended after CT-SCH-15 as CT-SCH-16/17 — existing CT ids and § numbers NOT renumbered, so no downstream `07-tasks.md` / review-file CT references break. | Mechanically clean. |
| 5 | — | `additionalProperties: false` kept; new keys optional (not in `required`), so the shared `fixtures/config.global.yaml` positive corpus (CT-SCH-13) still validates unchanged. The fixture was therefore NOT edited (it is not in `scope_of_design_edit`). | Scope-clean. |

## Consistency checks (shared.md § 7 amendment procedure step 6)

Run with `jsonschema` (Draft 2020-12) + `pyyaml` in a venv on 2026-06-24:

- `resolved-config.schema.json` passes the Draft 2020-12 meta-schema (`check_schema`). ✅
- `additionalProperties` is still `false`; both new properties present. ✅
- `fixtures/config.global.yaml` (CT-SCH-13 positive corpus) still validates **unchanged** — confirming the
  new keys are correctly optional. ✅
- A config carrying both timeout keys (CT-SCH-16) validates. ✅
- `minimum: 1` rejects 0; `additionalProperties: false` (CT-SCH-14) still rejects an unknown key. ✅
- contract-tests index still consistent: CT-SCH-16/17 reference the real `resolved-config` schema + the
  newly-created AC-8.10/8.11; no CT references a renamed/removed symbol. ✅

## Constraints honoured (DCR-4 constraints block)

- Edited only files in `scope_of_design_edit` (incl. `design-progress.md`) + this review file. No
  source/test edits; no edit outside scope. ✅
- Did NOT edit Phase-1a user stories (US-1..US-21 text). Added new ACs (AC-8.10/8.11) under existing US-8 and
  NFR-table notes only; EARS form kept. ✅
- Preserved all traceability — the NFR is now referenced by ≥ 1 AC and ≥ 1 CT after this amendment. ✅
- `resolved-config.schema.json` `additionalProperties: false` intact. ✅
- Did NOT touch, mark, or re-judge any milestone gate (G0–G4); G3 stays OPEN. ✅
- Did NOT edit `design/07-tasks-progress.md` or `design/open-questions.md` (coordinator-owned; the coordinator
  already logged the DCR-4 lifecycle there — they are modified in the working tree by the coordinator, not by
  this amendment). ✅
- Single amendment commit per shared.md § 7.2; `design-progress.md` updated in the same commit per § 9.5,
  with the amendment-mode front-matter flip-and-return per § 9.2. ✅

## Flagged for later phases

- The wiring is documented; the implementation is **T-4.6** in M4 (deps T-0.3). The implementer adds the two
  `ConfigKeys`/`ConfigDefaults` entries and extends the `BedrockClientFactory.wiring(...)` seam so the
  CT-INV-13-style wiring test can inspect the configured timeouts without a live Bedrock call.
- The Apache HTTP client artifact `software.amazon.awssdk:apache-client` must be added to `pom.xml` at T-4.6
  implementation time (recorded in ADR-0001 Consequences) — it is the dependency that carries the
  per-request `socketTimeout`/`connectionTimeout` knobs the NFR pins.
