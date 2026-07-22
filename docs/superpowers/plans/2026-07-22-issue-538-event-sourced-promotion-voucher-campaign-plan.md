# Issue #538 — Event-sourced Promotion & Voucher Campaign 구현 계획

> **실행 담당 agent:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`로 이 계획을 task 단위로 실행한다. 각 단계는 checkbox(`- [ ]`)로 추적하고, task마다 RED → GREEN → 검증 → Lore commit 순서를 지킨다.

**목표:** Java 25 Spring Boot 예제로 promotion campaign과 voucher lifecycle을 append-only PostgreSQL event store에 기록하고, 재시작 가능한 비동기 projection·snapshot·rebuild·운영 UI와 #534 black-box 호환성을 증명한다.

**아키텍처:** command 경로는 aggregate를 snapshot+tail로 복원한 뒤 expected-version CAS와 commit-safe global position fence로 event를 원자적으로 append한다. query 경로는 fencing token으로 보호되는 비동기 projection만 읽으며, 검증된 rebuild generation만 활성화한다. PostgreSQL이 event 순서, idempotency, lease, checkpoint, append-only 권한의 유일한 correctness authority다.

**기술 스택:** Kotlin 2.4, Java 25 toolchain, Spring Boot 4 MVC/Tomcat/Security/Actuator, virtual threads, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL, Testcontainers, Micrometer, Awaitility, JUnit 5, `bluetape4k-junit5`, `bluetape4k-assertions`.

**설계 기준:** `docs/superpowers/specs/2026-07-22-issue-538-event-sourced-promotion-voucher-campaign-design.md`

**중단 조건:** Task 16의 전체 검증과 관점별 코드 리뷰가 끝나고 exact-head PR의 CI/리뷰 결과를 보고한다. merge는 별도의 fresh approval 없이는 수행하지 않는다.

---

## 1. 고정 불변식

- module은 Java/Kotlin 25로 compile·test·runtime을 검증하고 preview feature를 사용하지 않는다.
- Bluetape 의존성 버전은 root의 `bluetape4k-dependencies` BOM만 사용한다. 개별 BOM이나 명시적 Bluetape version을 추가하지 않는다.
- 모든 concrete JDBC repository는 `EventSourcedExposedJdbcRepository` 또는 append-only 파생형을 통해 `ExposedJdbcRepository`를 구현한다. service가 transaction을 소유하고 repository 함수마다 `Connection`을 받지 않는다.
- 일반 persistence와 test fixture는 Exposed DAO/DSL을 사용한다. `JdbcTemplate`, raw `Connection`, `PreparedStatement`, `Statement`를 사용하지 않는다.
- `Transaction.exec`는 PostgreSQL owner/application role, REVOKE/GRANT, append-only trigger, session-local timeout처럼 Exposed가 표현하지 못하는 DDL과 session setting에만 별도 initializer 안에서 허용한다. event CRUD에는 사용하지 않는다.
- event log는 append-only다. `(tenantId, streamType, streamId, streamVersion)` unique constraint와 expected-version CAS가 단일 stream concurrency authority다.
- global position은 append fence를 잠근 transaction 안에서 연속 범위를 발급한다. projector는 committed head까지만 읽어 concurrent commit-order inversion에서 누락하지 않는다.
- idempotency scope는 tenant+principal digest+operation+resource+key digest다. same fingerprint는 terminal response를 replay하고 다른 fingerprint는 거부한다.
- projection handler와 rebuild worker는 lease generation과 fencing token을 checkpoint·row mutation CAS마다 검증한다. stale/cancelled worker는 쓰지 못한다.
- snapshot은 optimization일 뿐 authority가 아니다. snapshot+tail과 full replay의 aggregate version/canonical digest가 같아야 한다.
- active projection generation은 검증된 새 generation으로만 transactionally 교체한다. BUILDING/CANCELLED/FAILED generation은 query authority가 아니다.
- foreground/background/readiness permit은 14/3/1/1/1 예산을 지키며 readiness와 graceful shutdown을 starvation에서 보호한다.
- `Thread.sleep`을 test synchronization에 사용하지 않는다. 비동기 통합 검증은 Awaitility, coroutine 단위 검증은 injected dispatcher와 virtual time을 사용한다.
- mutable collection, `!!`, unchecked platform type, 민감정보 원문을 public API/event/log/metric/tag에 노출하지 않는다.
- #534 public HTTP/SSE/failure fixture를 유지하고 projection lag는 `202 PROJECTION_PENDING`으로 명시한다.

## 2. 파일 지도

### module과 runtime

- `commerce/event-sourced-promotion-voucher-campaign/build.gradle.kts`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/EventSourcedVoucherApplication.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/resources/application.yml`
- `commerce/event-sourced-promotion-voucher-campaign/src/test/resources/junit-platform.properties`
- `commerce/event-sourced-promotion-voucher-campaign/src/test/resources/logback-test.xml`

### domain, authority, command

- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/EventEnvelope.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/CampaignAggregate.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/VoucherAggregate.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/VoucherEvents.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/EventRegistry.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/domain/EventUpcasters.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventStoreTables.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/ProjectionTables.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventStoreEntities.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventSourcedExposedJdbcRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventStoreRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventStoreTransactionRunner.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/persistence/EventStoreSchemaInitializer.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/idempotency/EventSourcedHttpIdempotencyRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/command/EventStorePort.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/command/EventSourcedCommandService.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/command/CampaignCommandService.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/command/VoucherCommandService.kt`

### projection, operations, web

- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/snapshot/SnapshotRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionModels.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionHandlers.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionLeaseRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionWorker.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/projection/ProjectionRebuildService.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/DatabasePermitGate.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/EventSourcedLifecycle.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/EventSourcedHealthIndicators.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/EventSourcedMetrics.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/OperatorAuditRepository.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/operations/MaintenanceWorker.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/security/SubjectSurrogateService.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/EventSourcedApiModels.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/CustomerVoucherController.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/OperatorVoucherController.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/ProjectionOperationsController.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/EventSourcedExceptionHandler.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/OperatorAccessFilter.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/web/EventSourcedEventStream.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/main/resources/static/event-sourced-voucher/{index.html,app.js,styles.css}`

### fixture, documentation, registration

- `commerce/event-sourced-promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/fixture/EventSourcedVoucherFixtures.kt`
- `commerce/event-sourced-promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/eventsourced/fixture/NormalizedCompatibilityFixture.kt`
- `commerce/event-sourced-promotion-voucher-campaign/README.md`
- `commerce/event-sourced-promotion-voucher-campaign/README.ko.md`
- `README.md`, `README.ko.md`, `commerce/README.md`, `commerce/README.ko.md`
- `scripts/smoke-validate.sh`
- `scripts/generate-event-sourced-voucher-diagrams.mjs`
- `scripts/validate-event-sourced-voucher-readme.mjs`
- `docs/images/readme-diagrams/event-sourced-voucher-{architecture,command-projection,rebuild}-01.{svg,png}`
- `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`
- `docs/lessons/2026-07-22-issue-538-event-sourced-voucher-campaign.md`

---

## 3. 순차 구현 작업

실행자는 모든 task에서 `bluetape-kotlin-patterns`를 기본으로 적용한다. 추가 matching skill은 다음과 같다.

| Task | 추가 matching skill |
|---|---|
| 1, 10, 11 | `ecc-springboot-kotlin`, `ecc-kotlin-testing` |
| 2, 5, 6, 12 | `ecc-kotlin-patterns`, `ecc-kotlin-testing` |
| 3, 4, 7, 8, 13 | `ecc-kotlin-exposed`, `ecc-kotlin-testing` |
| 6–9 | `kotlin-coroutines-skill`, `ecc-kotlin-testing` |
| 9, 14 | `Backend Implementation`, `ecc-kotlin-testing` |
| 15 | `bluetape-diagram`, `bluetape-writer` |
| 16 | `requesting-code-review`, `verification-before-completion`, `finishing-a-development-branch` |

### Task 1: Java 25 module과 검증 task를 등록한다

**복잡도:** 중간
**의존:** 승인된 spec/plan
**write scope:** module build/runtime/test resources만

- [ ] **RED:** `Runtime.version().feature() == 25`, Kotlin/JVM target 25, preview 미사용, Boot main class, virtual-thread runtime을 검사하는 `EventSourcedRuntimeContractTest`를 먼저 작성한다.
- [ ] module directory가 없어서 targeted test가 실패하는 증거를 남긴다.
- [ ] #534 module build를 최소한으로 재사용해 Java/Kotlin 25, Boot 4 MVC/JDBC/Security/Actuator, Exposed, PostgreSQL, Testcontainers, Awaitility, assertions를 versionless alias로 선언한다.
- [ ] `test`는 container-free, `integrationTest`는 `integration`, `stressTest`는 `stress` tag만 실행하고 test mutex·zero-test guard·JUnit XML guard를 적용한다.
- [ ] `./gradlew projects | rg 'commerce-event-sourced-promotion-voucher-campaign'`와 runtime contract를 통과시킨다.
- [ ] rollback: module directory만 제거하면 자동 등록 전 상태로 돌아가도록 root build/settings를 수정하지 않는다.
- [ ] Lore commit: `Run the event-sourced voucher example on Java 25`.

### Task 2: event schema, aggregate reducer, upcaster를 순수 domain으로 잠근다

**복잡도:** 높음
**의존:** Task 1
**write scope:** `domain/**`와 domain tests

- [ ] **RED:** campaign 생성/활성화/용량 변경, voucher issue/redeem/cancel/expire, 잘못된 전이, version gap, unknown type/version, upcast 결과를 `bluetape4k-assertions`로 검사한다.
- [ ] event envelope에 UUID v7 event id, tenant, stream type/id/version, global position, event type/schema version, occurred/recorded time, correlation/causation, actor surrogate, canonical checksum을 정의한다.
- [ ] JSON payload 64 KiB/depth 16/string 8 KiB, snapshot 1 MiB, upcaster 4단계 hard cap과 `(eventType, fromVersion, toVersion)` immutable golden fixture를 고정한다.
- [ ] command decision은 immutable event list만 반환하고 aggregate mutation은 reducer 한 곳에서 수행한다.
- [ ] registry가 `(eventType, schemaVersion)` decoder/upcaster를 exhaustive하게 선택하고 원문 민감정보를 payload에 허용하지 않는다.
- [ ] property-style replay 순서, duplicate event id, empty decision edge를 검증한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:test --tests '*domain.*' :commerce-event-sourced-promotion-voucher-campaign:detektTest`를 통과시킨다.
- [ ] rollback: event contract 변경은 registry/upcaster와 fixture version을 함께 되돌린다.
- [ ] Lore commit: `Make voucher history deterministic before persistence`.

### Task 3: Exposed schema와 append-only repository contract를 만든다

**복잡도:** 높음
**의존:** Task 2
**write scope:** persistence tables/entities/repository base/schema initializer와 repository contract tests

- [ ] **RED:** 모든 concrete repository의 `ExposedJdbcRepository` assignability, connection-free method signature, append-only generic mutation 거부, required unique/check/index를 검사한다.
- [ ] `EventSourcedExposedJdbcRepository<E, ID>`는 `SimpleExposedJdbcRepository` delegate를 사용하고 `AppendOnlyEventSourcedRepository`는 `save/delete*`를 final override로 거부한다.
- [ ] event log, stream head, append fence, idempotency receipt, snapshot, projection generation/checkpoint/lease/dedup/read model, subject mapping, operator audit table을 Exposed로 선언한다.
- [ ] production fixture와 tests가 Exposed DAO/DSL로만 authority row를 생성하도록 architecture test를 둔다.
- [ ] `EventStoreSchemaInitializer`의 bounded `Transaction.exec`는 PostgreSQL owner/application role 분리, UPDATE/DELETE/TRUNCATE 차단 trigger·REVOKE/GRANT, session timeout에만 사용하고 SQL literal allowlist test를 둔다.
- [ ] Testcontainers PostgreSQL에서 application role의 direct UPDATE/DELETE/TRUNCATE가 권한/trigger로 실패하고 전용 append가 성공함을 hostile test로 증명한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --tests '*EventStoreRepositoryContractTest' :commerce-event-sourced-promotion-voucher-campaign:detekt :commerce-event-sourced-promotion-voucher-campaign:detektTest --console=plain`을 통과시킨다.
- [ ] rollback: initializer 적용 전/후 role과 trigger 존재를 검사하는 teardown-safe fixture를 유지한다.
- [ ] Lore commit: `Keep event authority append-only below the service layer`.

### Task 4: expected-version append와 commit-safe global position을 원자화한다

**복잡도:** 매우 높음
**의존:** Task 3
**write scope:** event-store port/repository/transaction runner와 append integration tests

- [ ] **RED:** same expected version 경쟁, multi-stream all-or-nothing, rollback gap, commit-order inversion, head-bounded read를 PostgreSQL barrier test로 재현한다.
- [ ] `EventStorePort.load`와 `appendAll(List<ExpectedAppend>)`를 정의하고 conflict를 sealed result로 반환한다.
- [ ] foreground transaction은 stream head를 정렬된 순서로 lock하고 expected version을 검증한 뒤 append fence에서 연속 global position을 발급한다.
- [ ] committed head는 append transaction 마지막에 갱신하며 projector가 미완료 position을 읽지 않게 한다.
- [ ] duplicate event id와 stream-version unique violation을 domain conflict로 변환하고 다른 SQL 오류는 숨기지 않는다.
- [ ] 32/64 independent stream과 one-hot-stream 경쟁에서 duplicate version/position, partial append가 없음을 검증한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --tests '*EventStoreAppendIntegrationTest' --console=plain`을 3회 연속 실행한다.
- [ ] rollback: append algorithm 교체 시 기존 event row를 rewrite하지 않고 port implementation만 되돌린다.
- [ ] Lore commit: `Preserve stream and global order across concurrent commits`.

### Task 5: principal-scoped idempotency와 command orchestration을 구현한다

**복잡도:** 매우 높음
**의존:** Task 4
**write scope:** idempotency repository와 command services/tests

- [ ] **RED:** same key/same fingerprint replay, fingerprint conflict, owner lease takeover, stale owner finalize, principal separation, key rotation replay/unavailable, event append rollback을 검사한다.
- [ ] fingerprint는 canonical request와 tenant/principal digest/operation/resource를 포함하며 raw key와 identity를 저장하지 않는다.
- [ ] receipt acquire를 짧은 transaction으로 commit한 뒤 active transaction 밖에서 bounded snapshot+tail rehydrate와 pure decision을 수행한다. 이어 짧은 foreground transaction에서 owner digest/lease/deadline/fingerprint를 재검증하고 stream lock, `appendAll`, terminal descriptor finalize를 원자적으로 수행한다.
- [ ] expected-version conflict와 domain rejection을 #534 error model로 안정적으로 매핑한다.
- [ ] terminal descriptor에 allocation identity와 generation/verification key version을 저장하고 크기/deadline/lease를 bound한다. takeover는 owner-token digest CAS를 요구하며 replay key가 retired/unavailable이면 `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`/503으로 fail closed한다.
- [ ] test double이 아닌 PostgreSQL 경쟁 test로 duplicate event가 0개임을 증명한다.
- [ ] targeted unit/integration tests와 `detektTest`를 통과시킨다.
- [ ] rollback: receipt schema를 유지한 채 command adapter만 이전 구현으로 교체 가능하게 port 경계를 보존한다.
- [ ] Lore commit: `Replay retries without repeating voucher decisions`.

### Task 6: snapshot과 bounded rehydration을 추가한다

**복잡도:** 높음
**의존:** Task 5
**write scope:** snapshot repository/service와 replay tests

- [ ] **RED:** no snapshot, valid snapshot+tail, corrupt/stale snapshot fallback, digest mismatch, key-version rotation, replay cap 초과를 검사한다.
- [ ] snapshot에 stream version, canonical state digest, schema/key version, created time을 저장하고 append 권위와 분리한다.
- [ ] snapshot threshold 250, foreground replay 최대 10,000 events/2초, 200-event position page, snapshot 1 MiB를 properties hard cap으로 고정한다.
- [ ] snapshot write 실패는 command append를 rollback하지 않고 metric/audit에 기록하며 다음 replay가 full/tail fallback한다.
- [ ] full replay와 snapshot+tail의 aggregate version/canonical digest가 동일함을 random sequence로 검증한다.
- [ ] coroutine helper가 필요하면 dispatcher를 주입하고 cancellation을 삼키지 않으며 cleanup만 `NonCancellable`에 한정한다.
- [ ] unit tests와 PostgreSQL snapshot integration test를 통과시킨다.
- [ ] rollback: snapshot table을 비워도 event replay로 정상 동작함을 검증한다.
- [ ] Lore commit: `Bound replay cost without promoting snapshots to authority`.

### Task 7: fenced asynchronous projection을 구현한다

**복잡도:** 매우 높음
**의존:** Tasks 4, 6
**write scope:** projection model/repository/handlers/lease/worker와 projection tests

- [ ] **RED:** duplicate, delayed batch, handler interruption, poison event, lease expiry, stale fencing token, checkpoint crash-before/after-commit을 검사한다.
- [ ] lease TTL 15초/renew 5초와 monotonically increasing fencing token을 PostgreSQL CAS로 발급한다.
- [ ] renew/release도 `(owner, fencingToken)` CAS로만 수행하고 release 때 lease row/token을 삭제·초기화하지 않는다. takeover 뒤 이전 process의 shutdown release가 새 owner lease를 바꾸지 못함을 PostgreSQL test로 증명한다.
- [ ] handler는 `(generation, eventId)` dedup과 read-model mutation, checkpoint advance를 같은 transaction에서 수행한다.
- [ ] projector는 committed head까지 keyset batch로 읽고 token/revision을 모든 mutation predicate에 포함한다.
- [ ] projection batch는 최대 200 events 또는 2 MiB, transaction 2초, idle poll backoff 100ms–2초를 hard cap으로 둔다.
- [ ] poison event는 bounded retry 뒤 checkpoint를 유지하고 DEGRADED 상태, event metadata, operator action을 노출한다.
- [ ] worker loop는 injected scheduler/dispatcher와 cooperative cancellation을 사용하고 `Thread.sleep`을 사용하지 않는다.
- [ ] Awaitility로 수렴을 기다리고 exact row count/version/digest를 assertions로 확인한다.
- [ ] projection integration suite를 3회 반복하고 `detekt detektTest`를 통과시킨다.
- [ ] rollback: active generation pointer를 유지한 채 worker bean만 비활성화할 수 있게 property gate를 둔다.
- [ ] Lore commit: `Fence stale projectors before serving voucher state`.

### Task 8: generation rebuild와 restart recovery를 구현한다

**복잡도:** 매우 높음
**의존:** Task 7
**write scope:** rebuild service/repository와 rebuild lifecycle tests

- [ ] **RED:** `BUILDING -> VALIDATING -> ACTIVE`, `BUILDING|VALIDATING -> CANCELLING -> CANCELLED`, retryable `FAILED -> BUILDING`, cancel 직후 process-kill/restart, stale worker, corrupt digest, activation race, retention cleanup을 검사한다.
- [ ] rebuild가 새 generation/checkpoint/token으로 position 0부터 bounded replay하고 active generation을 건드리지 않게 한다.
- [ ] rebuild page는 최대 200 events/2 MiB, transaction 2초, module/tenant 동시성 1, 100,000 events/10분으로 제한하고 lag >10,000 또는 foreground saturation >80%이면 새 batch를 throttle한다.
- [ ] head 도달 후 VALIDATING에서 row count, aggregate version, canonical digest를 검증하고 target head를 다시 읽은 generation만 pointer CAS로 ACTIVE 전환한다.
- [ ] cancellation revision과 fencing token을 batch마다 확인해 cancelled worker가 checkpoint/read model을 갱신하지 못하게 한다.
- [ ] startup recovery가 BUILDING/VALIDATING/CANCELLING/CANCELLED/FAILED를 분류한다. CANCELLING은 새 token으로 idempotent CANCELLED에 수렴하고, resume은 schema/upcaster/checksum/token 계약이 같은 retryable state만 BUILDING으로 되돌린다.
- [ ] cancel과 activation 경쟁에서 cancel 선행은 stale batch/activation rollback, activation 선행은 `REBUILD_ALREADY_ACTIVATED`/409를 반환하도록 검증한다.
- [ ] 이전 generation은 retention 뒤 maintenance worker가 bounded page로 삭제한다.
- [ ] Awaitility 기반 restart Testcontainers test와 concurrent activation test를 통과시킨다.
- [ ] rollback: previous active generation pointer를 보존하고 activation audit로 수동 복귀 가능성을 문서화한다.
- [ ] Lore commit: `Rebuild projections without replacing verified reads early`.

### Task 9: permit budget, lifecycle, health, metrics, immutable operator audit을 연결한다

**복잡도:** 높음
**의존:** Tasks 7, 8
**write scope:** `operations/**`, configuration, operational tests

- [ ] **RED:** foreground/background/readiness starvation, startup 진행/DB·schema failure, aggregate 단독 degradation, projection degradation, 모든 rebuild state, graceful shutdown, maintenance flood, 각 operator action audit 원자성을 검사한다.
- [ ] Hikari 20을 foreground 14, projection 3, rebuild 1, maintenance 1, readiness 1 permit으로 분리한다. virtual thread도 connection 요청 전에 permit을 얻고, foreground holders+waiters 128, permit wait 250ms, lock 500ms, statement 3초를 hard cap으로 둔다.
- [ ] admission/250ms 초과는 Hikari wait로 넘기지 않고 `DATABASE_BULKHEAD_REJECTED`/503과 `Retry-After: 1`로 종료한다. saturation test는 permit 전 Hikari pending/active 불변과 readiness reserved connection 비침범을 증명한다.
- [ ] management test는 availability state, readiness health group, custom health를 독립 assertion한다. startup 진행은 `REFUSING_TRAFFIC`/OUT_OF_SERVICE, integrity·DB failure는 `REFUSING_TRAFFIC`/DOWN, aggregate·projection degradation은 `ACCEPTING_TRAFFIC`/UP+custom DEGRADED, rebuild는 `ACCEPTING_TRAFFIC`/UP이며 custom health가 BUILDING/VALIDATING/CANCELLING/CANCELLED/FAILED를 그대로 구분하고, shutdown은 `REFUSING_TRAFFIC`/OUT_OF_SERVICE로 고정한다. liveness는 각 과정에서 process-local UP이다.
- [ ] application-owned lifecycle scope가 projector/rebuild/maintenance를 소유한다. migration, schema/upcaster integrity, PostgreSQL read/write probe 성공 뒤에만 worker와 readiness를 시작한다.
- [ ] shutdown은 새 lease/batch/admission 차단 → worker cancellation → in-flight drain → fenced lease release 순서로 수행하고 `CancellationException`을 재전파하며 `finally`에서 local resource를 정리한다.
- [ ] shutdown은 readiness를 먼저 REFUSING_TRAFFIC으로 바꾸고 10초 bounded drain 뒤 남은 transaction rollback, lease/permit/connection cleanup을 검증한다.
- [ ] `voucher_projection_lag_events`, `voucher_projection_lag_age_seconds`, `voucher_projection_checkpoint_stalled_seconds`, `voucher_projection_poison_events`, `voucher_projection_retry_total`, `voucher_rebuild_progress_ratio`, `voucher_rebuild_duration_seconds`, `voucher_db_bulkhead_active`, `voucher_db_bulkhead_queued`, `voucher_db_bulkhead_rejected_total`과 Hikari/readiness metric을 제공한다.
- [ ] Micrometer tag는 bounded `projection/state/outcome/reasonClass`만 사용하고 tenant, generation, campaign, stream/event/principal id를 넣지 않는다.
- [ ] poison 즉시, checkpoint stall 30초 즉시, lag age 60초가 5분 지속, rebuild FAILED/10분 초과, readiness DOWN 1분, bulkhead rejection 5분간 1% 초과 alert 계약을 metric test와 runbook에 연결한다.
- [ ] rebuild start/cancel/resume/activate, poison retry, reconciliation 각각 actor digest, tenant, request/idempotency digest, action, target projection/generation, expected fencing token, before/after state, checkpoint/stream position, outcome, bounded reason class, occurred-at을 mutation과 같은 transaction의 append-only audit에 남긴다.
- [ ] 각 action의 field coverage와 audit insert 실패 시 domain mutation도 rollback되는 PostgreSQL negative test를 둔다.
- [ ] virtual-time unit test와 Awaitility integration test를 모두 통과시킨다.
- [ ] rollback: worker별 enable property와 permit default를 문서화해 operational disable이 가능하게 한다.
- [ ] Lore commit: `Reserve database capacity for commands and readiness`.

### Task 10: #534-compatible HTTP/query/error 계약을 제공한다

**복잡도:** 높음
**의존:** Tasks 5, 7, 9
**write scope:** API models/controllers/exception handler와 HTTP integration tests

- [ ] **RED:** #534 command 성공/실패 fixture, same-key replay, conflict, validation, not-found, projection pending, rebuild start/status/cancel/resume, poison retry, reconciliation, stale token 412, activation 선행 cancel 409를 live WebTestClient로 검사한다.
- [ ] 기존 #534 body와 ETag를 바꾸지 않고 additive `X-Stream-Position`, `X-Projection-Position`, `X-Projection-Lag` header만 추가한다.
- [ ] query의 선택적 `X-Min-Stream-Position`은 최대 5초 기다린다. 미도달 시 `202 PROJECTION_PENDING`, `Retry-After: 1`, 현재 position/lag를 반환하며 GET caller만 최대 5회 retry하고 command를 재제출하지 않는다.
- [ ] rebuild start/status/cancel/resume endpoint는 idempotency key와 expected active-generation token을 요구하며 stale token 412와 activation 선행 cancel 409를 안정적으로 반환한다.
- [ ] poison retry는 최대 5회, 1–30초 exponential backoff를 적용하고 성공 전 failure row를 보존한다. 성공 transaction은 projection row, dedup identity, checkpoint, failure `RESOLVED`, operator audit을 원자 commit한다.
- [ ] management와 application WebTestClient 모두 JDK connector와 공통 `HTTP_TIMEOUT = 60.seconds`를 사용한다.
- [ ] controller는 service transaction을 누설하지 않고 request/response DTO만 변환한다.
- [ ] exception handler가 SQL/internal token/digest/PII를 노출하지 않는지 negative test를 둔다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --tests '*HttpIntegrationTest' --console=plain`을 통과시킨다.
- [ ] rollback: #534 fixture가 adapter 교체 전후 같은 normalized descriptor를 생성하게 유지한다.
- [ ] Lore commit: `Keep voucher clients stable while projections catch up`.

### Task 11: SSE와 accessible operator browser를 projection 상태에 맞춘다

**복잡도:** 높음
**의존:** Task 10
**write scope:** SSE/filter/static UI/browser tests

- [ ] **RED:** #534 snapshot-first SSE와 cursor reconnect, heartbeat/overflow, projection pending UI, last-known projection 유지, GET-only 최대 5회 retry, state별 action, stale revision/token, retry-vs-rebuild 안내, keyboard/ARIA를 검사한다.
- [ ] SSE event는 bounded public descriptor만 포함하고 snapshot을 먼저 보낸 뒤 cursor 기반 reconnect에서 stream/global/projection position을 구분한다.
- [ ] `X-Min-Stream-Position`이 없으면 즉시 query한다. 있으면 browser가 pending/manual-refresh 분기를 generic 2xx보다 먼저 수행하며 retry 소진 뒤 stale 표시와 수동 refresh를 제공한다.
- [ ] browser는 `PROJECTION_PENDING`을 성공 snapshot으로 렌더링하지 않고 마지막 verified projection과 lag banner를 유지한다.
- [ ] poison/rebuild/lag 상태별 허용 operator action과 destructive confirmation을 명시한다. 412는 최신 state를 다시 읽되 기존 mutation을 자동 재제출하지 않고 사용자 재확인을 요구한다.
- [ ] 진행 중 중복 action을 disable하고 같은 idempotency key/token을 유지한다. projection recovery와 business reconciliation은 별도 panel/action으로 표시한다.
- [ ] `aria-live`, text status, disabled-action reason과 색상·숫자에 의존하지 않는 screen-reader 문구를 keyboard-only/ARIA contract test로 검증한다.
- [ ] operator filter는 #534의 loopback/allowed-host, constant-time secret+guard, same-origin, JSON content, role/CSRF/mutation method, bounded pagination/admission을 fail closed로 검증하고 audit actor surrogate를 request scope에 전달한다.
- [ ] deterministic browser contract test와 live SSE integration test를 통과시킨다.
- [ ] rollback: static UI 없이도 HTTP/SSE API가 완전하게 동작하도록 progressive enhancement를 유지한다.
- [ ] Lore commit: `Show projection truth instead of hiding asynchronous lag`.

### Task 12: subject surrogate, erasure, redaction, key rotation을 검증한다

**복잡도:** 높음
**의존:** Tasks 5, 9, 11
**write scope:** security service/repository tests와 cross-cutting redaction tests

- [ ] **RED:** subject mapping deletion, reverse lookup 차단, same identity 재등록 새 surrogate, digest key rotation replay, log/event/metric/API redaction을 검사한다.
- [ ] erasure 대상 identity는 random per-subject surrogate와 event-store 밖 deletable mapping을 사용한다. 안정적 상관관계가 필요한 저엔트로 값은 purpose/version/tenant/domain-separated HMAC-SHA256만 사용하고 plain hash를 금지한다.
- [ ] erasure는 immutable event를 rewrite하지 않고 mapping을 삭제해 기존 surrogate의 reverse lookup을 차단한다.
- [ ] event/terminal descriptor에 HMAC/generation/verification key version을 저장한다. active+retired verification key ring을 retention과 결합하고 unavailable key replay가 503으로 fail closed함을 검사한다.
- [ ] HMAC은 erasure가 아님을 문서화하고 Logback/event/snapshot/projection/failure/SSE/API/metric scan으로 raw user/device/IP/voucher code/token/idempotency key/authorization header가 없는지 검사한다.
- [ ] operator security negative cases와 immutable audit 연결을 통과시킨다.
- [ ] rollback: key removal 전 replay coverage와 mapping backup/restore runbook을 README에 남긴다.
- [ ] Lore commit: `Separate immutable voucher history from reversible identity mapping`.

### Task 13: #534 black-box compatibility와 adversarial PostgreSQL suite를 고정한다

**복잡도:** 매우 높음
**의존:** Tasks 10–12
**write scope:** fixture/compatibility/concurrency/restart/adversarial tests

- [ ] normalized #534 fixture를 test source로 복제하지 말고 shared descriptor/contract adapter로 재사용한다.
- [ ] campaign/voucher happy path, 모든 failure code, idempotency replay를 #534와 event-sourced app에 같은 black-box 요청으로 실행해 normalized result가 같음을 검사한다.
- [ ] concurrent append, projector lease loss, rebuild cancellation, process restart, poison recovery, DB unavailable/recovery를 실제 PostgreSQL로 검증한다.
- [ ] PostgreSQL capability test가 unique constraint, row lock, trigger/role, advisory/row lease behavior와 query plan index usage를 증명한다.
- [ ] integration suite는 serialized Testcontainers mutex를 지키고 각 scenario를 Awaitility로 bounded wait한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:integrationTest --console=plain`을 3회 반복하고 `git diff --check`, architecture forbidden-pattern scan을 통과시킨다.
- [ ] rollback: compatibility fixture mismatch는 public adapter 변경을 revert하고 authority event를 삭제/수정하지 않는다.
- [ ] Lore commit: `Prove event sourcing preserves the normalized voucher contract`.

### Task 14: 성능·안정성 budget을 별도 profile로 증명한다

**복잡도:** 높음
**의존:** Task 13
**write scope:** stress tests, Gradle stress task, metrics assertions

- [ ] hot profile은 64 virtual-thread clients/단일 campaign/1,000 commands에서 ≥20 successful appends/s, append 성공률 ≥95%, p95 ≤2초, p99 ≤5초, lock/statement timeout ≤1%를 판정한다.
- [ ] independent profile은 64 clients/32 campaigns에서 ≥40 successful appends/s, append-fence p95 ≤100ms/p99 ≤500ms, timeout ≤1%를 판정한다.
- [ ] terminal/committed throughput, 409, Hikari wait, append-fence wait, stream-head wait를 분리 집계하고 CI correctness profile은 모든 request의 60초 내 terminal 결과와 starvation 0을 검사한다.
- [ ] snapshot/replay 250/10,000/2초/200, projection/rebuild 200 events 또는 2MiB/2초, rebuild 100,000 events/10분, lag 10,000 또는 foreground saturation 80% throttle 경계를 각각 정상/초과 test로 고정한다.
- [ ] maintenance queue 64, 100 rows/2MiB, 2초, 100ms–5초 backoff와 readiness permit 비침범을 flood profile로 검증한다.
- [ ] replay event/byte cap, projection batch, queue wait, permit utilization, lag, rebuild ETA metric의 cardinality를 검사한다.
- [ ] 100 tenants/1,000 campaigns/10,000 streams/100,000 events와 projection 100,000 rows를 seed/warm-up하고 stream tail, global scan, latest snapshot, dedup, retry scan에 `EXPLAIN (ANALYZE, BUFFERS)`를 실행한다.
- [ ] CI는 unexpected `Seq Scan` 금지와 index plan shape를 판정하고 dedicated profile은 shared read buffers ≤512, execution ≤100ms 및 위 throughput/percentile 수치를 판정한다.
- [ ] CI-safe correctness profile과 opt-in dedicated/soak property를 분리한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:stressTest -PeventSourcedStress=true --console=plain`을 통과시킨다.
- [ ] rollback: stress profile은 production defaults를 바꾸지 않고 test property override만 사용한다.
- [ ] Lore commit: `Measure committed appends under realistic contention`.

### Task 15: README, diagram, workflow, validator, lesson을 함께 등록한다

**복잡도:** 중간
**의존:** Tasks 1–14
**write scope:** module/root/commerce docs, diagram assets/scripts, workflow/smoke/nightly, lesson

- [ ] module README 영문/국문에 실행법, Java 25, architecture, event envelope, consistency/lag, rebuild, security, failure injection, performance profile을 같은 구조로 기록한다.
- [ ] README/API 표에 position headers, 202 body/retry/manual refresh, SSE snapshot/cursor, rebuild/retry/reconciliation endpoint와 state-action matrix를 기록하고 validator가 실제 route/error constant와 대조한다.
- [ ] `bluetape-diagram` contract로 architecture, command→projection, rebuild state diagram의 SVG/PNG와 generator를 만든다.
- [ ] root/commerce README의 module table과 commands를 양 언어에서 갱신한다.
- [ ] validator가 README parity, image reference/assets, module registration, Java 25, BOM-only dependency, workflow/smoke/nightly/Kover artifact registration을 검사한다.
- [ ] `scripts/smoke-validate.sh`에는 container-free `test`, Examples full/nightly에는 `integrationTest`와 Kover/JUnit artifact를 등록한다.
- [ ] repo `AGENTS.md`는 per-module 목록을 갖지 않으므로 변경하지 않고 validator/lesson에 N/A 근거를 기록한다. `settings.gradle.kts`도 auto-registration 검증만 하고 수정하지 않는다.
- [ ] README runbook에 `health/failure 확인 → fix/deploy → targeted retry 또는 rebuild → checkpoint/digest 검증 → activation/active 유지·rollback → reconciliation`을 기록하고 alert threshold와 operator audit 조회를 연결한다.
- [ ] lesson에 adopted/rejected approach, PostgreSQL proof, review findings, rerun commands, rollback boundary를 한국어로 기록한다.
- [ ] generator/validator, `./gradlew projects`, README link/image check, `git diff --check`를 통과시킨다.
- [ ] rollback: module row/workflow group/artifact/diagram reference를 한 세트로 revert한다.
- [ ] Lore commit: `Register the event-sourced example across workshop surfaces`.

### Task 16: 전체 gate, 코드 리뷰, PR/CI handoff를 완료한다

**복잡도:** 높음
**의존:** Tasks 1–15
**write scope:** 검증으로 발견된 module-local 결함, lesson evidence, PR metadata

- [ ] targeted unit tests → module `test` → `integrationTest` → `stressTest` 순으로 실행한다.
- [ ] `./gradlew :commerce-event-sourced-promotion-voucher-campaign:build :commerce-event-sourced-promotion-voucher-campaign:detekt :commerce-event-sourced-promotion-voucher-campaign:detektTest --console=plain`을 통과시킨다.
- [ ] `./gradlew projects`, module validator, `scripts/smoke-validate.sh`, `git diff --check`를 실행한다.
- [ ] forbidden scan으로 `JdbcTemplate`, raw JDBC types, repository connection parameters, `Thread.sleep`, `!!`, unbounded metric identifiers, explicit Bluetape versions/individual BOM을 검사한다.
- [ ] 개발자/API, 보안, 성능, 안정성, 운영자, 사용자 관점의 exact-head code review를 수행하고 P0/P1을 0으로 만든다.
- [ ] `verification-before-completion`으로 fresh evidence와 known gap을 확인하고 Lore commit을 만든다.
- [ ] English PR title/body에 issue link, architecture, Java 25, test evidence, operational risk/rollback을 기록하고 exact-head CI를 확인한다.
- [ ] CI와 review thread가 모두 통과하면 exact PR/head를 merge-ready로 보고하고 fresh approval을 기다린다.

---

## 4. Acceptance traceability

| 설계 criterion | 구현/검증 task |
|---|---|
| 1. expected-version 단일 승자 | 4, 13 |
| 2. idempotent terminal replay | 5, 10, 13 |
| 3. deterministic full replay | 2, 6, 7 |
| 4. duplicate/delayed/interrupted projection 수렴 | 7, 13 |
| 5. snapshot+tail parity | 6 |
| 6. poison event degraded/recovery | 7, 9, 11, 13 |
| 7. verified rebuild activation | 8, 11, 13 |
| 8. #534 black-box compatibility | 10, 13 |
| 9. sensitive-data prohibition | 2, 5, 9, 12 |
| 10. operator position/lag/rebuild/reconciliation 구분 | 9, 11, 15 |
| 11. Java/Kotlin 25와 virtual threads | 1, 16 |
| 12. module/workflow/README/diagram/stale-check 등록 | 15, 16 |
| 13. commit-safe global position | 4, 13 |
| 14. principal/key/fencing/append-only guard | 3, 5, 7, 12 |
| 15. bounded replay와 permit budget | 6, 9, 14 |
| 16. browser projection pending 처리 | 10, 11 |
| 17. operator action과 stale revision/token | 8, 11 |
| 18. subject erasure와 재등록 surrogate | 12 |
| 19. stale projector/cancelled rebuild fencing | 7, 8, 13 |
| 20. startup/degradation/failure/shutdown | 8, 9, 13 |
| 21. maintenance 폭주와 readiness 보존 | 9, 14 |
| 22. immutable audit와 alert/runbook | 9, 11, 15 |

## 5. Risk prediction과 review focus

| 위험 | 조기 신호 | 예방/증거 | 주 검토 관점 |
|---|---|---|---|
| commit-order inversion으로 event 누락 | global position gap 뒤 checkpoint 전진 | committed-head fence와 barrier test | 안정성, 개발자 |
| expected-version 우회 | 같은 stream version 중복 | PostgreSQL unique+lock+CAS 경쟁 test | 개발자, 안정성 |
| stale worker write | lease 재발급 뒤 old token mutation | 모든 row/checkpoint predicate의 fencing token | 안정성, 운영자 |
| idempotency identity/key rotation 오류 | duplicate event 또는 false conflict | principal scope와 multi-key verification test | 보안, 사용자 |
| rebuild가 active read를 오염 | BUILDING row가 query에 노출 | generation isolation과 activation digest | 운영자, 사용자 |
| replay/projection starvation | foreground/readiness permit 대기 증가 | 14/3/1/1/1 pool과 flood stress | 성능, 운영자 |
| 민감정보 immutable leak | event/log/metric에서 raw identity 발견 | surrogate mapping과 serialized scan | 보안 |
| #534 semantic drift | normalized fixture mismatch | same black-box contract suite | API, 사용자 |
| Java 25 명목 설정 | CI runtime이 21 | compile/test/runtime contract | 개발자, 운영자 |
| CI flakiness | fixed sleep/공유 DB race | Awaitility, test mutex, 3회 반복 | 안정성 |

## 6. 계획 자체 검증

- [ ] spec의 22개 acceptance criterion이 traceability 표에서 빠짐없이 task에 연결된다.
- [ ] 각 task에 dependency, disjoint write scope, RED/GREEN evidence, rollback/rerun, Lore commit이 있다.
- [ ] Kotlin/Exposed/Spring/testing/coroutine skill contract와 상충하는 항목이 없다.
- [ ] public API/KDoc/README/PR은 English, 계획/lesson은 Korean, localized README는 동시 변경한다.
- [ ] six-lens plan review의 P0/P1이 0이고 모든 reviewer가 exact committed head를 검토한다.
- [ ] 구현 전 user가 실행 방식을 선택할 수 있게 계획 commit과 검토 결과만 보고한다.
