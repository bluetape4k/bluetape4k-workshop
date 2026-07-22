# Issue #553 — Event-Sourced Usage Billing 구현 계획

> **For agentic execution:** REQUIRED SUB-SKILL: `executing-plans`. 이 계획은 사용자의 명시적 요청에 따라 현재 feature worktree의 main session에서 inline으로 실행한다. subagent 구현·리뷰를 사용하지 않으며 각 단계는 checkbox(`- [ ]`)로 추적한다.

**목표:** Java 25 Spring Boot 예제로 usage metering과 billing을 Event Sourcing 방식으로 구현해 optimistic append, deterministic replay, event evolution, snapshot, projection rebuild, correction, reconciliation을 PostgreSQL 권위 아래 실전 수준으로 증명한다.

**아키텍처:** 단일 Spring Boot modular application 안에서 command, event store, replay, projection, billing close, reconciliation 경계를 분리한다. `domain_events`와 stream head만 business authority이며 read model과 snapshot은 언제든 폐기·재생성할 수 있는 파생 데이터다. command 처리와 event append는 하나의 transaction으로 묶고 projection은 독립 transaction으로 따라간다.

**기술 스택:** Kotlin 2.4, Java 25 toolchain, Spring Boot 4 MVC/Security/Actuator, virtual threads, JetBrains Exposed JDBC/DAO, `bluetape4k-exposed-jdbc`, PostgreSQL, Testcontainers, Micrometer, JUnit 5, `bluetape4k-junit5`, `bluetape4k-assertions`.

**설계 기준:** `docs/superpowers/specs/2026-07-22-issue-553-event-sourced-usage-billing-design.md`

**중단 조건:** 이 문서의 자체 검토와 커밋까지만 수행한 뒤 사용자에게 구현 승인을 받는다. 구현 승인 후 Task 1부터 순서대로 실행하고, Task 14 검증·inline 6관점 리뷰·PR·CI를 통과해 merge-ready 상태를 보고한다. merge는 별도의 fresh approval 없이는 수행하지 않는다.

---

## 1. 고정 불변식

- business authority는 `event_stream_heads`와 append-only `domain_events`뿐이다. projection, snapshot, command receipt response를 금전 계산의 원본으로 사용하지 않는다.
- 모든 concrete repository는 `EventSourcingExposedJdbcRepository` 또는 `AppendOnlyEventSourcingExposedJdbcRepository`를 통해 Bluetape `ExposedJdbcRepository`를 구현한다.
- production과 test fixture 모두 Exposed DAO/DSL만 사용한다. `JdbcTemplate`, `Connection`, `PreparedStatement`, `Statement`, `Transaction.exec`, migration SQL을 추가하지 않는다.
- domain package는 Spring, Exposed, Jackson에 의존하지 않는다. reducer는 `(state, event) -> state`인 순수 함수이며 Clock, database, network를 참조하지 않는다.
- event append는 expected stream version CAS로 직렬화한다. 둘 이상의 stream을 한 command에서 갱신할 때 stream key를 사전식으로 정렬해 lock한다.
- event hash는 저장된 원본 envelope/payload의 canonical JSON을 SHA-256으로 계산한다. upcast된 payload나 projection 결과로 hash를 다시 만들지 않는다.
- event type과 schema version은 명시적 registry로 해석한다. unknown type/version은 조용히 건너뛰지 않고 quarantine과 health degradation으로 드러낸다.
- command receipt acquire와 domain event append/terminal response는 owner token으로 fencing한다. stale owner는 event나 terminal response를 남길 수 없다.
- snapshot은 optional optimization이다. stream version/hash 검증에 실패하면 폐기하고 genesis부터 replay한다.
- projection은 generation 단위로 분리한다. rebuild는 BUILDING generation에 쓰고 high-watermark catch-up 뒤 조건부로 ACTIVE를 전환한다.
- projector의 event marker, read-model mutation, checkpoint advance는 같은 transaction이다. global position은 gap을 허용하며 연속 번호를 가정하지 않는다.
- billing close는 projection이 아닌 `UsageAccepted` event를 stable keyset으로 읽고 당시의 price stream을 replay해 `UsageRated`를 append한다.
- correction은 기존 event, ledger, invoice를 수정하지 않는다. debit/credit correction event를 append하고 새 projection 결과로 반영한다.
- tenant는 JWT authority와 path/body의 tenant가 일치해야 한다. 모든 event store와 read-model query는 tenant predicate를 포함한다.
- Redis, Kafka, leader election, XA, generic Event Sourcing framework는 #553 범위에 넣지 않는다. microservices 적용은 README guide/diagram과 #555에서 다룬다.

## 2. 파일 지도

### baseline black-box contract

- 수정: `commerce/usage-metering-billing-ledger/build.gradle.kts`
- 생성: `commerce/usage-metering-billing-ledger/src/testFixtures/kotlin/io/bluetape4k/workshop/commerce/metering/contract/UsageBillingHttpContract.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/testFixtures/kotlin/io/bluetape4k/workshop/commerce/metering/contract/UsageBillingContractModels.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/UsageBillingBaselineContractTest.kt`

### advanced module과 domain

- 생성: `commerce/usage-metering-billing-event-sourcing/build.gradle.kts`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/UsageBillingEventSourcingApplication.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/resources/application.yml`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/EventContracts.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/AggregateContracts.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/MeterEvents.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/UsageEvents.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/BillingEvents.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/domain/AggregateReducers.kt`

### event store, idempotency, projection

- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/CanonicalEventHash.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventCodecRegistry.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventUpcasters.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/EventStore.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/AggregateReplayer.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/eventstore/SnapshotStore.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandFingerprint.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/idempotency/CommandReceiptService.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionContracts.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionCoordinator.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionRebuilder.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/projection/ProjectionHandlers.kt`

### Exposed persistence와 application

- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/MeteringEventsTables.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/MeteringEventsEntities.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/EventSourcingExposedJdbcRepository.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/EventStoreRepository.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/CommandReceiptRepository.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/SnapshotRepository.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/ProjectionRepositories.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/BillingReadModelRepositories.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/persistence/MeteringEventsJdbcExecutor.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/MeterCommandService.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/UsageCommandService.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/BillingCloseService.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/AdjustmentService.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/application/ReconciliationService.kt`

### runtime, web, docs

- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/config/EventSourcingProperties.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/config/EventSourcingConfiguration.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/config/EventSourcingMetrics.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/config/EventSourcingHealthIndicators.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/worker/ProjectionWorker.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/worker/CommandReceiptCleanupWorker.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingApiModels.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingControllers.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingSecurityConfiguration.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/eventsourcing/web/EventSourcingExceptionHandler.kt`
- 생성: `commerce/usage-metering-billing-event-sourcing/README.md`
- 생성: `commerce/usage-metering-billing-event-sourcing/README.ko.md`
- 생성: `scripts/generate-usage-billing-event-sourcing-diagrams.mjs`
- 생성: `scripts/validate-usage-billing-event-sourcing-readme.mjs`
- 생성: `docs/images/readme-diagrams/usage-billing-event-sourcing-{architecture,aggregate-state,command-sequence,replay-sequence,projection-state,rebuild,billing-correction,microservices}-01.{svg,png}`
- 생성: `docs/lessons/2026-07-22-issue-553-event-sourced-usage-billing.md`
- 생성: `docs/reviews/2026-07-22-issue-553-event-sourced-usage-billing-review.md`

---

## 3. 순차 구현 작업

### Task 1: baseline과 advanced가 공유할 HTTP black-box 계약을 추출한다

**의존:** 승인된 spec/plan

**파일:** baseline black-box contract 항목 전체

- [ ] **RED:** baseline의 meter 등록, price activation, usage ingest/replay/conflict, period close, invoice 조회, late adjustment HTTP 동작을 동일한 adapter로 실행하는 `UsageBillingBaselineContractTest`를 작성한다.
- [ ] baseline build에 `java-test-fixtures`를 적용하고 contract가 Spring production bean이나 baseline persistence type을 import하지 못하도록 package/import architecture test를 추가한다.
- [ ] contract는 HTTP method/path/header/body/status와 의미적 assertion만 제공한다. database fixture, repository, internal DTO는 공유하지 않는다.
- [ ] baseline의 기존 `MeteringHttpIntegrationTest`와 새 contract가 같은 동작을 증명하고 기존 응답을 바꾸지 않음을 확인한다.
- [ ] **GREEN:** 아래를 통과시킨다.

```bash
./gradlew :commerce-usage-metering-billing-ledger:test \
  --tests '*UsageBillingBaselineContractTest' \
  --tests '*MeteringControllerBoundaryTest'
```

- [ ] Lore commit: `Share billing behavior without sharing billing internals`.

### Task 2: Java 25 Spring Boot advanced module과 architecture guard를 등록한다

**의존:** Task 1

**파일:** advanced module/application/config 기본 파일, `EventSourcingRuntimeContractTest.kt`, `KotlinPatternArchitectureTest.kt`

- [ ] **RED:** project path, Java/Kotlin toolchain 25, main class, preview 미사용, default test의 integration/stress 제외, domain dependency 방향을 검사한다.
- [ ] `./gradlew :commerce-usage-metering-billing-event-sourcing:test`가 project 부재로 실패함을 확인한다.
- [ ] #552 build를 구조 기준으로 삼되 새 module은 `testImplementation(testFixtures(project(":commerce-usage-metering-billing-ledger")))`만 추가한다. production project dependency는 금지한다.
- [ ] root `bluetape4k-dependencies` BOM만 사용하고 version catalog에 Bluetape 개별 version/BOM을 추가하지 않는다.
- [ ] `test`, `integrationTest`, `stressTest`에 test mutex, `failOnZero`, non-empty XML 검증을 설정한다.
- [ ] domain package에서 `org.springframework`, `org.jetbrains.exposed`, `com.fasterxml.jackson` import를 금지한다.
- [ ] production의 `!!`, `println`, broad unlogged exception handling을 architecture test로 금지한다.
- [ ] **GREEN:** `./gradlew projects | rg 'commerce-usage-metering-billing-event-sourcing'`와 runtime/architecture test를 통과시킨다.
- [ ] Lore commit: `Isolate the event-sourced billing learning boundary`.

### Task 3: event contract, reducer, canonical hash, upcaster를 TDD로 구현한다

**의존:** Task 2

**파일:** domain/eventstore contract 파일, `EventContractTest.kt`, `AggregateReducerTest.kt`, `EventCodecRegistryTest.kt`, `CanonicalEventHashTest.kt`

- [ ] **RED:** blank/oversized tenant/stream/type, negative version, duplicate registry key, unknown type/version, non-contiguous upcast chain, payload field order 차이, hash-chain tamper를 검사한다.
- [ ] `DomainEvent`를 sealed interface로 두고 persisted envelope와 decoded domain event를 분리한다.

```kotlin
data class PersistedEvent(
    val eventId: UUID,
    val tenantId: String,
    val streamType: String,
    val streamId: String,
    val streamVersion: Long,
    val globalPosition: Long,
    val eventType: String,
    val schemaVersion: Int,
    val payload: String,
    val metadata: String,
    val previousHash: String?,
    val eventHash: String,
    val occurredAt: Instant,
    val recordedAt: Instant,
)
```

- [ ] Meter, Usage, BillingPeriod, Invoice, Adjustment event와 state/reducer를 exhaustive `when`으로 정의한다. reducer가 I/O와 현재 시간에 접근하지 않는지 architecture test로 잠근다.
- [ ] canonical hash input은 tenant/stream/version/type/schema/original payload/metadata/previous hash를 고정 순서 UTF-8로 직렬화한다.
- [ ] registry는 `(eventType, schemaVersion)` decoder와 version별 upcaster chain을 시작 시 검증한다. hash 검증 후에만 upcast한다.
- [ ] golden JSON/hash fixture로 직렬화 안정성을 잠근다.
- [ ] **GREEN:** domain/eventstore unit tests와 `detektTest`를 통과시킨다.
- [ ] Lore commit: `Keep event meaning deterministic across schema evolution`.

### Task 4: Exposed event store와 optimistic multi-stream append를 구현한다

**의존:** Task 3

**파일:** persistence base/tables/entities/event store repository/executor, `EventStoreDatabaseFixture.kt`, `EventStorePostgresIntegrationTest.kt`, `RepositoryArchitectureTest.kt`

- [ ] **RED:** 모든 concrete repository의 `ExposedJdbcRepository` assignability, append-only generic mutation 거부, unique stream version, expected version conflict, hash chain, tenant isolation, global-position gap 허용, sorted multi-stream lock을 검사한다.
- [ ] 다음 Bluetape delegate를 module repository root로 사용한다.

```kotlin
abstract class EventSourcingExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
```

- [ ] append-only subclass는 `save*`, `delete*`를 final override해 거부하고 전용 `append`만 노출한다.
- [ ] `event_stream_heads`에 `(tenant_id, stream_type, stream_id)` unique와 version/latest hash를, `domain_events`에 stream/version unique 및 global scan/close scan index를 정의한다.
- [ ] append는 head를 insert-ignore 후 `FOR UPDATE`, expected version 비교, event batch insert, head CAS 순서로 수행한다. multi-stream command는 정렬된 stream key 순서로 head를 잠근다.
- [ ] fixture reset/seed는 `SchemaUtils`와 repository/service만 사용한다.
- [ ] 동시 append 40개 중 expected version을 만족한 하나만 성공하고 나머지는 명시적 conflict임을 PostgreSQL로 증명한다.
- [ ] raw SQL 금지 scan을 통과시킨다.

```bash
if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("|java\.sql\.|src/.*/db/migration' \
  commerce/usage-metering-billing-event-sourcing; then exit 1; fi
```

- [ ] Lore commit: `Make the event log the only writable billing history`.

### Task 5: fenced command receipt와 atomic command transaction을 구현한다

**의존:** Task 4

**파일:** idempotency files/repository/cleanup worker, `CommandReceiptPostgresIntegrationTest.kt`, `AtomicCommandIntegrationTest.kt`

- [ ] **RED:** same key replay, fingerprint mismatch, active lease, expired takeover, stale owner append/terminal CAS 거부, response limit, rollback 시 event/terminal 부재, commit 후 exact response replay를 검사한다.
- [ ] receipt row에 tenant/command/key hash/fingerprint/status/owner token/lease/response를 저장하고 raw key/body는 저장하지 않는다.
- [ ] acquire/takeover는 짧은 `REQUIRES_NEW` transaction으로 `Acquired`, `Replay`, `InProgress`, `Conflict`를 반환한다.
- [ ] acquired owner의 main transaction 안에서 owner token을 재검증하고 event append와 `SUCCEEDED` response 저장을 원자적으로 수행한다. business rejection은 stable `FAILED` response로 남긴다.
- [ ] infrastructure failure는 transaction rollback 후 lease takeover가 가능하게 하며 stale owner는 이후 append할 수 없다.
- [ ] terminal cleanup은 keyset과 bounded batch를 사용하고 active/expired-but-recoverable receipt는 삭제하지 않는다.
- [ ] **GREEN:** receipt/atomic command integration tests를 반복 10회 통과시킨다.
- [ ] Lore commit: `Fence retries before they can duplicate event history`.

### Task 6: replay와 validated snapshot optimization을 구현한다

**의존:** Tasks 3-5

**파일:** replayer/snapshot files/repository, `AggregateReplayTest.kt`, `SnapshotPostgresIntegrationTest.kt`

- [ ] **RED:** empty stream, full replay, snapshot 이후 tail replay, stale version, wrong event hash, corrupt payload, reducer version mismatch, snapshot 없는 fallback을 검사한다.
- [ ] replayer는 stream event hash chain을 검증하고 upcast/decoded event를 reducer에 적용한다.
- [ ] snapshot은 `(tenant, stream, streamVersion, reducerVersion)` append-only record로 저장하며 state payload와 last event hash를 포함한다.
- [ ] snapshot validation 실패는 request 실패가 아니라 해당 snapshot 무시+metric 증가+genesis replay로 처리한다. event log 자체 hash 오류는 즉시 실패/quarantine한다.
- [ ] snapshot threshold와 retention은 property로 제한하고 snapshot 생성 실패가 command commit을 rollback하지 않게 post-commit optimization으로 분리한다.
- [ ] 동일 stream을 snapshot 사용/미사용으로 replay해 state와 version이 완전히 같음을 property-style test로 증명한다.
- [ ] Lore commit: `Let snapshots accelerate replay without becoming authority`.

### Task 7: generation 기반 projection substrate와 lease를 구현한다

**의존:** Tasks 4-6

**파일:** projection contract/coordinator/rebuilder, projection repositories, `ProjectionCoordinatorPostgresIntegrationTest.kt`, `ProjectionGenerationTest.kt`

- [ ] **RED:** ACTIVE 단일성, BUILDING 격리, duplicate event, crash before/after checkpoint, expired lease takeover, stale owner checkpoint CAS, global-position gap, generation switch/rollback을 검사한다.
- [ ] generation state를 `ACTIVE`, `BUILDING`, `FAILED`, `RETIRED`로 제한하고 `(projection_name, generation)`별 checkpoint/owner token/lease를 둔다.
- [ ] projector transaction은 applied-event marker insert, handler mutation, checkpoint update를 함께 commit한다. marker 충돌은 이미 적용된 event로 간주하되 checkpoint만 안전하게 전진시킨다.
- [ ] lease acquire/renew/release와 checkpoint update는 owner token fencing 조건을 포함한다.
- [ ] global position은 `>` keyset으로 읽고 gap을 허용한다. 빈 batch에서 max sequence를 추론하지 않는다.
- [ ] rebuild는 시작 high-watermark를 고정하고 BUILDING generation replay 후 tail catch-up, lag 0 확인, ACTIVE conditional switch 순서로 수행한다.
- [ ] 실패 generation은 원인/event position을 기록하고 기존 ACTIVE를 유지한다. 이전 generation은 retention 동안 rollback 대상으로 보존한다.
- [ ] Lore commit: `Rebuild projections without replacing a healthy read path`.

### Task 8: billing read models, poison quarantine, projector를 구현한다

**의존:** Task 7

**파일:** handlers/read-model repositories, `ProjectionHandlersTest.kt`, `ProjectionRecoveryPostgresIntegrationTest.kt`

- [ ] **RED:** usage timeline, ledger, invoice, operator timeline, reconciliation projection의 deterministic 결과와 tenant/generation 격리를 검사한다.
- [ ] read-model table은 projection name/generation/tenant를 key에 포함하고 모든 concrete repository가 module Exposed base를 상속한다.
- [ ] handler는 한 event의 read-model mutation만 담당하며 external I/O를 하지 않는다.
- [ ] unknown/corrupt event는 bounded failure row에 event id/type/position/error digest/attempt count를 기록하고 projection을 FAILED/degraded로 전환한다.
- [ ] financial projection에는 skip API를 제공하지 않는다. operator는 registry/code 수정 후 같은 event부터 rebuild/retry한다.
- [ ] failure payload에 원문 event body, JWT, idempotency key를 복제하지 않는다.
- [ ] projector 재시작, duplicate delivery, crash injection 후 결과가 clean replay와 같은지 PostgreSQL로 증명한다.
- [ ] Lore commit: `Expose projection failure instead of skipping financial truth`.

### Task 9: meter, price, usage, period, close, invoice command를 구현한다

**의존:** Tasks 5-8

**파일:** command services, application unit/integration tests

- [ ] **RED:** meter 등록, non-overlap price activation, duplicate usage, source conflict, period state, restartable close, invoice issuance, concurrent close를 event/result 기준으로 검사한다.
- [ ] command service는 stream replay로 invariant를 판단하고 expected version과 새 event 목록만 event store에 전달한다.
- [ ] usage source uniqueness는 deterministic Usage stream id 또는 dedicated source-authority stream으로 표현해 concurrent duplicate가 하나의 `UsageAccepted`만 만들게 한다.
- [ ] period close는 projection을 읽지 않고 `UsageAccepted` event를 `(occurredAt, eventId)` stable keyset으로 직접 스캔한다.
- [ ] batch 시작 전에 필요한 price stream id 집합을 모아 정렬된 순서로 replay하고 각 source usage당 하나의 `UsageRated`를 append한다.
- [ ] close checkpoint/event와 rated events를 sorted multi-stream atomic append로 commit한다. crash 후 같은 checkpoint에서 재시작해 중복 rated event가 없어야 한다.
- [ ] invoice는 finalized close events를 replay해 발행하고 이미 발행된 invoice stream은 idempotent response를 반환한다.
- [ ] concurrent close 20회, 중간 crash, 재시작에서 ledger/invoice 합계가 단일 실행과 같은지 PostgreSQL로 증명한다.
- [ ] Lore commit: `Calculate invoices from replayable usage history`.

### Task 10: late correction과 stale-safe reconciliation을 구현한다

**의존:** Task 9

**파일:** adjustment/reconciliation services, `CorrectionIntegrationTest.kt`, `ReconciliationIntegrationTest.kt`

- [ ] **RED:** finalized period late usage, debit/credit correction, duplicate correction, wrong currency, stale repair digest, concurrent repair, projection-only corruption을 검사한다.
- [ ] finalized history는 수정하지 않고 Adjustment stream에 reason/source link/amount/currency를 포함한 event를 append한다.
- [ ] correction projection은 원 ledger/invoice와 별도 provenance를 유지하고 합산 query에서 방향을 보존한다.
- [ ] reconciliation은 event-store replay 결과와 ACTIVE projection 결과를 비교해 missing, extra, amount mismatch, provenance mismatch, checkpoint anomaly로 분류한다.
- [ ] inspect는 read-only다. repair command는 finding digest, observed generation/position, expected stream versions를 재검증한 뒤 correction append 또는 generation rebuild만 수행한다.
- [ ] stale finding은 409로 거부하며 raw row overwrite/delete repair는 제공하지 않는다.
- [ ] clean replay 뒤 finding 0, projection 행 변조 뒤 finding 검출, rebuild 뒤 finding 0을 증명한다.
- [ ] Lore commit: `Correct billing history by adding evidence, never rewriting it`.

### Task 11: HTTP, security, consistency wait, metrics, health, workers를 연결한다

**의존:** Tasks 7-10

**파일:** web/config/worker files, `EventSourcingHttpIntegrationTest.kt`, `SecurityBoundaryTest.kt`, `ActuatorContractTest.kt`

- [ ] **RED:** idempotency header, tenant mismatch, authority matrix, validation problem detail, projection headers, bounded wait 성공/timeout, operator endpoint 권한을 검사한다.
- [ ] baseline과 호환되는 command/query endpoint를 제공하고 advanced operator endpoint는 `/api/admin/event-sourcing/**` 아래 분리한다.
- [ ] query response에 `Projection-Position`, `Projection-Lag`를 넣는다. `X-Wait-For-Position`은 설정된 최대 대기시간까지만 polling하고 미도달 시 `409 projection_not_caught_up`을 반환한다.
- [ ] `TENANT_BILLING_WRITE`, `TENANT_BILLING_READ`, `ROLE_OPERATOR` authority와 JWT tenant claim을 path/body tenant와 교차 검증한다.
- [ ] metrics는 append conflict/latency, replay count/duration, snapshot fallback, projection lag/failure/rebuild, quarantine, close batch, reconciliation finding을 bounded tag로 기록한다.
- [ ] health는 event store connectivity, ACTIVE projection 존재/lag, FAILED generation/quarantine을 구분해 readiness detail을 제공한다.
- [ ] projection/cleanup worker는 같은 application use case를 호출하며 PostgreSQL lease/fencing으로 multi-instance 중복 수행을 막는다.
- [ ] live `@SpringBootTest`에서 security, controller, service, Exposed transaction, PostgreSQL을 함께 검증한다.
- [ ] Lore commit: `Make eventual consistency explicit at the HTTP boundary`.

### Task 12: parity, evolution, recovery, 10k stress를 PostgreSQL에서 증명한다

**의존:** Tasks 1-11

**파일:** `UsageBillingAdvancedContractTest.kt`, `EventEvolutionIntegrationTest.kt`, `ProjectionRebuildIntegrationTest.kt`, `BillingEventSourcingEndToEndTest.kt`, `BillingEventSourcingStressTest.kt`

- [ ] baseline test fixture의 `UsageBillingHttpContract`를 advanced HTTP adapter로 실행해 공통 business contract parity를 증명한다.
- [ ] v1 event fixture를 최신 reducer로 upcast/replay하고 original hash가 유지됨을 검증한다.
- [ ] snapshot corrupt/stale fallback, projector crash/restart, BUILDING failure, ACTIVE switch/rollback, command owner takeover를 통합 시나리오로 실행한다.
- [ ] 두 tenant에 같은 external ids를 넣어 stream, projection, receipt, reconciliation이 섞이지 않음을 검증한다.
- [ ] 10,000 usage events를 append하고 close/replay/rebuild를 수행해 count, 금액 합계, provenance, hash chain, zero reconciliation finding을 검증한다.
- [ ] stress test는 절대 시간 SLA를 pass/fail 기준으로 삼지 않고 소요시간/throughput/peak batch를 XML 및 로그에 남긴다.
- [ ] 각 custom test task의 JUnit XML과 Kover report가 비어 있으면 build가 실패하도록 확인한다.
- [ ] **GREEN:** 아래 targeted suite를 통과시킨다.

```bash
./gradlew :commerce-usage-metering-billing-event-sourcing:test
./gradlew :commerce-usage-metering-billing-event-sourcing:integrationTest
./gradlew :commerce-usage-metering-billing-event-sourcing:stressTest
./gradlew :commerce-usage-metering-billing-event-sourcing:koverXmlReport
```

- [ ] Lore commit: `Prove replay recovery under realistic billing volume`.

### Task 13: README, diagrams, microservice guide, CI/validation matrix를 등록한다

**의존:** Tasks 11-12

**파일:** module README 2종, diagram generator/assets, validator, root README 2종, validation matrix, workflows/scripts, lesson

- [ ] `bluetape-diagram` skill을 읽고 architecture, command sequence, projection state, rebuild, microservices SVG/PNG를 source-first generator로 만든다.
- [ ] README.ko.md는 빠른 실행, 핵심 불변식, event envelope, append/replay/projection/rebuild/correction 운영 절차, 장애 복구 runbook을 쉬운 한국어로 설명한다. README.md는 동일한 public contract를 영어로 제공한다. 두 README는 PNG를 authoritative embed로 사용하고 SVG source를 함께 링크한다.
- [ ] aggregate state diagram은 Meter/Usage/BillingPeriod/Invoice/Adjustment의 허용 전이를, projection state diagram은 `ACTIVE/BUILDING/FAILED/RETIRED` 전이와 금지 전이를 표시한다. replay sequence는 hash 검증→upcast→reduce→snapshot fallback을, rebuild diagram은 high-watermark/catch-up/switch/rollback을, billing correction diagram은 original event를 변경하지 않는 debit/credit 흐름을 표시한다.
- [ ] microservice guide는 #555를 링크하고 Meter/Usage/Billing/Invoice/Projection 서비스별 database ownership, Kafka event transport, transactional outbox/inbox, at-least-once, no XA/no broker exactly-once를 설명한다.
- [ ] root README/README.ko에 baseline과 advanced 선택 기준을 나란히 등록한다.
- [ ] validation matrix project 수를 112로 갱신하고 module을 full container-backed group에 넣는다.
- [ ] `Examples.yml`, `nightly.yml`, `scripts/smoke-validate.sh`에 integration/Kover/non-empty XML/report artifact를 등록한다.
- [ ] validator는 README section/diagram 쌍/issue link/Java25/Exposed/BOM/project count/workflow registration을 검사한다.
- [ ] lesson은 설계 선택, 실패한 접근, Exposed 구현 교훈, replay/projection recovery evidence, #555/#1070 후속 경계를 한국어로 기록한다.
- [ ] **GREEN:** 아래를 통과시킨다.

```bash
node scripts/generate-usage-billing-event-sourcing-diagrams.mjs --check
node scripts/validate-usage-billing-event-sourcing-readme.mjs
xmllint --noout docs/images/readme-diagrams/usage-billing-event-sourcing-*.svg
git diff --check
```

- [ ] Lore commit: `Teach operators how to rebuild billing truth safely`.

### Task 14: 전체 검증, 6관점 inline 리뷰, PR delivery를 완료한다

**의존:** Tasks 1-13

- [ ] targeted unit → integration → stress → module build 순으로 fresh 실행한다.
- [ ] `./gradlew :commerce-usage-metering-billing-ledger:test`로 shared contract 추출이 baseline을 깨지 않았음을 재검증한다.
- [ ] `./gradlew :commerce-usage-metering-billing-event-sourcing:build detekt detektTest`와 validator/diagram/XML/raw-SQL scan/`git diff --check`를 실행한다.
- [ ] local JDK 25 hang이 재현되면 unrelated Java process를 종료하지 않는다. 실행한 command, timeout/마지막 출력, 통과한 하위 검증을 기록하고 exact-head GitHub CI를 최종 build authority로 사용한다.
- [ ] inline으로 architecture, data/consistency, security/tenant, failure/recovery, test/operability, documentation/adoption 6관점을 독립적으로 검토한다.
- [ ] P0/P1은 모두 수정한다. P2는 수정하거나 근거와 후속 이슈를 기록한다. `docs/reviews/2026-07-22-issue-553-event-sourced-usage-billing-review.md`에 한국어로 남긴다.
- [ ] issue #553 acceptance/DoD와 아래 traceability를 다시 대조한다.
- [ ] Lore protocol로 최종 변경을 commit하고 remote feature branch에 push한다.
- [ ] English PR body에 issue link, architecture, event authority, Exposed-only proof, tests, diagrams, known local validation gap을 기록한다.
- [ ] exact local/remote head 일치, CI success, review thread 0, human-review artifact를 확인해 merge-ready만 보고한다.
- [ ] fresh merge approval 전에는 merge하지 않는다. 승인 후 rebase merge, local `develop` fast-forward, issue worktree와 local feature branch 제거, unrelated worktree 보존까지 수행한다.

---

## 4. RED/GREEN 검증 순서

1. contract 또는 invariant test를 먼저 작성해 의도한 이유로 실패하는지 확인한다.
2. 가장 작은 production 변경으로 targeted test를 통과시킨다.
3. 같은 package/module의 인접 regression test를 실행한다.
4. PostgreSQL correctness는 Testcontainers integration test로만 승인한다. H2나 mock으로 대체하지 않는다.
5. task 경계마다 `detektTest`와 raw-SQL scan을 실행한다.
6. 전체 build는 모든 targeted evidence가 모인 뒤 실행한다.

## 5. Acceptance criteria traceability

| 설계/DoD 요구 | 구현 Task | 증명 |
|---|---:|---|
| Java 25 Spring Boot 전용 | 2 | runtime contract, Gradle toolchain |
| ExposedJdbcRepository 강제, raw SQL 없음 | 4, 7, 8 | repository architecture test, forbidden scan |
| append-only event store + optimistic concurrency | 4 | concurrent PostgreSQL append test |
| fenced idempotent command | 5 | takeover/stale-owner/atomic rollback test |
| deterministic replay + hash chain | 3, 6 | golden hash, replay equivalence, tamper test |
| schema evolution/upcast | 3, 12 | v1 fixture to latest state integration test |
| snapshot은 optional | 6, 12 | corrupt/stale fallback test |
| generation rebuild/switch/rollback | 7, 12 | crash/rebuild/conditional switch test |
| poison event 무시 금지 | 8 | quarantine + FAILED health test |
| event store 기반 billing close | 9 | projection corruption 중 close correctness test |
| immutable correction | 10 | debit/credit provenance test |
| reconciliation과 stale-safe repair | 10 | digest race/rebuild test |
| tenant/security/consistency headers | 11, 12 | JWT boundary, wait timeout, two-tenant test |
| baseline HTTP parity | 1, 12 | shared black-box contract |
| 10k usage recovery proof | 12 | stress count/amount/hash/reconciliation assertions |
| 쉬운 README/state/rebuild/microservice guide | 13 | validator, SVG/PNG check |
| CI/validation matrix/smoke registration | 13, 14 | scripts/workflow checks, exact-head CI |

## 6. 예상 위험과 선제 대응

| 위험 | 선제 대응 | 실패 신호 |
|---|---|---|
| projection을 billing authority로 사용 | close query를 event repository port로 제한 | projection 삭제 시 invoice 결과 변화 |
| generic repository가 append-only row 수정 | append-only base의 final mutation rejection | architecture/contract test 실패 |
| upcast 후 hash 계산으로 감사성 상실 | original payload hash를 decoder 전 검증 | golden v1 hash 변화 |
| multi-stream deadlock | stream key lexical lock order | concurrency test timeout/deadlock |
| receipt lease owner가 바뀐 뒤 stale commit | main transaction 시작 시 owner token 재검증 | stale owner가 event append |
| global sequence gap에서 projector 정지 | `position > checkpoint` keyset | gap fixture 뒤 lag 고정 |
| rebuild가 ACTIVE를 오염 | generation을 모든 read-model key에 포함 | BUILDING 실패 후 query 변화 |
| poison event skip으로 금전 누락 | financial skip API 금지, FAILED/quarantine | checkpoint가 poison position을 넘음 |
| 10k test가 flaky SLA test가 됨 | correctness assertion과 관측 metric만 gate | 시간 임계치만으로 실패 |
| baseline production 결합 | test fixture HTTP contract만 의존 | advanced main source가 baseline import |

## 7. 계획 자체 검토 기준

- [x] spec 1-36장의 요구가 traceability 또는 Task에 매핑된다.
- [x] 모든 Task에 dependency, exact file, RED, GREEN, verification, commit point가 있다.
- [x] type/package 이름이 파일 지도와 Task 사이에서 일치한다.
- [x] production/test fixture 모두 Exposed-only이며 모든 concrete repository가 `ExposedJdbcRepository`를 구현한다.
- [x] event authority와 projection/snapshot 파생 경계가 어떤 경로에서도 뒤집히지 않는다.
- [x] P0/P1 발견 사항이 0이 될 때까지 architecture, data, security, recovery, test, docs 6관점을 inline 재검토한다.
- [x] placeholder와 모호한 실행어가 없다.
- [x] `git diff --check`를 통과한다.

## 8. 계획 자체 검토 결과

검토일: 2026-07-22

| 관점 | 결과 | 확인 내용 |
|---|---|---|
| Architecture | PASS | event store 권위, domain dependency 방향, baseline production 격리, microservice 후속 경계가 일관된다. |
| Data/Consistency | PASS | expected-version append, hash chain, sorted multi-stream lock, atomic receipt, generation checkpoint, event-store 기반 close가 테스트에 연결된다. |
| Security/Tenant | PASS | repository tenant parameter, JWT tenant 교차 검증, `ROLE_OPERATOR`, payload/log 제한, cross-tenant test가 포함된다. |
| Failure/Recovery | PASS | stale owner, corrupt snapshot, poison event, projector crash, failed rebuild, stale repair의 복구 경로와 증명이 있다. |
| Test/Operability | PASS | unit→PostgreSQL integration→parity→10k stress→exact-head CI 순서와 local JDK hang 대응이 명시돼 있다. |
| Documentation/Adoption | PASS | baseline 선택표, aggregate/projection state, replay/rebuild/correction, operator runbook, #555 microservice guide가 등록돼 있다. |

- P0: 0
- P1: 0
- 자체 검토에서 발견한 plan consistency 2건을 반영했다: operator authority를 spec의 `ROLE_OPERATOR`로 통일했고, diagram 범위를 aggregate state/replay/correction까지 확장했다.
- placeholder scan, 14개 Task 존재, 핵심 spec keyword traceability, `git diff --check`: PASS.
