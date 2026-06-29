# Kafka-first Outbox Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Issue #348 as a new `messaging/kafka-outbox-fallback` workshop module that stores only `orders` in the hot transaction, publishes directly to Kafka after commit, stores failed publication rows as durable fallback, and relays fallback rows later.

**Architecture:** Add a standalone Spring Boot 4 + Exposed + Kafka module modeled after `messaging/transactional-outbox`, but keep the public order-placement boundary in `PlaceOrderUseCase`. `PlaceOrderUseCase` orchestrates an internal transactional order write, bounded direct Kafka publish, fallback upsert on failure, relay/reconciler recovery, safe inspection endpoints, README diagrams, and CI/smoke registration.

**Tech Stack:** Kotlin, Spring Boot 4 Web MVC, Spring Kafka `KafkaTemplate`, JetBrains Exposed JDBC/Spring transactions, PostgreSQL Testcontainers, Kafka Testcontainers, MockK/springmockk, bluetape4k assertions/logging/Jackson/testcontainers, Micrometer.

---

## Source Truth

- Spec: `docs/superpowers/specs/2026-06-29-issue-348-kafka-outbox-fallback-design.md`
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/348
- Reference module: `messaging/transactional-outbox`

## File Structure

Create module files:

- `messaging/kafka-outbox-fallback/build.gradle.kts`: Spring Boot, Exposed, Kafka, Testcontainers, Micrometer dependencies.
- `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackApplication.kt`: app entrypoint.
- `.../api/OrderRequest.kt`: request DTO with bean validation.
- `.../api/OrderResponse.kt`: order response with `publicationStatus`.
- `.../api/OrderPublicationStatus.kt`: caller-facing publication outcome enum.
- `.../api/PublicationResponse.kt`: safe publication-state DTO; no raw payload.
- `.../api/AdminActionResponse.kt`: response DTO for demo-admin relay/reconcile actions.
- `.../api/OrderController.kt`: REST endpoints.
- `.../api/RestExceptionHandler.kt`: sanitized `400` responses for validation failures.
- `.../config/ExposedConfig.kt`: create tables on startup.
- `.../config/KafkaConfig.kt`: `KafkaTemplate<String, String>` producer config following the local Spring Kafka pattern.
- `.../config/FallbackOutboxProperties.kt`: topic, retry, timeout, batch, scheduler toggles.
- `.../config/ClockConfig.kt`: injectable `Clock` for deterministic reconciler tests.
- `.../domain/OrderStatus.kt`: enum.
- `.../domain/OrderTable.kt`: `orders` table.
- `.../domain/OrderRecord.kt`: internal order projection.
- `.../domain/TransactionalOrderWriter.kt`: internal `@Transactional` writer/reader.
- `.../domain/PlaceOrderUseCase.kt`: public orchestration boundary.
- `.../publication/OrderPlacedEvent.kt`: typed event DTO and deterministic event id.
- `.../publication/EventPublicationStatus.kt`: `NOT_PUBLISHED`, `PUBLISHED`, `FAILED`, `DEAD_LETTER`.
- `.../publication/EventPublicationTable.kt`: fallback publication table.
- `.../publication/EventPublicationRecord.kt`: internal row projection.
- `.../publication/EventPublicationRepository.kt`: insert/upsert, claim, status update, safe query helpers.
- `.../publication/OrderEventPublisher.kt`: bounded direct Kafka publish and fallback upsert.
- `.../publication/EventPublicationRelay.kt`: scheduled claim/send/update relay.
- `.../publication/PublicationReconciler.kt`: deterministic fallback reconstruction.
- `.../publication/PublicationQueryService.kt`: safe publication-state query for REST.
- `.../observability/OutboxMetrics.kt`: Micrometer counters/timer/gauge registration helpers.
- `messaging/kafka-outbox-fallback/src/main/resources/application.yml`: demo config.
- `messaging/kafka-outbox-fallback/src/test/resources/junit-platform.properties`
- `messaging/kafka-outbox-fallback/src/test/resources/logback-test.xml`
- `messaging/kafka-outbox-fallback/src/test/kotlin/io/bluetape4k/workshop/messaging/fallback/AbstractKafkaOutboxFallbackTest.kt`
- `.../KafkaOutboxFallbackFlowTest.kt`: integration and component tests.

Modify shared project files:

- `README.md` and `README.ko.md`: add Messaging row and targeted test command.
- `.github/workflows/Examples.yml`: add path filter, container test task, artifact path, summary dependency.
- `scripts/smoke-validate.sh`: add messaging test task.
- `scripts/validate-readme-architecture-diagrams.mjs`
- `scripts/validate-sequence-diagrams.mjs`
- Verify the state diagram through SVG/PNG existence, README references, and the diagram layout evidence gate; no separate state validator exists in this repo today.

Create diagrams:

- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-architecture-01.png`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-sequence-01.png`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.svg`
- `docs/images/readme-diagrams/kafka-outbox-fallback-readme-state-01.png`

## Task 1: Module Skeleton and Configuration

**Files:**
- Create: `messaging/kafka-outbox-fallback/build.gradle.kts`
- Create: `messaging/kafka-outbox-fallback/src/main/kotlin/io/bluetape4k/workshop/messaging/fallback/KafkaOutboxFallbackApplication.kt`
- Create: `messaging/kafka-outbox-fallback/src/main/resources/application.yml`
- Create: test resources listed above.

- [ ] **Step 1: Create `build.gradle.kts` by adapting `messaging/transactional-outbox/build.gradle.kts`**

Use the same dependency families as transactional-outbox. Set:

```kotlin
exposed {
    migrations {
        tablesPackage = "io.bluetape4k.workshop.messaging.fallback"
        databaseUrl = "jdbc:h2:mem:messaging-kafka-outbox-fallback-migrations;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        databaseUser = "sa"
        databasePassword = ""
    }
}

springBoot {
    mainClass.set("io.bluetape4k.workshop.messaging.fallback.KafkaOutboxFallbackApplicationKt")
}
```

Do not add Redis or `bluetape4k-kafka4`.

- [ ] **Step 2: Create application entrypoint**

```kotlin
package io.bluetape4k.workshop.messaging.fallback

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class KafkaOutboxFallbackApplication

fun main(args: Array<String>) {
    runApplication<KafkaOutboxFallbackApplication>(*args)
}
```

- [ ] **Step 3: Create `application.yml`**

Include datasource, Kafka bootstrap placeholder, Jackson, safe error defaults, actuator health/readiness/liveness, and:

```yaml
server:
  error:
    include-message: never
    include-stacktrace: never
    include-binding-errors: never

management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  endpoints:
    web:
      exposure:
        include: health,info,prometheus

workshop:
  kafka-outbox-fallback:
    topic: order-events
    direct-publish-attempts: 3
    direct-publish-timeout: 500ms
    direct-publish-total-timeout: 1600ms
    relay-max-retries: 3
    relay-batch-size: 25
    relay-fixed-delay: 2000ms
    relay-claim-ttl: 30s
    reconciler-grace: 30s
    max-payload-bytes: 8192
    direct-publish-enabled: true
    relay-enabled: true
    reconciler-enabled: true
    demo-admin-endpoints-enabled: false
```

- [ ] **Step 4: Implement validated properties contract**

`FallbackOutboxProperties` must use `@ConfigurationProperties("workshop.kafka-outbox-fallback")` and `@Validated`.
Bounds:

- `topic` must equal `order-events`.
- `directPublishAttempts` must be exactly `3` for the workshop.
- `relayMaxRetries`, `relayBatchSize`, and durations must be positive.
- `maxPayloadBytes` must be between `1024` and `65536`.
- `demoAdminEndpointsEnabled` defaults to `false`.

Add a config validation test that invalid topic, zero timeout, zero batch size, and oversized payload limit fail startup or binding validation.

- [ ] **Step 5: Verify module discovery**

Run:

```bash
./gradlew projects | rg "messaging-kafka-outbox-fallback"
```

Expected: `Project ':messaging-kafka-outbox-fallback'` appears.

## Task 2: Domain Tables, DTOs, Validation, and Test Isolation

**Files:**
- Create domain/API files listed above.
- Test: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **Step 1: Write failing tests for transactional writer and REST validation**

Create tests named:

```kotlin
@Test
fun `transactional writer stores only order row`()

@Test
fun `POST api-orders rejects invalid input with safe 400 and zero persistence`()
```

Assert writer success creates one `orders` row and zero `event_publications` rows. Assert REST validation rejects blank, length overflow, control characters, quantity `0`, and quantity `1001` with `400 Bad Request`, sanitized error body, and zero persisted rows.

- [ ] **Step 2: Implement request/response DTOs**

Use:

```kotlin
data class OrderRequest(
    @field:NotBlank @field:Size(max = 80) val customerId: String,
    @field:NotBlank @field:Size(max = 120) val product: String,
    @field:Min(1) @field:Max(1000) val quantity: Int,
) : Serializable
```

Define public `OrderPublicationStatus` separately from fallback row status:

```kotlin
enum class OrderPublicationStatus {
    PUBLISHED_DIRECT,
    FALLBACK_STORED,
    FALLBACK_STORE_FAILED,
    UNKNOWN,
}
```

Define `OrderResponse` with `id`, `customerId`, `product`, `quantity`, `status`, `publicationStatus`, `createdAt`, `updatedAt`. For read endpoints, `publicationStatus` is `UNKNOWN` unless a caller-facing outcome is known from the create flow.

- [ ] **Step 3: Implement tables and transactional writer**

`OrderTable` mirrors transactional-outbox `orders`. `TransactionalOrderWriter.saveOrder(...)` validates:

```kotlin
customerId.requireNotBlank("customerId")
product.requireNotBlank("product")
quantity.requirePositiveNumber("quantity")
require(customerId.length <= 80) { "customerId must be 80 characters or less" }
require(product.length <= 120) { "product must be 120 characters or less" }
require(quantity <= 1000) { "quantity must be 1000 or less" }
require(customerId.none(Char::isISOControl)) { "customerId must not contain control characters" }
require(product.none(Char::isISOControl)) { "product must not contain control characters" }
```

- [ ] **Step 4: Add sanitized exception mapping and test lifecycle isolation**

Create `RestExceptionHandler` that maps `MethodArgumentNotValidException` and `IllegalArgumentException` to `400 Bad Request` without echoing raw customer/product values. In `AbstractKafkaOutboxFallbackTest`, set dynamic properties:

```text
workshop.kafka-outbox-fallback.relay-enabled=false
workshop.kafka-outbox-fallback.reconciler-enabled=false
workshop.kafka-outbox-fallback.demo-admin-endpoints-enabled=false
```

Clean `event_publications` and `orders` between tests with `TransactionTemplate`. Keep Testcontainers launcher singletons and `@DynamicPropertySource`.
Scheduled entrypoints must honor these flags directly. Manual service methods
such as `relayOnce()` and `reconcileOnce()` remain callable in tests, but
`scheduledRelay()` and `scheduledReconcile()` must return without side effects
when their flags are false.

- [ ] **Step 5: Run targeted tests**

Run:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*transactional writer stores only order row*' --tests '*safe 400*' --max-workers=1
```

Expected: PASS.

## Task 3: Direct Kafka Publish and Fallback Persistence

**Files:**
- Create `FallbackOutboxProperties.kt`
- Create `OrderPlacedEvent.kt`
- Create `EventPublicationTable.kt`, `EventPublicationStatus.kt`, `EventPublicationRepository.kt`
- Create `OrderEventPublisher.kt`
- Create `OutboxMetrics.kt`
- Create `PlaceOrderUseCase.kt`
- Test: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **Step 1: Write failing tests for direct retry/fallback**

Add tests:

```kotlin
@Test
fun `placeOrder stores only order row and returns PUBLISHED_DIRECT when direct Kafka publish succeeds`()

@Test
fun `direct publish retries three times then stores NOT_PUBLISHED fallback row`()

@Test
fun `direct publish timeout stores NOT_PUBLISHED fallback row`()

@Test
fun `fallback insert failure returns FALLBACK_STORE_FAILED and records safe metric and log`()
```

Use `@MockkBean(relaxed = true) KafkaTemplate<String, String>`. For timeout, return an incomplete `CompletableFuture<SendResult<String, String>>()`, set a small per-attempt timeout plus total direct publish timeout, assert elapsed time remains below the total budget, and verify timed-out futures are cancelled. Document that a timed-out send has unknown Kafka outcome and may duplicate when fallback relay later publishes the deterministic `eventId`.

- [ ] **Step 2: Implement deterministic event DTO**

`OrderPlacedEvent` must be a `data class : Serializable` with `serialVersionUID`. Use:

```kotlin
val eventId: String get() = "order-placed:$orderId:v1"
```

Serialize only this closed DTO. Do not enable Jackson default typing or class-name polymorphism. Assert payload JSON does not contain `@class`, package names, stack traces, or raw exception text, and assert serialized bytes are `<= maxPayloadBytes`.

- [ ] **Step 3: Implement fallback repository**

Repository operations:

- `upsertNotPublished(event, directAttemptCount, errorCode, errorSummary)`
- `countByAggregateId(orderId)`
- `findSafeResponses(orderId?)`

Use unique `event_id`. Implement select-then-insert/update inside a transaction for PostgreSQL/H2 portability and keep it idempotent for this workshop.

- [ ] **Step 4: Implement direct publisher**

Use:

```kotlin
kafkaTemplate.send(topic, event.eventId, payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS)
```

Retry exactly `directPublishAttempts`. Sanitize errors into code/summary. Never persist raw stack traces.
Enforce `directPublishTotalTimeout`: stop retrying when the total budget is exhausted even if attempts remain, cancel timed-out futures with `future.cancel(true)`, and store fallback as `NOT_PUBLISHED`.

- [ ] **Step 5: Implement `PlaceOrderUseCase`**

Non-transactional orchestrator flow:

1. `val order = transactionalOrderWriter.saveOrder(...)`
2. Build typed `OrderPlacedEvent`
3. Call `orderEventPublisher.publishDirectOrFallback(event)`
4. Return `OrderResponse(... publicationStatus = result.status)`

The result status mapping is:

| Direct/fallback result | `OrderResponse.publicationStatus` |
|---|---|
| Kafka send confirmed | `PUBLISHED_DIRECT` |
| Kafka failed/timed out and fallback row upserted | `FALLBACK_STORED` |
| Kafka failed/timed out and fallback upsert failed | `FALLBACK_STORE_FAILED` |

- [ ] **Step 6: Run targeted tests**

Run:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*placeOrder stores only order row*' --tests '*direct publish*' --tests '*fallback insert failure*' --max-workers=1
```

Expected: PASS.

## Task 4: Relay, Reconciler, Safe Query API, and Observability

**Files:**
- Create `EventPublicationRelay.kt`
- Create `PublicationReconciler.kt`
- Create `PublicationQueryService.kt`
- Create/modify `OrderController.kt`, `PublicationResponse.kt`
- Test: `KafkaOutboxFallbackFlowTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests:

```kotlin
@Test
fun `relay publishes fallback row and marks it PUBLISHED`()

@Test
fun `relay failure increments relay retry and moves to DEAD_LETTER`()

@Test
fun `concurrent relay calls cannot claim the same row twice`()

@Test
fun `stale relay claim becomes eligible after claim ttl`()

@Test
fun `reconciler reconstructs deterministic fallback row and documents duplicate risk`()

@Test
fun `reconciler repair covers fallback store failure after grace duration`()

@Test
fun `demo admin relay and reconcile endpoints are disabled by default`()

@Test
fun `scheduled relay and reconciler do nothing when disabled`()

@Test
fun `publication endpoint never exposes raw payload or raw exception text`()

@Test
fun `health readiness liveness and safe error defaults expose no sensitive details`()

@Test
fun `metrics and structured logs record direct failure fallback relay and reconciler outcomes`()
```

- [ ] **Step 2: Implement atomic claim**

Use an optimistic claim-token contract for PostgreSQL/H2 compatibility:

1. Generate a per-run `claimToken`.
2. In one transaction, select candidate IDs ordered by `next_attempt_at` up to `relayBatchSize`.
3. For each candidate, update with predicates on `id`, eligible status, retry count, and stale/null `claimed_until`.
4. Count successful updates.
5. Reload and return only rows whose `claimed_by == claimToken`.
6. If an update count is `0`, do not send that row.

Eligibility:

```text
status in (NOT_PUBLISHED, FAILED)
relay_retry_count < relayMaxRetries
next_attempt_at <= now
claimed_until is null or claimed_until < now
```

Tests must run two concurrent relay calls against the same row and verify exactly one Kafka send. Add a stale-claim test that sets `claimed_until` in the past and proves the row becomes eligible again.

- [ ] **Step 3: Implement relay**

For each claimed row, send to Kafka with bounded timeout. On success mark `PUBLISHED`. On failure increment `relay_retry_count`, set `FAILED` or `DEAD_LETTER`, clear claim, set sanitized error fields, and set `next_attempt_at`.
Split scheduled and manual entrypoints:

- `scheduledRelay()` is annotated with `@Scheduled`, checks `relayEnabled`, and delegates to `relayOnce()` only when enabled.
- `relayOnce()` contains the claim/send/update logic and is callable by tests and demo-admin endpoints.
- The disabled-scheduler test inserts an eligible row, invokes `scheduledRelay()`, and verifies no Kafka send and unchanged row status.

- [ ] **Step 4: Implement reconciler**

Inject `Clock` and use `reconcilerGrace`. Find orders older than the grace duration with no matching `event_id`, upsert `NOT_PUBLISHED`, increment `workshop.outbox.reconciler.repairs`, and emit `order.event.reconciler.repaired`. Tests freeze time for "too new" and "older than grace" cases.
Split scheduled and manual entrypoints:

- `scheduledReconcile()` is annotated with `@Scheduled`, checks `reconcilerEnabled`, and delegates to `reconcileOnce()` only when enabled.
- `reconcileOnce()` contains deterministic reconstruction and is callable by tests and demo-admin endpoints.
- The disabled-scheduler test creates an eligible old order, invokes `scheduledReconcile()`, and verifies no fallback row is created.

- [ ] **Step 5: Implement safe REST endpoints**

Paths:

- `POST /api/orders`
- `GET /api/orders/{id}`
- `GET /api/orders`
- `GET /api/publications`
- `POST /api/publications/relay`
- `POST /api/publications/reconcile`

`PublicationResponse` excludes `payload` and raw error. It includes sanitized `lastErrorCode` and `lastErrorSummary`.
`POST /api/publications/relay` and `POST /api/publications/reconcile` are demo-admin endpoints. They return `404` or `403` and perform no action unless `demoAdminEndpointsEnabled=true`; tests keep them disabled by default and enable them only in endpoint-specific tests. `AdminActionResponse` includes `requested`, `processed`, `published`, `failed`, and `repaired` counts. README must state these endpoints are local/demo-only and need real auth, rate limiting, and audit logging before production use.

- [ ] **Step 6: Run targeted tests**

Run:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --tests '*relay*' --tests '*claim*' --tests '*reconciler*' --tests '*publication endpoint*' --tests '*health*' --tests '*metrics*' --tests '*structured logs*' --max-workers=1
```

Expected: PASS.

## Task 5: README Pair and Diagrams

**Files:**
- Create: `messaging/kafka-outbox-fallback/README.md`
- Create: `messaging/kafka-outbox-fallback/README.ko.md`
- Create diagram SVG/PNG assets under `docs/images/readme-diagrams/`
- Modify diagram validator scripts.

- [ ] **Step 1: Draft README.md in English**

Required sections:

- Language switch.
- Architecture image.
- Sequence image.
- State lifecycle image.
- Classic transactional outbox vs Kafka-first fallback comparison table.
- REST examples.
- Failure semantics.
- Not guaranteed.
- Operator runbook.
- Tests/running.
- Public API status mapping table for `OrderPublicationStatus`.
- Demo-admin endpoint response examples and disabled-by-default behavior.
- KDoc coverage note for public DTOs/controllers/services.

- [ ] **Step 2: Draft README.ko.md in natural Korean**

Keep source-equivalent content and language switch `[English](README.md) | 한국어`.

- [ ] **Step 3: Generate diagrams with bluetape4k-diagram rules**

Use shared Kafka/database icons. Hard visual gates:

- card-label overlaps = 0
- label-card overlaps = 0
- endpoint audit = PASS
- diagonal card-to-card connectors = 0 unless unavoidable and documented
- row centers and spacing reported
- PNG rendered and visually inspected

- [ ] **Step 4: Update validators**

Add new architecture and sequence basenames to existing allowlists. Add state validation only if a local state validator exists; otherwise validate SVG/PNG existence and references.

- [ ] **Step 5: Run docs/diagram checks**

Run:

```bash
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
rg -n "direct-publish-enabled|relay-enabled|reconciler-enabled|NOT_PUBLISHED|DEAD_LETTER|re-drive|rollback|migration|SELECT|UPDATE" messaging/kafka-outbox-fallback/README.md messaging/kafka-outbox-fallback/README.ko.md
git diff --check
```

Expected: PASS.

## Task 6: Root README, CI, Smoke, and Verification

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

- [ ] **Step 1: Add root README rows**

Add `messaging/kafka-outbox-fallback` under Messaging in both root README files. Mention targeted command:

```bash
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
```

- [ ] **Step 2: Update Examples workflow**

Add path filter:

```yaml
      - 'messaging/kafka-outbox-fallback/**'
```

Add sequential container lane task:

```bash
./gradlew :messaging-kafka:test :messaging-kafka-outbox-fallback:test --continue --max-workers=1
```

Add artifact path:

```yaml
messaging/kafka-outbox-fallback/build/test-results/test/
messaging/kafka-outbox-fallback/build/reports/tests/test/
```

- [ ] **Step 3: Update smoke script**

Add `:messaging-kafka-outbox-fallback:test` to the messaging group.

- [ ] **Step 4: Run full targeted verification**

Run:

```bash
./gradlew projects
./gradlew :messaging-kafka-outbox-fallback:test --max-workers=1
./gradlew :messaging-kafka-outbox-fallback:test --tests '*health*' --max-workers=1
bash -n scripts/smoke-validate.sh
./scripts/smoke-validate.sh messaging
actionlint .github/workflows/Examples.yml
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
rg -n "OrderPublicationStatus|PUBLISHED_DIRECT|FALLBACK_STORED|FALLBACK_STORE_FAILED|demo-admin|health|readiness|liveness" messaging/kafka-outbox-fallback/README.md messaging/kafka-outbox-fallback/README.ko.md
git diff --check
```

Expected: all pass. If `actionlint` is not installed, report the missing tool and validate workflow syntax through `gh workflow view Examples` or YAML parse as next-best evidence.

## Task 7: Lessons, Commit, PR, and Live Metadata

**Files:**
- Create: `docs/lessons/2026-06-29-issue-348-kafka-outbox-fallback.md`

- [ ] **Step 1: Write concise lesson**

Cover context, decision, outcome, verification evidence, and future-agent warnings:

- This pattern lowers hot-transaction DB work but weakens atomicity.
- Reconciler is loss-avoidance with duplicate risk.
- Safe publication endpoints must not expose raw payload/error.
- Diagram layout evidence must be real rendered PNG evidence.
- Article follow-up packet: final code paths, verified diagram paths, metric
  names, unsupported capabilities, classic-vs-fallback comparison anchors, and
  duplicate/idempotency warning.

- [ ] **Step 2: Commit with Lore protocol**

Commit message intent line:

```text
feat: teach Kafka-first outbox fallback trade-offs
```

Include trailers:

```text
Constraint: Issue #348 requires order-only hot transaction and Kafka-first publication with durable fallback.
Rejected: Redis Stream fallback in v1 | It would obscure the core outbox trade-off and add a second durability system.
Confidence: high
Scope-risk: moderate
Directive: Keep classic transactional-outbox intact; this module is a complementary at-least-once fallback example.
Tested: <commands that passed>
Not-tested: <only if any required check could not run>
```

- [ ] **Step 3: Push branch and open PR**

Before PR creation, refresh issue metadata:

```bash
gh issue view 348 --json assignees,milestone,labels,state,url
```

Use the returned assignee, milestone, and labels for `gh pr create` / `gh pr edit` where supported. PR body final section must be `## DoD Status`.

- [ ] **Step 4: Verify live PR metadata**

Run:

```bash
gh issue view 348 --json assignees,milestone,labels,state
gh pr view <number> --json assignees,milestone,labels,body,url
```

Expected: issue and PR assignee/milestone/labels are correct; PR body includes `## DoD Status`.
