# Issue #348 Kafka-first Outbox Fallback Design

Date: 2026-06-29
Repository: `bluetape4k-workshop`
Branch: `feat/issue-348-kafka-outbox-fallback`
Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/348

## Problem

`messaging/transactional-outbox` already demonstrates the classic transactional
outbox pattern: `orders` and `outbox_events` are written together inside the
same database transaction, and a scheduler later publishes the outbox row to
Kafka.

Issue #348 asks for a complementary pattern for systems where the hot write
transaction should be lighter:

1. Write only the domain `orders` row inside the transaction.
2. After commit, publish the domain event directly to Kafka.
3. Retry direct Kafka publish up to 3 attempts.
4. If Kafka is unavailable or the retry budget is exhausted, store the event as
   `NOT_PUBLISHED` in a durable fallback store.
5. Let a new module-local outbox-style relay publish fallback rows to Kafka and
   move them through retry, `PUBLISHED`, and dead-letter states.

This is not a strict replacement for classic transactional outbox. It is a
transaction-load-first variant with explicit recovery and reconciliation
trade-offs. HTTP response latency is not the primary success metric because the
direct Kafka path waits for bounded send confirmation after commit.

## Current Evidence

- `messaging/transactional-outbox` writes `orders` and `outbox_events` in one
  transaction. Its README documents the classic guarantee, and
  `OrderService.placeOrder` inserts both rows together.
- `OutboxPublisher.publishEvent` sends to Kafka synchronously with
  `kafkaTemplate.send(...).get()` before marking the row `PUBLISHED`.
- `docs/lessons/2026-05-24-transactional-outbox-pattern.md` records the classic
  dual-write failure: DB commit followed by Kafka failure can lose the event.
- `docs/lessons/2026-05-27-issue-228-domain-module-adoption.md` explicitly
  says not to change `messaging/transactional-outbox` to `bluetape4k-kafka4`
  unless the publisher design changes, because its current blocking send
  preserves the classic success contract.
- `messaging/kafka` and `messaging/kafka-reply` already provide Kafka examples,
  and the version catalog already has `bluetape4k-kafka4`,
  `bluetape4k-redis`, `bluetape4k-redisson`, Spring Kafka test, Kafka
  Testcontainers, and PostgreSQL Testcontainers aliases.
- Existing Examples workflow currently includes `messaging/kafka/**` but not
  a future `messaging/kafka-outbox-fallback/**` path.

CodeGraph found the root checkout's `OutboxPublisher`; the new worktree was not
indexed for that symbol yet. Current design evidence therefore uses the root
CodeGraph hit plus direct source, README, GNO, and official docs inspection.

## External API Evidence

- Spring Kafka official docs show `KafkaTemplate.send(...)` returns a
  `CompletableFuture<SendResult<K, V>>`; callers can handle async completion or
  block with `get()` when they need confirmed send success.
- Spring Kafka docs also document publishing failed records to a dead-letter
  topic with `DeadLetterPublishingRecoverer`, but this workshop module will keep
  application-owned fallback state visible in the example instead of hiding it
  inside listener error handling.
- Redisson official docs show `RStream` supports `add`, `readGroup`,
  `pendingRange`, `fastClaim`, and `ack`. This is a good alternative buffer
  candidate, but implementing Redis Streams in this first issue would expand the
  example into a second durable queue implementation.
- Exposed official docs confirm Spring-managed `@Transactional` services can
  use Exposed DSL operations without wrapping every call in manual
  `transaction {}` blocks.

## Design Goals

- Reduce the hot write transaction by avoiding normal-path outbox inserts.
- Keep the happy path simple: one domain transaction, then bounded direct Kafka
  publish.
- Keep failed publication observable and recoverable through durable fallback
  rows.
- Keep event ids stable so relay retries and consumers can be idempotent.
- Keep classic transactional outbox intact as the stronger consistency baseline.
- Teach the trade-off honestly: lower normal-path DB work, weaker atomicity
  unless reconciler/idempotency are present.
- Make failure visibility concrete through bounded logs, safe demo endpoints,
  Micrometer counters, and table-state inspection.

## Non-goals

- Do not replace `messaging/transactional-outbox`.
- Do not claim this pattern provides the same atomic guarantee as classic
  transactional outbox.
- Do not add unused Redis dependencies only because Redis is mentioned as a
  possible buffer.
- Do not build a general-purpose eventing framework.
- Do not hand-roll raw Testcontainers when bluetape4k launchers exist.
- Do not implement Redis Streams, Kafka transactions, exactly-once delivery,
  total ordering, or production consumer idempotency in Issue #348.
- Do not expose raw event payloads, raw exception text, stack traces,
  credentials, broker URLs with secrets, tokens, or keys through demo endpoints.

## Approach Options

### Option A: Relational fallback publication table

Create `messaging/kafka-outbox-fallback` with:

- `orders` table for domain state.
- `event_publications` table for only failed direct publishes.
- `OrderService` saves only `orders` in the transaction.
- `OrderEventPublisher` runs after commit, publishes to Kafka with up to 3
  attempts, and stores `NOT_PUBLISHED` only after failure.
- `PublicationRelay` polls `NOT_PUBLISHED` and retryable `FAILED` rows, sends to
  Kafka, and updates `PUBLISHED`, `FAILED`, or `DEAD_LETTER`.
- `PublicationReconciler` identifies orders whose event status is unknown and
  can reconstruct a fallback row with a deterministic event id. This is a
  loss-avoidance safety net that may intentionally duplicate an already
  published event; consumers must dedupe by event id.

Pros:

- Closest to the user's requested flow.
- Reuses current Exposed/PostgreSQL/Kafka workshop patterns.
- Keeps the fallback durable and inspectable in tests and README diagrams.
- Avoids adding Redis complexity before the core trade-off is clear.

Cons:

- The fallback write still uses the database on failure path.
- Crash between Kafka failure and fallback insert still needs reconciler
  coverage.

### Option B: Redis Stream fallback buffer

Use Redis Stream as the failed-publication buffer:

- Direct Kafka publish remains the happy path.
- Failed events are appended to a Redis Stream.
- A stream consumer group relays entries to Kafka and acknowledges them after
  success.
- Pending/claim logic handles consumer crashes.

Pros:

- Keeps DB load lower even on fallback.
- Demonstrates the user's Redis buffer idea directly.

Cons:

- Adds a second durability system to the first implementation.
- Requires Redis failure semantics, pending entry handling, stream group setup,
  and Testcontainers Redis in addition to PostgreSQL and Kafka.
- The example becomes about Redis Streams as much as about the outbox trade-off.

### Option C: Classic outbox with fewer columns or batched insert

Keep writing outbox rows inside the domain transaction, but optimize the row or
batching behavior.

Pros:

- Keeps strongest outbox semantics.

Cons:

- Does not satisfy the main goal: removing normal-path outbox writes from the
  hot transaction.

## Selected Approach

Select Option A for Issue #348.

Option A directly teaches the requested transaction-load trade-off while keeping
the implementation small enough for a workshop module. Option B remains a
documented extension point and a good future issue once the core behavior is
validated. Option C is rejected because it preserves the normal-path outbox
write and therefore does not address the user's goal.

## Proposed Module

Path: `messaging/kafka-outbox-fallback`

Gradle module name through existing include convention:
`:messaging-kafka-outbox-fallback`

Primary package:
`io.bluetape4k.workshop.messaging.fallback`

Suggested dependencies:

- `bluetape4k-core`, `bluetape4k-logging`, `bluetape4k-coroutines`
- Exposed core/JDBC/Spring transaction support
- Spring Boot 4 Web MVC, validation, actuator, Spring Kafka
- Spring Kafka `KafkaTemplate`; do not add `bluetape4k-kafka4` unless the
  implementation uses a concrete API from it
- PostgreSQL runtime + Testcontainers PostgreSQL
- Kafka Testcontainers
- Jackson 3
- `bluetape4k-junit5`, `bluetape4k-assertions`, MockK/springmockk

Redis dependencies are intentionally excluded from the first implementation.
Redis Stream fallback is a follow-up issue candidate, not Issue #348 scope.

## Component Design

### Tables

`orders`

- `id`
- `customer_id`
- `product`
- `quantity`
- `status`
- `created_at`
- `updated_at`

`event_publications`

- `id`
- `event_id` unique stable id
- `aggregate_type`
- `aggregate_id`
- `event_type`
- `payload`
- `status`: `NOT_PUBLISHED`, `PUBLISHED`, `FAILED`, `DEAD_LETTER`
- `direct_attempt_count`
- `relay_retry_count`
- `last_error_code`
- `last_error_summary`
- `next_attempt_at`
- `claimed_by`
- `claimed_until`
- `created_at`
- `published_at`
- `updated_at`

The table stores only failed or reconstructed publications. Direct Kafka success
does not create a publication row. Fallback rows start with
`relay_retry_count = 0` even when direct publish already used all three direct
attempts. `event_id` is deterministic:
`order-placed:{orderId}:v1`; unique upsert by `event_id` makes fallback insert
and reconciliation idempotent.

`payload` is a closed `OrderPlacedEvent` JSON document serialized from a typed
DTO. Jackson default typing and class-name polymorphism are prohibited. Payload
size is bounded by the module configuration. `last_error_summary` is bounded,
sanitized text and must not store stack traces, serialized exception objects,
raw payloads, credentials, API keys, tokens, or connection URLs containing
secrets.

### Services

`PlaceOrderUseCase`

- Validates input with bluetape4k `require*` helpers.
- Owns the public caller contract for order placement.
- Is a non-transactional orchestrator that calls an internal
  `TransactionalOrderWriter.saveOrder(...)` method. The writer owns the
  `@Transactional` boundary and writes only `orders`.
- Executes the direct Kafka publish after commit and before returning the REST
  response. The response reports `publicationStatus` as `PUBLISHED_DIRECT`,
  `FALLBACK_STORED`, or `FALLBACK_STORE_FAILED`.
- Keeps any lower-level "save order only" method internal/test-only so callers
  cannot forget the publish/fallback step.

`OrderEventPublisher`

- Builds `OrderPlaced` event with stable `eventId`.
- Publishes to Kafka after DB commit.
- Uses blocking `KafkaTemplate.send(...).get(timeout)` for an explicit
  workshop-friendly confirmation contract.
- Retries up to 3 direct attempts with a per-attempt timeout and a total direct
  publish budget. Timeout is treated as publish failure and triggers fallback.
- On final direct failure, creates or updates an `event_publications` row with
  `NOT_PUBLISHED`, `direct_attempt_count = 3`, and `relay_retry_count = 0`.
- If fallback insert fails, logs a structured error, increments a metric, and
  returns `FALLBACK_STORE_FAILED`; reconciler is then the recovery mechanism.
- Does not use suspend APIs in v1; if future suspend code is added, it must
  rethrow `CancellationException` before broad exception handling.

`PublicationRelay`

- Polls `NOT_PUBLISHED` and retryable `FAILED` rows.
- Claims rows atomically before sending. The implementation may use
  `SELECT ... FOR UPDATE SKIP LOCKED` or an optimistic `UPDATE ... WHERE
  status IN (...) AND claimed_until < now()` pattern, but it must prevent two
  scheduler ticks from publishing the same non-terminal row concurrently.
- Uses bounded batch size, poll interval, `next_attempt_at`, `claimed_by`, and
  `claimed_until`. Stale claims are retryable after the claim TTL.
- Sends to Kafka and only then marks `PUBLISHED`.
- Increments `relay_retry_count` and stores sanitized error code/summary on
  failure.
- Moves to `DEAD_LETTER` after max retry.
- Is idempotent for already terminal rows.
- May duplicate a Kafka event if the process crashes after Kafka send succeeds
  but before the row is marked `PUBLISHED`; this is documented as at-least-once
  behavior and relies on deterministic `event_id` consumer dedupe.

`PublicationReconciler`

- Demonstrates the operating safety net for this pattern.
- Reconstructs or upserts a fallback row for orders whose event publication is
  unknown.
- Keeps the normal write transaction free from outbox rows while making the
  residual crash window explicit and recoverable in the example.
- Does not prove that an event was unpublished. Because direct success creates
  no publication row, reconciliation is a deliberate loss-avoidance vs
  duplicate-risk trade-off.

`PublicationQueryService`

- Provides demo-safe publication state for REST/README inspection.
- Excludes raw `payload` and raw error text from responses.
- Exposes only `eventId`, `aggregateId`, `eventType`, `status`,
  `directAttemptCount`, `relayRetryCount`, timestamps, and sanitized error
  category/summary.

### Validation and Security

- `customer_id` and `product` are required, length-bounded, and must reject
  control characters.
- `quantity` must be positive and capped by a small workshop-friendly maximum.
- Topic names and event types are fixed allowlists in configuration:
  `order-events` and `OrderPlaced`.
- Demo endpoints are local/workshop examples only. Production use requires
  authentication, authorization, rate limits, audit logging, and reviewed
  actuator exposure.
- Actuator exposure remains minimal; no endpoint may leak raw payloads,
  stack traces, credentials, broker URLs with secrets, tokens, or keys.

### Observability

Required Micrometer meters:

- `workshop.outbox.direct.publish.attempts` tagged by `result`.
- `workshop.outbox.fallback.stored` tagged by `result`.
- `workshop.outbox.relay.rows` tagged by `status`.
- `workshop.outbox.reconciler.repairs` tagged by `result`.
- `workshop.outbox.publication.lag` for oldest `NOT_PUBLISHED`/`FAILED` age.

Required structured log events:

- `order.event.direct-publish.failed`
- `order.event.fallback-store.failed`
- `order.event.relay.dead-lettered`
- `order.event.reconciler.repaired`

Tests should use a registry or endpoint assertion for at least the direct
publish failure, fallback storage, relay success/failure, and reconciler repair
metrics.

### Kafka Topics and Dead Letter

- Main topic: `order-events`, owned by this workshop module configuration.
- Consumer idempotency key: `eventId`.
- Issue #348 uses table state `DEAD_LETTER` only. A Kafka DLQ topic such as
  `order-events.dlq` is a documented extension and future issue, not v1 scope.

`orders` and `event_publications` are module-local table names inside this
example. They are intentionally separate from
`messaging/transactional-outbox`; do not share implementation classes between
the modules.

### Performance and Transaction Budget

Normal direct-success command count:

- Hot transaction: one order insert, no publication insert.
- After commit: one Kafka send confirmation.
- Fallback table: no row.

Direct-failure command count:

- Hot transaction: one order insert, no publication insert.
- After commit: up to 3 Kafka send attempts with timeout.
- Fallback path: one idempotent publication upsert.

Relay path command count:

- One bounded row claim batch.
- One Kafka send per claimed row.
- One terminal or retry update per claimed row.

Success criteria are functional plus comparative, not a hard benchmark
threshold: README must show that classic outbox records publication intent in
the hot transaction, while this module removes that normal-path insert and
accepts at-least-once reconciliation obligations. If a local stress helper is
added, it must report p95 HTTP response time, p95 direct publish time, fallback
store count, relay lag, and duplicate-risk notes rather than claiming global
performance superiority.

## Failure Modes

| Failure | Expected behavior |
|---|---|
| DB transaction fails | No order and no event publication. |
| DB commit succeeds, Kafka succeeds | Order exists; no fallback row is created. |
| DB commit succeeds, Kafka fails temporarily then succeeds | Order exists; no fallback row is created. |
| DB commit succeeds, Kafka fails after 3 attempts | Order exists; `event_publications.status = NOT_PUBLISHED`. |
| Kafka send hangs or exceeds timeout | Timeout is a publish failure; after 3 bounded attempts a fallback row is upserted. |
| Kafka exhausted and fallback insert fails | Structured error and metric are emitted; REST reports `FALLBACK_STORE_FAILED`; reconciler can upsert the deterministic event later. |
| Relay publish succeeds | Row moves to `PUBLISHED`. |
| Relay retry exhausts | Row moves to `DEAD_LETTER` with sanitized last error code/summary for manual inspection. |
| Relay crashes after Kafka send before marking `PUBLISHED` | Later relay retry may duplicate the event; consumers dedupe by `event_id`. |
| Process crashes after commit before fallback insert | Reconciler can upsert a fallback row from `orders`; README explains this may duplicate an already published event and is weaker than classic outbox. |
| Relay and reconciler overlap | Unique `event_id` upsert and row claiming prevent duplicate fallback rows; Kafka duplicate risk remains at-least-once and documented. |

## Testing Strategy

Use one Testcontainers-backed Gradle invocation per verification pass.

Required tests:

- `placeOrder` stores only `orders` on direct Kafka success.
- Direct publish retries up to 3 attempts and succeeds without fallback row.
- Direct publish failure creates a `NOT_PUBLISHED` fallback row.
- Direct publish timeout creates a `NOT_PUBLISHED` fallback row after bounded
  attempts.
- Fallback insert failure is observable and can be repaired by reconciler.
- Relay publishes a fallback row and marks it `PUBLISHED`.
- Relay failure increments retry count and transitions to `DEAD_LETTER`.
- Duplicate relay call is idempotent for terminal rows.
- Concurrent relay calls cannot claim the same non-terminal row twice.
- Reconciler reconstructs missing fallback for an order with unknown publication
  status and documents duplicate-safe semantics.
- REST endpoint returns a created order and exposes safe publication state for
  demo inspection without raw payloads, stack traces, broker URLs, tokens, keys,
  or credentials.
- Invalid customer/product/quantity input is rejected.
- Metrics/log-observability paths are covered at least at component level.

Test conventions:

- Use `bluetape4k-assertions`; do not introduce AssertJ/Kluent/JUnit assertion
  APIs in new tests.
- Use `PostgreSQLServer.Launcher.postgres` and `KafkaServer.Launcher.kafka`.
- Use MockK/springmockk for producer failure paths where real Kafka outage would
  make tests slow or flaky.
- Keep Testcontainers tests serial.

## Documentation and Diagram Scope

Module README pair:

- `messaging/kafka-outbox-fallback/README.md`
- `messaging/kafka-outbox-fallback/README.ko.md`

Root README pair:

- Add module row under Messaging.
- Add targeted test command.
- Keep English/Korean parity.

README visuals:

- Architecture diagram: domain transaction, direct Kafka path, fallback table,
  relay, and reconciler.
- Sequence diagram: happy path and failure/fallback path.
- Optional state lifecycle diagram for `event_publications`.

Diagram requirements:

- Use shared Kafka and database icons from the wiki icon catalog.
- Use orthogonal rounded connectors and avoid diagonal card-to-card lines.
- Report row spacing, label overlap, endpoint, geometry, and PNG evidence.
- Render both SVG and PNG assets under `docs/images/readme-diagrams/`.
- Expected asset basenames:
  `kafka-outbox-fallback-readme-architecture-01.svg`,
  `kafka-outbox-fallback-readme-sequence-01.svg`,
  `kafka-outbox-fallback-readme-state-01.svg`.

README content requirements:

- Language switch in both README files.
- Comparison table for classic transactional outbox vs Kafka-first fallback.
- "Not guaranteed" section: no classic transactional atomicity, exactly-once
  delivery, total ordering, Kafka transactions, Redis Stream fallback, real
  Kafka DLQ, or production consumer idempotency implementation in Issue #348.
- Concrete `POST /api/orders` example, response fields, safe publication-state
  inspection example, and "Kafka down -> fallback row -> relay publish"
  walkthrough.
- Operator runbook: boot/test commands, failure injection, SQL queries for
  stuck rows, retry/dead-letter inspection, reconciler repair path, scheduler
  toggles, and expected metric/log evidence.
- Migration guidance: choose classic transactional outbox when every mutation
  must durably record publication intent in the same database transaction;
  choose Kafka-first fallback only when lower hot-transaction load is worth
  reconciliation and duplicate-handling obligations. Keep both examples side by
  side; do not migrate `messaging/transactional-outbox`.

Blog follow-up:

- Write the `bluetape4k.github.io` article after the workshop PR is validated,
  using the finished code as source truth.
- Compare classic transactional outbox vs Kafka-first fallback outbox.
- Keep the trade-off explicit: lower normal-path transaction load, weaker
  atomicity without reconciler and idempotent event ids.

## Workflow and CI Impact

- `settings.gradle.kts` should include the module automatically through
  `includeModules("messaging", false, true)`, but `./gradlew projects` must
  prove it.
- Update `.github/workflows/Examples.yml` path filters for
  `messaging/kafka-outbox-fallback/**`.
- Add `:messaging-kafka-outbox-fallback:test` to the sequential container
  example lane and update result artifact paths/summary dependencies.
- Update `scripts/smoke-validate.sh` messaging group with
  `:messaging-kafka-outbox-fallback:test`.
- Update diagram validation scripts for the three expected README asset
  basenames.
- Run `actionlint` because workflow YAML changes are in scope.

## Rollback and Operations

- Direct publish, relay scheduler, and reconciler scheduler must be configurable
  so an operator can disable each path during debugging.
- Rollback from the example means disabling relay/reconciler, draining or
  inspecting `NOT_PUBLISHED`/`FAILED`/`DEAD_LETTER` rows, removing the module
  from CI/smoke registration, and deleting demo tables only after rows are no
  longer needed.
- `DEAD_LETTER` rows are manual-inspection records in Issue #348. Re-drive is
  allowed only by resetting rows to `NOT_PUBLISHED` through an explicit
  demo/admin method or SQL shown in the README runbook.
- Table cleanup is manual and documented; no automatic purge runs in v1.

## Acceptance Criteria

- Issue #348 metadata remains assigned to `debop`, milestone `1.3.1`.
- New module `:messaging-kafka-outbox-fallback` exists and is discoverable by
  Gradle.
- Direct Kafka success writes no fallback publication row.
- Direct Kafka failure after 3 attempts writes `NOT_PUBLISHED`.
- Direct Kafka timeout and fallback insert failure behavior are bounded,
  observable, and tested.
- Relay publishes fallback rows and records `PUBLISHED`, `FAILED`, and
  `DEAD_LETTER` states correctly.
- Relay row claiming prevents concurrent scheduler ticks from claiming the same
  non-terminal row.
- Reconciler demonstrates how the crash window is operationally repaired while
  documenting duplicate-risk and deterministic event-id dedupe.
- Safe publication-state endpoints do not expose raw payloads, stack traces,
  connection strings, tokens, keys, credentials, or raw exception objects.
- README.md and README.ko.md explain the trade-off and compare with classic
  transactional outbox.
- README diagrams ship as SVG + PNG and pass the diagram layout evidence gate.
- CI/smoke scripts include the module.
- Required verification commands pass:
  `./gradlew projects`,
  `./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1`,
  targeted compile with warnings,
  README/diagram validators,
  `actionlint`,
  and `git diff --check`.

## Closed Decisions

- Issue #348 uses table `DEAD_LETTER` state only. Real Kafka DLQ publishing is a
  follow-up unless a later issue explicitly adds it.
- Redis Stream fallback is a follow-up issue candidate. This module isolates the
  Kafka-first/outbox-fallback trade-off.
- The direct publish path uses blocking Spring Kafka confirmation with explicit
  timeout and total retry budget, not `bluetape4k-kafka4` coroutine helpers.
- The public API is a single `PlaceOrderUseCase.placeOrder(...)` boundary that
  owns transaction, after-commit publish, retry, and fallback persistence.
