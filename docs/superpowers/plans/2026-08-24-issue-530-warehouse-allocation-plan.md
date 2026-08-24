# Issue #530 Warehouse Allocation 및 Pick-Wave Planner Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and the matching Kotlin/Exposed/Spring pattern skills. Execute each task in order and keep every checkbox tied to fresh test evidence.

**Goal:** Epic #523의 #530 요구사항에 맞춰 PostgreSQL을 재고 예약의 최종 권위로 사용하는 결정론적 Warehouse Allocation/Pick-Wave Planner reference module을 추가한다.

**Architecture:** `optimization-warehouse-allocation`을 기존 `planning-contracts`와 독립된 Spring Boot 4/Kotlin 모듈로 만든다. 순수 deterministic planner는 불변 warehouse/order/stock/wave 입력에서 proposal만 만들고, PostgreSQL repository가 order, reservation, event inbox, plan, outbox와 audit의 변경을 단일 transaction과 CAS로 소유한다. HTTP와 test-fixtures는 닫힌 DTO/fixture contract만 노출하고 실제 Timefold, WMS, carrier provider는 이번 변경에 포함하지 않는다.

**Tech Stack:** Kotlin 2.4.0, Java 25, Spring Boot 4.0.6 MVC, Exposed v1 JDBC, PostgreSQL Testcontainers, Jackson 3, virtual-thread API/JDK25 runtime, JUnit 5, bluetape4k assertions/testcontainers.

---

## 파일 구조와 소유권

| 영역 | 파일/디렉터리 | 단일 책임 |
|---|---|---|
| 모듈 | `optimization/warehouse-allocation/build.gradle.kts`, `src/main/resources/application.yml`, `src/test/resources/{application-test.yml,junit-platform.properties,logback-test.xml}` | Gradle 의존성, Spring profile, 테스트 환경 |
| 진입점 | `src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/WarehouseAllocationApplication.kt` | 명시적 `WarehouseAllocationApplicationKt` Spring Boot 진입점 |
| domain | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationIds.kt`, `WarehouseAllocationLimits.kt`, `WarehouseAllocationModels.kt`, `WarehouseAllocationEvents.kt`, `WarehouseAllocationErrors.kt` | ID/value object, 닫힌 상태·reason enum, 입력/제안 모델, 오류 |
| planner | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlanner.kt`, `WarehouseAllocationPlannerInput.kt`, `WarehouseAllocationPlannerOutput.kt` | deterministic 후보 생성, hard/medium/soft 점수, bounded output |
| persistence | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationTables.kt`, `WarehouseAllocationRecords.kt`, `WarehouseAllocationCodec.kt`, `WarehouseAllocationRepository.kt`, `WarehouseAllocationTransactionSupport.kt`, `WarehouseAllocationDatabaseInitializer.kt` | PostgreSQL schema, redacted JSON, lock/CAS/query/write 경계 |
| application | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationCommandService.kt`, `WarehouseAllocationApprovalService.kt`, `WarehouseAllocationReplanService.kt`, `WarehouseAllocationEventService.kt`, `WarehouseAllocationIdempotencyService.kt`, `WarehouseAllocationOutboxWorker.kt`, `WarehouseAllocationRecovery.kt` | command, approval, event, replan, retry/recovery, outbox fencing |
| adapters | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/fake/DeterministicWarehouseAllocationPlanner.kt`, `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/WarehouseAllocationCanonicalizer.kt`, `WarehouseAllocationSignatureVerifier.kt`, `WarehouseAllocationHttpModels.kt`, `WarehouseAllocationHttpService.kt` | fake provider와 canonical/signature/HTTP 변환 |
| web | `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationController.kt`, `WarehouseAllocationExceptionHandler.kt`, `WarehouseAllocationDtos.kt` | query/command route와 고정 error DTO |
| fixture ABI | `optimization/warehouse-allocation/src/testFixtures/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/WarehouseAllocationFixturePort.kt`, `DefaultWarehouseAllocationFixturePort.kt` | test-fixtures source set에서만 제공하는 명시적 reset/ingest/snapshot ABI |
| unit/integration tests | `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/` | 모든 red/green 증거와 contract fixture |
| 저장소 등록 | `settings.gradle.kts`는 자동 탐색 검증, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`, `optimization/README*`, validation matrix/lesson | workflow, smoke, stale-check, README, canonical matrix 등록 |

새 module의 production source는 `io.bluetape4k.workshop.optimization.warehouseallocation` 하위만 사용한다. test-fixtures ABI 외에는 외부 소비자용 public class를 만들지 않고 controller/service/repository는 `internal`을 기본으로 한다.

## Task 1: 모듈 골격과 테스트 환경을 먼저 고정

**Files:**

- Create: `optimization/warehouse-allocation/build.gradle.kts`
- Create: `optimization/warehouse-allocation/src/main/resources/application.yml`
- Create: `optimization/warehouse-allocation/src/test/resources/application-test.yml`
- Create: `optimization/warehouse-allocation/src/test/resources/junit-platform.properties`
- Create: `optimization/warehouse-allocation/src/test/resources/logback-test.xml`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/WarehouseAllocationApplication.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/WarehouseAllocationRuntimeContractTest.kt`

- [ ] **Step 1: 실패하는 runtime contract test 작성**

  `WarehouseAllocationRuntimeContractTest`는 `WarehouseAllocationApplicationKt`의 존재, `springBoot.mainClass`, `application.yml`의 `server.port`, `server.address`/management binding이 loopback인지, CORS가 public origin을 허용하지 않는지, test profile의 PostgreSQL property와 `FAKE` 기본 provider가 결정론적임을 검사한다. 아직 module이 없으므로 `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationRuntimeContractTest' --max-workers=1 --console=plain`은 module/task 부재로 실패해야 한다.

- [ ] **Step 2: 기존 optimization build convention으로 최소 모듈 추가**

  `field-service-dispatch/build.gradle.kts`를 기준으로 `kotlin.spring`, `spring.boot`, `java-test-fixtures`만 적용하고 `testImplementation(project(":shared"))`, bluetape4k core/logging/http/jackson3/idgenerators/micrometer/virtualthread, Exposed JDBC/Jackson/java-time/Spring Boot 4 starter, PostgreSQL, Spring MVC/validation/actuator, JUnit/assertions/Testcontainers PostgreSQL/WireMock을 선언한다. 개별 bluetape4k BOM과 explicit bluetape version은 추가하지 않으며 `bluetape4k-virtualthread-jdk21`은 exclude한다.

  `springBoot { mainClass.set("io.bluetape4k.workshop.optimization.warehouseallocation.WarehouseAllocationApplicationKt") }`를 고정하고, `application.yml`에는 `spring.profiles.default: test`, `warehouse-allocation.provider: fake`, `warehouse-allocation.planner.deadline: 2s`, `warehouse-allocation.planner.max-lines: 500`, `max-warehouses: 100`, `max-waves: 200`, `max-stock-rows: 10000`, `max-pins: 500`, `max-response-bytes: 262144`를 둔다. `src/test/resources/application-test.yml`은 Testcontainers JDBC placeholder와 `spring.sql.init.mode: never`를 사용한다.

- [ ] **Step 3: RED test를 확인하고 골격으로 GREEN 전환**

  `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationRuntimeContractTest' --max-workers=1 --console=plain`을 다시 실행해 application context와 resource contract가 PASS하는 것을 확인한다. `./gradlew :optimization-warehouse-allocation:compileTestFixturesKotlin --max-workers=1 --console=plain`도 실행해 fixture source set이 분리됐음을 증명한다.

## Task 2: 도메인 계약과 deterministic planner를 TDD로 구현

**Files:**

- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationIds.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationLimits.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationModels.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationEvents.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationErrors.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlannerInput.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlannerOutput.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlanner.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/fake/DeterministicWarehouseAllocationPlanner.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationModelsTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlannerTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlannerResourceContractTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/planner/WarehouseAllocationPlannerStressTest.kt`

- [ ] **Step 1: 값 객체·상태·reason의 RED 테스트 작성**

  `WarehouseAllocationModelsTest`에 dataset/order/order-line/warehouse/sku/stock snapshot/pick wave/shipping rule/carrier cutoff/picker capacity/pin/plan/reservation/event/idempotency key의 blank·length·negative revision 거부, `OrderStatus` truth table, `OrderLineStatus`, `PinStatus`, `ReservationState`, `PlanStatus`, `ReplanState`, `ReplanStaleReason`, `OutboxState`, `EffectState`의 닫힌 enum과 nullable/presence golden fixture를 작성한다. `warehouseId`가 빈 문자열이면 `InvalidWarehouseAllocationInput`, `datasetId` 96자 초과와 line/stock/wave/pin cardinality 초과는 고정 오류 code를 반환하게 한다.

- [ ] **Step 2: planner RED 테스트 작성**

  `WarehouseAllocationPlannerTest`는 동일 `WarehouseAllocationPlannerInput`을 두 번 실행해 byte-identical proposal을 요구한다. fixture에는 available stock, cold-chain/hazmat capability, carrier cutoff, shipping rule, picker capacity, committed pin, two warehouse split을 포함하고 다음을 각각 고정한다: hard violation이면 assignment를 만들지 않음, stock 부족이면 `STOCK_UNAVAILABLE`, capability 부족이면 `COLD_CHAIN`/`HAZMAT`, cutoff 이후면 `CARRIER_CUTOFF`, picker capacity 초과면 `PICKER_CAPACITY`, committed pin 충돌이면 `PIN_CONFLICT`, stale pin이면 `PIN_STALE`, 가능한 경우 hard/medium/soft score와 `SPLIT_SHIPMENT` reason을 deterministic order로 반환.

- [ ] **Step 3: planner minimal implementation**

  `WarehouseAllocationPlanner`는 input line을 cutoff→capability→quantity→line ID 순으로 평가하고, 후보 tie-break는 warehouseId→waveId→orderLineId로 고정한다. pin은 먼저 보존하며 stale/충돌은 `PIN_STALE`/`PIN_CONFLICT`로 설명한다. 후보가 여러 개면 고정 tie-break의 첫 후보를 택하고 stock/capacity를 local counter로 차감한다. proposal에는 `planId`, `datasetId`, `datasetVersion`, `expectedOrderRevision`, `warehouseRevision`, hard/medium/soft score, `allocations`, `unassignedReasons`, `splitShipmentReasons`, `manualPins`를 저장하며 입력 object는 변경하지 않는다. 후보 product가 `2_000_000`을 넘으면 `PLANNER_INPUT_TOO_LARGE`/HTTP 413/`SHRINK_DATASET`, deadline은 `PLANNER_DEADLINE_EXCEEDED`/HTTP 422/`SHRINK_DATASET`, output 500개 초과는 `PLANNER_OUTPUT_TOO_LARGE`/HTTP 422/`SHRINK_DATASET`으로 구분하고 plan/reservation/outbox를 생성하지 않는다. 각 golden fixture는 allocation 순서, score, digest를 함께 검증한다.

- [ ] **Step 4: planner GREEN와 resource guard 검증**

  `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationPlannerTest' --tests '*WarehouseAllocationPlannerResourceContractTest' --max-workers=1 --console=plain`을 실행한다. planner resource test는 lines 500/501, warehouses 100/101, waves 200/201, stock 10000/10001, pins 500/501, candidate 2M/2M+1, deadline 2초, output 500/501, response 256KiB/256KiB+1 경계를 검증한다. executor admission과 outbox queue는 후행 Task 5에서만 검증한다.

## Task 3: PostgreSQL schema, codec, repository와 aggregate CAS 구현

**Files:**

- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationTables.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationRecords.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationCodec.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationRepository.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationTransactionSupport.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationDatabaseInitializer.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationRepositoryTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationReservationPostgresTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationApprovalContentionPostgresTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationOrderCancellationContractTest.kt`

- [ ] **Step 1: schema/repository RED tests 작성**

  PostgreSQL Testcontainers fixture는 기존 `PostgreSQLServer.Launcher.postgres`와 `TestMutexService` serialization helper를 재사용하고, `SchemaUtils.drop/create`를 ordered table list로 호출한다. 각 lock 집합은 stable ID 오름차순으로 획득한다. warehouse, stock, order, order line, wave, pin, plan, allocation, reservation, event inbox, idempotency, outbox, local effect, audit를 저장·조회하는 테스트를 먼저 작성한다. 모든 mutable row에는 revision/version과 updated timestamp가 있고, payload에는 raw provider body/secret이 들어가지 않는다는 assertion을 둔다.

- [ ] **Step 2: Exposed table과 record/codec 구현**

  `WarehouseAllocationTables`는 `warehouse_alloc_*` 이름을 사용하고 unique/index를 `(dataset_id, revision)`, `(order_id, line_id)`, `(aggregate_type, aggregate_id, event_key)`, `(status, next_attempt_at, id)`에 둔다. `WarehouseAllocationCodec`와 HTTP/event/idempotency adapter는 하나의 `warehouse-canonical-v1` 알고리즘을 공유한다: UTF-8/NFC, object key lexicographic sort, set 성격 배열만 stable-key로 정렬하고 의미 순서 배열은 순서를 보존하며, optional `null`은 생략하고 required `null`은 거부한다. finite decimal은 exponent/scale을 canonical decimal string으로 정규화하고 `-0`은 `0`으로 만들며 UTC Instant를 정규화한다. 이 알고리즘의 golden digest fixture를 저장하고 sorted key/UTC만으로 축약 구현하지 않는다. `WarehouseAllocationRepository`는 lock 순서를 `plan -> order -> order_line -> pin -> pick_wave -> warehouse -> stock -> reservation -> inbox -> outbox -> outbox_effects -> audit`로 고정하고, 각 lock ID는 오름차순으로 취득한다. `select ... for update`/expected revision update count를 검사하며 outbox와 effect의 paired status를 DB `CHECK` 제약으로 고정한다. 최초 enqueue 직후의 `PENDING` outbox와 effect 없음만 유일한 예외로 허용하고, `PENDING→CLAIMED` 또는 `PENDING→RETRYABLE` 이후와 모든 후속 상태에서는 paired row와 orphan 0을 강제한다. `warehouse_alloc_idempotency`에는 `(http_method, route_template, demo_scope, idempotency_key)` unique claim과 concrete target/fingerprint를 저장하고, `warehouse_alloc_outbox_effects`에는 `(operation_key, effect_key)` unique constraint를 둔다.

- [ ] **Step 3: order projection/cancellation RED→GREEN**

  `OrderStatus` projection을 all CANCELLED→CANCELLED, all FULFILLED→COMPLETED, all OPEN→OPEN, mixed→PARTIALLY_ALLOCATED로 구현한다. `cancelOrder(orderId, expectedOrderRevision)`는 parent order를 먼저 lock하고 line을 CAS한 뒤 reservation release/cancel, audit, replan intent를 하나의 transaction에 쓴다. duplicate cancellation은 parent revision을 올리지 않고, line-scoped `order.cancelled`도 parent order revision/status를 갱신한다.

- [ ] **Step 4: reservation CAS RED→GREEN**

  `WarehouseAllocationReservationPostgresTest`에서 두 plan이 같은 SKU를 동시에 승인하도록 하고 한 승자만 reserved delta/reservation row를 반영하며 패자는 `RESERVATION_CONFLICT`가 되게 한다. reservation row partial write는 0, losing plan의 `activePlanId`는 NULL 또는 승자만, audit는 단일 terminal history여야 한다. `ACTIVE_PLAN_CONFLICT`, stock revision mismatch, order revision mismatch도 no-write로 검증한다.

- [ ] **Step 5: approval contention RED→GREEN**

  `WarehouseAllocationApprovalContentionPostgresTest`는 approve×cancel, approve×carrier cutoff, approve×picker capacity, approve×pin, 서로 다른 plan approve의 다섯 경합을 반복 실행한다. 각 실행은 `maxLockWait <= 2s`, deadlock 0, 정확히 하나의 terminal outcome, partial reservation/plan/outbox row 0, warehouse/stock/order revision의 monotonicity를 assertion한다.

- [ ] **Step 6: persistence targeted commands**

  `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationRepositoryTest' --max-workers=1 --console=plain`을 먼저 실행하고, Docker/Colima가 준비된 뒤 `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationReservationPostgresTest' --tests '*WarehouseAllocationApprovalContentionPostgresTest' --tests '*WarehouseAllocationOrderCancellationContractTest' --max-workers=1 --console=plain`을 순차 실행한다. fixture는 `PostgreSQLServer.Launcher.postgres`, `TestMutexService`, ordered `SchemaUtils.drop/create`, stable-ID lock order를 실제로 사용하며, pair `CHECK`/orphan row assertions를 포함한다. 실패 시 redacted SQL/lock-wait evidence를 `optimization/warehouse-allocation/build/reports/warehouse-allocation-diagnostics/**`에 저장하고 raw secret/provider body는 저장하지 않으며, 재시도만으로 통과 처리하지 않는다.

## Task 4: event inbox, stale/out-of-order replay와 idempotency lifecycle 구현

**Files:**

- Modify: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationEvents.kt`, `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/domain/WarehouseAllocationErrors.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationEventService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationIdempotencyService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationRecovery.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/EventInboxPostgresTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/persistence/WarehouseAllocationEventOrderingPostgresTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationIdempotencyRecoveryTest.kt`

- [ ] **Step 1: canonical event RED tests 작성**

  여섯 event(`inventory.adjusted`, `reservation.rejected`, `order.cancelled`, `carrier.cutoff.changed`, `picker.capacity.changed`, `warehouse.incident`)의 wire schema를 먼저 고정한다: inventory는 target `{warehouseId,sku}`와 payload `{onHandQuantity:Int}`, reservation rejected는 target `{reservationId,orderLineId}`와 `{reasonCode:RESERVATION_CONFLICT|CANCELLED|INCIDENT}`, order cancelled는 target `{orderLineId}`와 `{lineRevision:Long}`, carrier cutoff은 target `{orderLineId,carrierCode}`와 `{cutoffAt:UTC Instant}`, picker capacity는 target `{warehouseId}`와 `{capacity:Int,effectiveAt:UTC Instant}`, warehouse incident는 target `{warehouseId}`와 `{incidentCode:WAREHOUSE_INCIDENT,active:Boolean}`다. 각각 정상, same digest duplicate, same key/different digest `EVENT_KEY_REUSED`, same aggregate/source revision/different digest `EVENT_REVISION_CONFLICT`, lower revision `STALE_EVENT`, wrong target/schema `INVALID_REQUEST`를 테스트한다. `EVENT_REVISION_CONFLICT`와 `STALE_EVENT`는 redacted audit 1건만 남기고 aggregate/inbox/outbox 업무 상태를 추가 변경하지 않으며, invalid target/schema는 audit/idempotency/inbox/aggregate/outbox 모두 0건이다. duplicate만 기존 operation key와 `EventState.DUPLICATE`를 replay한다.

- [ ] **Step 2: event service 구현**

  request body를 먼저 DTO로 구조 검증하고 target binding과 payload discriminator를 확인한 뒤 canonical digest를 계산한다. unknown field/duplicate JSON key, required null, polymorphic/default typing, JSON depth 12 위반을 역직렬화 단계에서 거절하고 dataset/identifier 96/160자, event key 200자, quantity/capacity `0..1_000_000`, history 100, explanation 20×240자, body 256KiB, cursor 256자 상한을 적용한다. canonicalization은 `warehouse-canonical-v1`, Unicode NFC, finite number, UTC Instant, `-0` normalization을 사용한다. inbox unique key를 insert/CAS하고, revision 순서를 비교해 accepted/duplicate/conflict/stale를 반환한다. accepted event의 aggregate mutation, audit, replan intent는 같은 Exposed transaction으로 처리한다. `order.cancelled`는 parent order lock을 거치는 기존 repository command로 위임한다.

- [ ] **Step 2a: out-of-order/coalescing RED→GREEN**

  `WarehouseAllocationEventOrderingPostgresTest`는 source revision 5/6을 동시 ingest해 최종 aggregate revision 6, `maxRevision=6`인 pending replan intent 1개, aggregate overwrite 0을 assertion한다. revision 6 뒤 revision 5는 `STALE_EVENT` audit 1개를 남기고, revision 5 뒤 revision 6은 두 CAS를 보존하되 stale audit를 요구하지 않는다. duplicate concurrent insert는 inbox 1행과 동일 operation replay만 남긴다. 모든 revision CAS와 coalescing write는 PostgreSQL `clock_timestamp()` 기준의 짧은 transaction에서 수행한다.

- [ ] **Step 3: idempotency RED→GREEN**

  `IN_PROGRESS`, `RETRYABLE`, `COMPLETED`, `FAILED_TERMINAL` lifecycle과 fingerprint conflict, 5회/24시간 retention 경계, `RETRY_EXHAUSTED`, queue saturation(503, operationKey 없음), durable `COMMAND_IN_PROGRESS`(202, operationKey 있음)를 고정한다. fingerprint는 `(HTTP method, route template, demo scope, key)` namespace와 concrete `planId`, `pinId`, `orderId`, `generation`, `operationKey`, canonical body, schema version을 포함하고 database unique claim을 원자적으로 수행한다. 같은 key와 다른 concrete target/body는 `409 IDEMPOTENCY_FINGERPRINT_CONFLICT`와 no-write를 반환한다. startup recovery는 worker 1개, pass당 20 rows, deadline 2초, stable ID order로 처리하며 21-row fixture가 20+1 pass를 확인한다. crash 전 DB mutation이면 RETRYABLE, DB commit 후 response 전 crash면 COMPLETED를 replay한다. capacity saturation만 retryable이고, 입력/비용/출력 admission failure는 `FAILED_TERMINAL`; plan/reservation/outbox partial row는 항상 0이며 terminal/retry-exhausted response는 `409`, `retryable=false`, `nextAction=NO_RETRY`와 동일 body를 replay한다.

- [ ] **Step 4: event/idempotency commands**

  `./gradlew :optimization-warehouse-allocation:test --tests '*EventInboxPostgresTest' --tests '*WarehouseAllocationEventOrderingPostgresTest' --tests '*WarehouseAllocationIdempotencyRecoveryTest' --tests '*WarehouseAllocationHttpContractTest' --max-workers=1 --console=plain`을 실행하고, six event audit/no-write matrix, `EVENT_KEY_REUSED`/`EVENT_REVISION_CONFLICT`/`STALE_EVENT`의 fixed HTTP/error DTO와 `retryable`/`nextAction`, HTTP 409 conflict/202 duplicate mapping, recovery row counts를 결과에 남긴다.

## Task 5: plan command, approval, outbox/effect fencing와 #524 seam 구현

**Files:**

- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationCommandService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationApprovalService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationReplanService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationOutboxWorker.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/fake/DeterministicWarehouseAllocationPlanner.kt` (Task 2에서 뼈대만 만들었다면 완성)
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/PlanningContractsHttpAdapter.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationPlanningContractTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationOutboxStateIntegrationTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationOutboxStressTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/application/WarehouseAllocationLifecycleContractTest.kt`

- [ ] **Step 1: #524 seam RED tests 작성**

  `WarehouseAllocationPlanningContractTest`는 exact `POST /api/planning/requests` request와 `202 {id,status:QUEUED}` response, aggregateId `warehouse-allocation:<datasetId>:snapshot:<datasetVersion>`, aggregateVersion `0`, stable datasetId, parentRevision null을 고정한다. callback은 실제 #524 route `POST /api/planning/callbacks/{provider}`를 사용하고 body의 `eventId`, `planningRequestId`, `providerRevision`, `status`(`QUEUED|SUBMITTED|SOLVING|SUCCEEDED|FAILED`), bounded `scoreSummary` grammar `hard=<int>;medium=<int>;soft=<int>`, 기본 `constraintExplanations: []`와 response `decision`(`ACCEPTED|DUPLICATE|STALE_REVISION|AGGREGATE_CHANGED|PROVIDER_MISMATCH|REJECTED`)를 exact fixture로 검증한다. query는 `GET /api/planning/requests/{requestId}`의 exact read fields(`id`, `aggregateId`, `aggregateVersion`, `status`, `provider`, `providerRequestId`, `acceptedRevision`, `scoreSummary`, `redactedExplanation`)와 planningRequestId binding을 검증한다. provider `FAKE`는 `X-Planning-Signature: fake`를 사용하고 custom-solver HMAC verifier, canonical/idempotency fingerprint, 두 snapshot ID/replay도 고정하며 signature/provider/request/plan binding preflight 실패 전에는 #530 state를 변경하지 않는다. planning-contracts 내부 class를 직접 import하지 않고 wire DTO/HTTP adapter만 사용한다.

- [ ] **Step 2: command/replan implementation**

  request는 `datasetVersion`과 current aggregate version을 읽어 plan row와 idempotency row를 한 transaction에 만든다. planner executor는 running 2개와 waiting queue 20개로 제한하고 23번째 admission을 거부하며, queue가 꽉 차면 `PLANNER_CAPACITY_EXCEEDED`를 반환한다. 동일 fingerprint의 retryable response는 `Retry-After` 뒤 replay하고 queue saturation에는 operationKey를 만들지 않는다. deterministic fake는 provider unavailable 상태에서도 동일 proposal을 반환하며 callback raw payload와 secret을 저장하지 않는다.

- [ ] **Step 3: approval CAS와 stale history**

  approval은 plan의 `expectedOrderRevision`, `datasetVersion`, warehouse/stock/wave/pin revision을 다시 읽고 변경되면 `STALE`/`RESERVATION_CONFLICT`로 terminal history를 남긴다. 성공 시 reservation transaction, allocation pin, order line projection, audit/outbox를 함께 commit한다. replan state와 `ReplanStaleReason`은 `STALE_SOLVER_RESULT`, `ORDER_REVISION_CHANGED`, `STOCK_REVISION_CHANGED`, `WAVE_REVISION_CHANGED`, `PIN_REVISION_CHANGED`, `WAREHOUSE_REVISION_CHANGED`, `CARRIER_CUTOFF_CHANGED`, `ORDER_CANCELLED`만 허용한다.

- [ ] **Step 4: paired outbox/effect worker**

  outbox/effect pair는 최초 enqueue 시 `PENDING` outbox와 effect 없음만 허용하고, 이후 `PENDING` outbox와 effect `CLAIMED`를 함께 claim하며, outbox `DELIVERED`와 effect `COMPLETED`, outbox `DELIVERY_UNKNOWN`와 effect `RECONCILE_REQUIRED`, outbox/effect `RETRYABLE`, outbox/effect `DEAD_LETTER`만 허용한다. effect에는 `PENDING`/`APPLIED`/`FAILED` 상태를 만들지 않는다. `PENDING -> RETRYABLE` 전이는 effect `RETRYABLE`을 같은 transaction에서 생성하고, `RETRYABLE -> CLAIMED`, `DELIVERY_UNKNOWN/RECONCILE_REQUIRED -> CLAIMED/CLAIMED` reconciliation, `DELIVERED/COMPLETED` redrive 금지, `DEAD_LETTER/DEAD_LETTER -> RETRYABLE/RETRYABLE` operator redrive를 모두 matrix fixture로 고정한다. outbox retry는 `attempt <= 5`, `nextAttemptAt`, 1초에서 30초까지의 bounded backoff를 사용하며 attempt 5/6과 restart/lease sweep 후 초과 시 paired `DEAD_LETTER/DEAD_LETTER`로 원자 전이한다. 모든 claim/renew/complete/reclaim predicate는 DB `clock_timestamp()` 기준이며, send 직전 fenced effect claim을 다시 확인하고 lease/fence 조건과 `affectedRows == 1`을 검사한다. 0 rows이면 외부 provider 호출 없이 rollback/orphan no-write를 보장한다. executor는 최대 4개 동시 job, batch 20개, queue 100개로 제한하고 job timeout 10초, DB lock timeout 2초를 적용한다. lease는 15초 유효, 5초 renew, cancellation grace 5초이며 shutdown은 admission close → in-flight cancellation/quiescence → lease release → executor drain 순서로 30초 안에 끝나야 한다. duplicate provider idempotency, `DELIVERY_UNKNOWN`/`RECONCILE_REQUIRED` reconciliation, operator-only `DEAD_LETTER → RETRYABLE` redrive를 구현한다. bounded worker와 scheduler는 graceful shutdown과 cancellation을 보장한다.

- [ ] **Step 5: planning/outbox commands**

  `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationPlanningContractTest' --tests '*WarehouseAllocationOutboxStateIntegrationTest' --tests '*WarehouseAllocationLifecycleContractTest' --max-workers=1 --console=plain`을 실행한다. planning test는 running 2/queue 20/23번째 거부를 확인하고, outbox test는 최초 `PENDING`/effect 없음, `PENDING→CLAIMED`, `PENDING→RETRYABLE`, `RETRYABLE→CLAIMED`, `DELIVERY_UNKNOWN/RECONCILE_REQUIRED→CLAIMED/CLAIMED`, `DELIVERED/COMPLETED` redrive 금지, `DEAD_LETTER/DEAD_LETTER→RETRYABLE/RETRYABLE` pair transition matrix, attempt 5/6, `nextAttemptAt`, 1→30초 backoff, batch 20/21, queue 100/101, 4-worker saturation, 2초 lock timeout, DB `clock_timestamp()`, send 직전 fenced claim, 15초 lease/5초 renew, 5초 cancellation grace, 30초 shutdown, callback duplicate/out-of-order, provider timeout/restart, lease takeover, reconciliation과 redacted audit를 모두 확인한다. lifecycle test는 queued/running cancellation에서 permit/lease가 `finally`로 반환되는지, shutdown 순서와 30초 bound, crash-before-send/after-send-response-loss, capacity release retry와 `RETRY_EXHAUSTED` terminal replay를 검증한다. 실패 시 redacted DB snapshot/row-count/local-effect/audit/lock-wait와 container log를 `optimization/warehouse-allocation/build/reports/warehouse-allocation-diagnostics/**`에 남긴다.

## Task 6: HTTP wire contract, DTO validation, browser/operator recovery contract 구현

**Files:**

- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/WarehouseAllocationCanonicalizer.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/WarehouseAllocationSignatureVerifier.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/WarehouseAllocationHttpModels.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/adapter/http/WarehouseAllocationHttpService.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationDtos.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationController.kt`
- Create: `optimization/warehouse-allocation/src/main/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationExceptionHandler.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationHttpContractTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationBrowserContractTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationOperatorRecoveryContractTest.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/web/WarehouseAllocationObservabilityContractTest.kt`

- [ ] **Step 1: route/error RED tests 작성**

  Query route는 `GET /warehouse-allocation`, `GET /api/warehouse-allocation/stock`, `GET /api/warehouse-allocation/orders/{orderId}`, `GET /api/warehouse-allocation/plans/{planId}`, `GET /api/warehouse-allocation/replans/{generation}`, `GET /api/warehouse-allocation/outbox/{operationKey}`를 제공하고, command route는 `POST /api/warehouse-allocation/events`, `/replans`, `/plans/{planId}/approve`, `/plans/{planId}/reject`, `/pins`, `/orders/{orderId}/cancel`, `/outbox/{operationKey}/redrive`와 `DELETE /api/warehouse-allocation/pins/{pinId}`를 제공한다. 각 route의 exact request/response field, status, error code, `nextAction`, `Retry-After`를 golden JSON으로 고정한다. error fixture는 `EVENT_KEY_REUSED`, `EVENT_REVISION_CONFLICT`, `STALE_EVENT`, `RETRY_EXHAUSTED`, `INVALID_REQUEST_ID`를 각각 409/409/409/409/400, `retryable=false`, `nextAction=NO_RETRY`로 고정하고, `OUTBOX_NOT_REDRIVABLE`은 409/`RECONCILE`, `PLANNER_INPUT_TOO_LARGE`는 413/`SHRINK_DATASET`, `RESPONSE_TOO_LARGE`는 413/`retryable=false`/`nextAction=SHRINK_DATASET`/no-write, `PLANNER_CAPACITY_EXCEEDED`는 503/`retryable=true`/`nextAction=RETRY_AFTER`/`retryAfterSeconds=5`와 `Retry-After: 5`로 고정한다. list query는 opaque cursor 256자 이하와 limit 1..100을 사용하고 `{items,nextCursor}`를 반환한다. orders는 `reservations:[{reservationId,state}]`를 항상 반환하며 없으면 `[]`, plans의 score/allocations/reasons/history와 replan/outbox의 nullable state 규칙도 모두 fixture로 고정한다. `activePlanId`, `pinRevision`, `planId`, `staleReason`, `effectState`, `nextAttemptAt` null 규칙과 reservations `[]` 규칙을 포함한다.

- [ ] **Step 2: HTTP adapter/controller 구현**

  `WarehouseAllocationController`는 DTO를 domain model로 변환하고 service에 위임한다. 전체 `/api/warehouse-allocation/...` route 목록은 Task 6 Step 1의 exact set으로만 등록하며, mutation route는 `demo` profile에서만 등록하고 `X-Demo-Operator: true`, `Idempotency-Key`, `X-Request-Id: [A-Za-z0-9._-]{1,128}`를 모두 요구하며, non-demo profile에는 route가 없어야 한다. 기본 server/management binding은 `127.0.0.1`이고 CORS/public operator endpoint는 허용하지 않는다. 누락/형식 오류 header, invalid `X-Request-Id`는 `INVALID_REQUEST_ID` 400 no-write로 끝낸다. `WarehouseAllocationExceptionHandler`는 `INVALID_REQUEST`/`EVENT_KEY_REUSED`/`EVENT_REVISION_CONFLICT`/`STALE_EVENT` 400 또는 409 fixed DTO, `UNKNOWN_TARGET` 404, `RESERVATION_CONFLICT`/`ACTIVE_PLAN_CONFLICT`/idempotency conflict 409, `COMMAND_IN_PROGRESS` 202, planner capacity 503 with `Retry-After: 5`, `PLANNER_INPUT_TOO_LARGE` 413/`retryable=false`/`nextAction=SHRINK_DATASET`, `RESPONSE_TOO_LARGE` 413/`retryable=false`/`nextAction=SHRINK_DATASET`를 고정한다. `PLANNER_DEADLINE_EXCEEDED`와 `PLANNER_OUTPUT_TOO_LARGE`는 422, queue saturation만 retryable 503, terminal/retry exhausted replay는 `retryable=false`, `nextAction=NO_RETRY`를 포함한다. Jackson default typing은 사용하지 않으며 unknown field, duplicate JSON key, oversized body, unknown enum과 wrong target은 write 전에 거절한다.

- [ ] **Step 3: callback/signature/redaction 구현**

  기본 profile은 literal `fake`/loopback `FAKE` signature verifier만 활성화하고 custom solver HMAC은 nonblank env secret과 explicit property가 있을 때만 활성화한다. shared canonical profile은 UTF-8, Unicode NFC, finite decimal/number, exponent/scale normalization, set 배열의 stable-key sort, 의미 순서 배열 보존, optional `null` 생략, required `null` 거부, UTC Instant, `-0` normalization, control-character/HTML rejection, duplicate/unknown key rejection을 동일하게 적용하며 golden digest fixture로 검증한다. HMAC 서명 대상은 method, path, schema version, canonical body, bounded timestamp, provider/request/plan/generation binding이며 constant-time compare를 사용한다. invalid secret/signature, replayed timestamp, provider/request mismatch는 preflight에서 거절하고 #530 state를 변경하지 않는다. `createdBy`는 검증된 서버 actor/principal에서만 생성하고 caller header 값은 무시한다. `X-Demo-Operator`는 local workshop guard일 뿐 인증·인가 수단이 아니며 spoofed header negative fixture는 non-demo route 부재와 함께 no-write를 검증한다. HTTP error, audit, outbox, callback log와 metric label에는 secret, raw provider response/body, raw Idempotency-Key, signature, payload, owner token, JDBC URL, exception, order PII와 ID/key가 들어가지 않도록 field allowlist/canary fixture를 둔다.

- [ ] **Step 4: HTTP/browser/operator commands**

  `./gradlew :optimization-warehouse-allocation:test --tests '*WarehouseAllocationHttpContractTest' --tests '*WarehouseAllocationBrowserContractTest' --tests '*WarehouseAllocationOperatorRecoveryContractTest' --tests '*WarehouseAllocationObservabilityContractTest' --max-workers=1 --console=plain`을 실행한다. Testcontainers-backed HTTP test는 sequential로 실행하고, demo profile에서만 `/api/warehouse-allocation/...` mutation route가 보이며 non-demo profile/공개 origin/CORS/외부 bind에서는 route와 mutation이 모두 차단되는 negative fixture를 포함한다. browser contract는 module static/operator console이 실제 상태, stale reason, redaction, retry/reconcile action을 보여주는지 확인한다. observability contract는 `warehouse_allocation_inbox_events_total{outcome}`, `warehouse_allocation_approval_conflicts_total{reason}`, `warehouse_allocation_planner_jobs_total{outcome}`, `warehouse_allocation_outbox_queue_depth`, `warehouse_allocation_outbox_attempts_total{outcome}`, `warehouse_allocation_outbox_lease_expiry_total`, `warehouse_allocation_db_lock_wait_seconds`의 low-cardinality label만 허용하고 readiness(스키마/executor/Docker fixture), liveness(process event loop), 검증된 `X-Request-Id` audit 연결을 확인한다.

## Task 7: test-fixtures ABI와 module-level contract를 고정

**Files:**

- Create: `optimization/warehouse-allocation/src/testFixtures/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/WarehouseAllocationFixturePort.kt`
- Create: `optimization/warehouse-allocation/src/testFixtures/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/DefaultWarehouseAllocationFixturePort.kt`
- Test: `optimization/warehouse-allocation/src/test/kotlin/io/bluetape4k/workshop/optimization/warehouseallocation/fixture/WarehouseAllocationFixtureAbiTest.kt`

- [ ] **Step 1: exact ABI RED test 작성**

  ABI test는 `WarehouseAllocationFixturePort`의 package와 세 메서드 `reset(seed: Long): String`, `ingest(canonicalEvent: String): String`, `snapshot(datasetId: String): String`만 compile-time/reflection으로 확인하고, production jar에는 fixture class가 포함되지 않는다는 source-set/build artifact assertion을 둔다.

- [ ] **Step 2: deterministic fixture 구현**

  `reset(seed)`는 stable datasetId와 version 0 fixture를 만들고, `ingest`는 canonical event JSON만 받아 accepted/duplicate/error result를 반환하며, `snapshot`은 redacted JSON으로 stable ordering과 `reservations: []`를 반환한다. fixture는 provider/WMS/Redis 없이 동작하고 same seed/replay는 byte-identical 결과를 낸다.

- [ ] **Step 3: ABI commands**

  `./gradlew :optimization-warehouse-allocation:testFixturesJar :optimization-warehouse-allocation:test --tests '*WarehouseAllocationFixtureAbiTest' --max-workers=1 --console=plain`을 실행하고 `jar tf`로 fixture가 production `bootJar`에 들어가지 않는지 확인한다.

## Task 8: 저장소 등록, README/lesson, workflow와 검증 matrix 동기화

**Files:**

- Create: `optimization/warehouse-allocation/README.md`
- Create: `optimization/warehouse-allocation/README.ko.md`
- Modify: `.github/workflows/Examples.yml` container test task/report artifact allowlist
- Modify: `scripts/smoke-validate.sh` optimization case와 stale-check required module array
- Modify: `optimization/README.md`, `optimization/README.ko.md`
- Modify: `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md`
- Modify: `docs/lessons/2026-05-23-issue-91-validation-matrix.md`
- Create: `docs/lessons/2026-08-24-issue-530-warehouse-allocation.md`

- [ ] **Step 1: registration RED checks 작성**

  module path, `build.gradle.kts`, README pair, workflow task/artifact, smoke optimization task, stale-check array, matrix T3/T4와 lesson row가 없으면 실패하는 shell/Gradle contract check를 먼저 추가하거나 기존 helper invocation으로 실패를 관찰한다.

- [ ] **Step 2: registration implementation**

  `Examples.yml`의 기존 `optimization/**` path filter는 중복하지 않고 container task에 `:optimization-warehouse-allocation:test`, artifact allowlist에 `optimization/warehouse-allocation/build/test-results/test/**`, `optimization/warehouse-allocation/build/reports/tests/**`, `optimization/warehouse-allocation/build/reports/warehouse-allocation-diagnostics/**`, `optimization/warehouse-allocation/build/reports/performance/*.jfr`, redacted container log를 `if: always()`로 업로드하도록 추가한다. smoke optimization case에 같은 task를 추가하고 stale-check required module에 `optimization/warehouse-allocation`을 넣는다. 새 module `README.md`와 `README.ko.md`에는 같은 module 설명·명령·비목표·provider limitation과 health 판독, `operationKey` 조회, `DELIVERY_UNKNOWN` reconciliation, operator-only `DEAD_LETTER` redrive, `DELIVERED` redrive 금지, shutdown, Docker PENDING 진단 순서의 recovery runbook을 작성하고, `X-Demo-Operator`가 인증·인가 수단이 아닌 local workshop guard라는 경고를 포함한다. 상위 `optimization/README.md`와 `README.ko.md`에도 같은 module 행을 추가한다. validation matrix T3에는 `:optimization-warehouse-allocation | PostgreSQL`, T4에는 `./gradlew :optimization-warehouse-allocation:test --max-workers=1`을 추가한다. lesson에는 결정, 예상 밖의 문제, 검증 결과, 재발 방지 guard를 한국어로 기록한다.

- [ ] **Step 3: docs/registration verification**

  `./gradlew projects --console=plain`, `bash scripts/smoke-validate.sh optimization`, `node scripts/validate-readme-language.mjs optimization/README.md optimization/README.ko.md optimization/warehouse-allocation/README.md optimization/warehouse-allocation/README.ko.md`, `actionlint .github/workflows/Examples.yml`, `git diff --check`를 실행한다. README command와 실제 task, artifact path와 실제 report path, stale-check file list를 read-back한다.

## Task 9: 통합 검증, 성능/안정성 scan, plan/spec traceability

**Files:**

- Modify: `docs/superpowers/plans/2026-08-24-issue-530-warehouse-allocation-plan.md` (검증 evidence checkbox)
- Modify: `docs/lessons/2026-08-24-issue-530-warehouse-allocation.md` (최종 evidence)

- [ ] **Step 1: targeted → module full validation**

  다음 순서로 의존 작업을 검증한다.

  ```bash
  ./gradlew :optimization-warehouse-allocation:test --max-workers=1 --console=plain
  ./gradlew :optimization-warehouse-allocation:build --max-workers=1 --console=plain
  ./gradlew :optimization-warehouse-allocation:detekt --max-workers=1 --console=plain
  ./gradlew :optimization-warehouse-allocation:testFixturesJar --max-workers=1 --console=plain
  ./gradlew :optimization-planning-contracts:test :optimization-field-service-dispatch:test :optimization-warehouse-allocation:test --max-workers=1 --console=plain
  ./gradlew projects --console=plain
  ```

  Testcontainers 명령은 macOS Colima context와 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` 상속을 확인한 뒤 순차 실행한다. skipped/IN_PROGRESS job을 성공으로 세지 않는다.

- [ ] **Step 2: performance/stability scan**

  설치된 `$bluetape-full-feature` reference `/Users/debop/.codex/skills/bluetape-full-feature/references/performance-stability-scan.md` 기준으로 planner allocation pressure, executor queue/backpressure, lock wait/deadlock, outbox lease cleanup, cancellation/timeout, Testcontainers cleanup을 점검한다. `WarehouseAllocationPlannerStressTest`는 최대 2M candidate와 10k stock hot path를 반복해 allocation/GC diagnostic만 남기고, `WarehouseAllocationOutboxStressTest`는 100 queued outbox와 4-worker admission/lease churn을 반복한다. throughput 목표를 새로 만들지 않고 spec의 deterministic bounds와 failure/no-write 결과만 판단한다. PostgreSQL 실행 전 `colima status`, `docker context show`, `docker info`를 실행해 active context와 inherited `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 확인한다. container test가 skip되거나 Docker가 unavailable이면 성공으로 세지 않고 redacted DB snapshot, row-count/local-effect/audit/lock-wait report와 redacted container log를 `optimization/warehouse-allocation/build/reports/warehouse-allocation-diagnostics/**`에 남기고 PENDING evidence로 표시한다.

  정확한 diagnostic 명령은 다음과 같다.

  ```bash
  ./gradlew :optimization-warehouse-allocation:test \
    --tests '*WarehouseAllocationPlannerStressTest' \
    --tests '*WarehouseAllocationOutboxStressTest' \
    -Dwarehouse.allocation.jfr=optimization/warehouse-allocation/build/reports/performance/warehouse-allocation.jfr \
    --max-workers=1 --console=plain
  ```

  테스트가 property를 받으면 JDK Flight Recorder 결과를 `optimization/warehouse-allocation/build/reports/performance/warehouse-allocation.jfr`에 쓰고, 테스트 결과 XML은 Gradle 표준 `optimization/warehouse-allocation/build/test-results/test/`에 남긴다. 문제가 발견되면 해당 task로 되돌아가 RED/GREEN을 반복한다.

- [ ] **Step 3: spec-to-plan-to-code traceability**

  설계서의 모든 수용 기준(재고/capability/cutoff/pin hard constraints, transactional approval, six events, duplicate/out-of-order/restart/retry/cancellation convergence, public query/command/nullability, #524 seam, resource bounds, registration)을 Task 2~8과 exact test/class/command에 매핑한다. unchecked criterion, untracked source, stale README/workflow path가 있으면 completion을 보류한다.

- [ ] **Step 4: final plan evidence**

  `git diff --check`, `git status --short`, changed-file list, test report existence, `rg`-based stale refs, README parity/language, source-set ABI, and exact Gradle task names를 fresh output으로 저장한다. 이 단계는 implementation completion을 주장하지 않고 Step 5 verifier로 넘긴다.

## Rollback과 재실행 지점

- Task 1~2 실패: 새 module directory만 제거하고 기존 optimization modules에는 변경하지 않은 채 runtime/planner tests부터 재실행한다.
- Task 3~5 실패: `SchemaUtils.drop`으로 fixture schema를 되돌리고, 현재 branch의 previous green test boundary에서 repository/application task를 재실행한다. production migration이나 외부 provider side effect는 없다.
- Task 6 실패: HTTP adapter/controller만 되돌리고 pure planner/repository tests는 보존한다. wire contract가 변경되면 설계서와 plan을 먼저 다시 승인·리뷰한다.
- Task 8 실패: module source를 건드리지 않고 README/workflow/smoke/matrix/lesson registration만 수정·검증한다.
- Docker/Testcontainers가 unavailable이면 container-backed tests를 성공으로 처리하지 않고, `colima status`, `docker context show`, `docker info` 증거를 남긴 뒤 환경 복구 또는 명시적 PENDING으로 보고한다.

## Plan self-review

- Spec coverage: planner/model/resource(Tasks 1~2), PostgreSQL authority/CAS/order/reservation(3), event/replay/idempotency(4), #524/approval/outbox(5), HTTP/nullability/redaction(6), fixture ABI(7), registration/docs/lessons(8), full verification(9)로 모든 acceptance/DoD 항목을 덮는다.
- Ordering: module/resources → domain/planner → persistence → events/idempotency → application/#524 → HTTP → fixtures → registration → full verification 순서이며 후행 산출물을 선행 task가 참조하지 않는다.
- Pattern/hazard coverage: Kotlin pattern, Exposed v1 lock/CAS, Spring Boot main/profile, TDD RED/GREEN, virtual-thread lifecycle, PostgreSQL/Testcontainers serialization, BOM-only consumer rule, workflow/report/stale-check/README parity를 task에 포함했다.
- Public/prose impact: test-fixtures ABI와 HTTP wire contract, Korean README/KDoc/lesson, validation matrix, smoke/workflow artifact를 명시했다. PR/merge/release는 범위 밖이며 별도 승인 gate로 남긴다.

## Step status at creation

- [ ] Tasks 1~9 implementation evidence
- [ ] Six-lane Step 3-R review and main integration PASS
- [ ] Approved plan/spec/docs commit before Step 4
- [ ] Step 4 implementation, Step 5 verifier, Step 6 review, Step 7 lesson, and PR/merge gates

## 2026-08-24 구현 증거

- [x] warehouse-allocation 모듈, deterministic planner, PostgreSQL reservation authority,
  event inbox, durable replan, outbox lease/effect, HTTP query/command, fixture ABI를
  구현했다.
- [x] 모듈 테스트 13개와 PostgreSQL Testcontainers 저장소 테스트 6개가 통과했다.
- [x] optimization smoke, Gradle project registration, workflow lint, README language,
  `git diff --check`, test-fixtures/bootJar 경계를 확인했다.
- [ ] `detekt`는 모듈 task가 없어 검증하지 못했다. 이는 현재 저장소 build convention의
  검증 공백이다.
- [ ] HTTP 전체 end-to-end/stress suite와 모든 mutation route의 공통 idempotency
  service wiring은 후속 hardening 범위다.

따라서 본 계획의 구현 DoD는 `DONE (bounded #530 implementation)`으로 판정하되,
위 두 정적 분석·hardening 항목은 `PENDING`으로 추적한다. PR 생성·merge·release는
이 계획의 범위가 아니며 별도 승인 단계다.
