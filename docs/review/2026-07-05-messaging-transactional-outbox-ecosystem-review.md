# messaging-transactional-outbox Ecosystem Review

Date: 2026-07-05
Module: `:messaging-transactional-outbox`
Branch: `refactor/messaging-transactional-outbox-ecosystem-patterns`

## Scope

- Declare direct `bluetape4k-core` dependency for support helper usage.
- Validate order ids with bluetape4k `requirePositiveNumber` before DB writes and reads.
- Keep transactional outbox persistence, publication retry, and controller behavior unchanged.

## 7-Tier Review

| Tier | Lens | Verdict | Evidence |
|---|---|---|---|
| 1 | Security/input | PASS | `updateStatus` and `getOrder` reject non-positive ids through named bluetape4k validation helpers. |
| 2 | Architecture | PASS | No module topology or transaction boundary change; only a direct BOM-governed ecosystem dependency was added. |
| 3 | Data/DB | PASS | Exposed update, outbox insert, payload shape, and response mapping remain unchanged after id validation. |
| 4 | Code quality | PASS | Validated id is reused consistently in update, logging, payload, aggregate id, and response lookup. |
| 5 | Tests | PASS | `./gradlew :messaging-transactional-outbox:test --console=plain --max-workers=1` passed. |
| 6 | Operations | PASS | No Kafka, database, scheduler, workflow, or runtime configuration changed. |
| 7 | Evidence/docs | PASS | `git diff --check` passed; Gradle test output executed 7 tests successfully. |

## P0/P1 Gate

- P0: 0
- P1: 0
- Deferred: existing deprecation warnings in untouched Exposed setup and Faker test data remain outside this PR slice.
