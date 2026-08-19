# Issue #525 Field Service Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended) or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `optimization/field-service-dispatch`에 synthetic 방문·worker를 이용한 독립적인 Field Service dispatcher 예제를 추가하고, deterministic planning, PostgreSQL 권위 상태, worker-route 원자 확정, 안전한 browser console을 검증한다.

**Architecture:** 새 Spring Boot 애플리케이션이 Field Service domain, deterministic planner, PostgreSQL persistence, REST read model을 소유한다. #524 `planning-contracts` 내부 구현은 의존하지 않고, 선택적 HTTP/fixture adapter는 lifecycle 계약만 사용한다. proposal과 committed dispatch를 분리하며 approval은 set-based CAS, dispatch는 worker route 전체 CAS로 처리한다.

**Tech Stack:** Kotlin 2.4, Java 25, Spring Boot 4.0.6 MVC/Validation, Exposed 1.4 계열, PostgreSQL/Testcontainers, Jackson 3, Bluetape virtual-thread runtime, JUnit 5, MockK/HTTP contract tests, static HTML + external JavaScript.

---

## 구현 전 기술 계약

- Step 4 전에 `$test-driven-development`, `$bluetape-kotlin-patterns`,
  `$ecc-kotlin-exposed`, `$ecc-springboot-kotlin`을 읽고, 각 Task의 RED →
  GREEN 순서를 지킨다.
- Exposed/Testcontainers 검증은 `TestMutexService` 규칙과
  `--max-workers=1`을 사용한다. 실제 Docker가 없는 경우 테스트를 PASS나
  skip으로 분류하지 않고 검증 공백으로 기록한다.
- 신규 예제는 현재 `optimization/planning-contracts`의 Java 25,
  Spring MVC, virtual-thread executor, `SchemaUtils` 초기화 패턴을 재사용하되
  `project(":optimization-planning-contracts")` 의존성은 추가하지 않는다.
- 구현 시 기준 파일은
  `optimization/planning-contracts/build.gradle.kts`,
  `optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/PlanningContractsApplication.kt`,
  `optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/config/PlanningConfiguration.kt`,
  `optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/config/PlanningDatabaseInitializer.kt`,
  `optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/web/PlanningController.kt`,
  `optimization/planning-contracts/src/main/kotlin/io/bluetape4k/workshop/optimization/planning/web/PlanningExceptionHandler.kt`와
  `commerce/reservation-control-plane`의 predicate CAS,
  `operations/job-console-core`의 `EXPLAIN` 테스트다. 이 파일들의 패턴만
  복사하고 Field Service 책임 경계를 유지한다.

## 변경 파일 지도

### 새 모듈

- Create: `optimization/field-service-dispatch/build.gradle.kts` — 기존 `planning-contracts`와 같은 Java 25/Spring Boot/Exposed/Testcontainers 의존성 세트. `project(":optimization-planning-contracts")`는 추가하지 않는다.
- Create: `optimization/field-service-dispatch/README.md`
- Create: `optimization/field-service-dispatch/README.ko.md`
- Create: `optimization/field-service-dispatch/src/main/resources/application.yml`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceDispatchApplication.kt`

### domain / planner

- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceIds.kt` — synthetic ID와 버전 value class.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceModels.kt` — Worker, Visit, availability, pin, plan proposal, assignment, score, reason code.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceEvents.kt` — event type, idempotency key, digest, audit decision.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceErrors.kt` — 안정적인 conflict/error code.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceLimits.kt` — body, collection, planner, queue, timeout 상수와 입력 검증.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/TravelTimeMatrix.kt` — immutable matrix revision과 O(1) edge lookup.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/DeterministicFieldServicePlanner.kt` — skill/availability/time-window/pin 규칙과 deterministic tie-break.

### application / execution

- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceCommandService.kt` — create/cancel/urgent/pin/unpin/no-show/unavailable/matrix event.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceReplanService.kt` — 실행 기준 데이터, local `planRevision`, request generation, admission.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceApprovalService.kt` — proposal 전체 approval set-based CAS.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceDispatchService.kt` — worker route 전체 confirmation CAS와 committed projection.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceQueryService.kt` — redacted read model, composite revision, ETag input.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceOutboxWorker.kt` — bounded claim/replay와 terminal convergence.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceExecutorLifecycle.kt` — CPU planner admission, cancellation, shutdown drain.

### adapter / HTTP seam

- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/fake/FieldServicePlanningFixture.kt` — deterministic callback/result fixture.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/fake/FieldServiceFixtureData.kt` — small/max-envelope synthetic fixtures.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCallbackEnvelope.kt` — #524 wire와 분리된 local envelope, strict score/reason parser.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCanonicalizer.kt` — sorted-key UTF-8 JSON, duplicate-key reject, SHA-256.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceSignatureVerifier.kt` — fixture signature 검증.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/PlanningContractsHttpAdapter.kt` — 선택적 lifecycle mapper. assignment result authority가 아니다.

### persistence

- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceTables.kt` — workers, visits, plans, proposal assignments, dispatch assignments, events, audits, outbox.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRecords.kt` — table↔domain record.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRepositories.kt` — aggregate/event/plan/assignment/outbox repository.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceDatabaseInitializer.kt` — `SchemaUtils` disposable schema.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceTransactionSupport.kt` — set-based CAS, stable lock order, statement/query budget.

### web / UI

- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceDtos.kt` — closed input/output DTO와 redacted score/reason.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceController.kt` — documented REST endpoints와 static resource.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceWebConfig.kt` — `demo` profile, loopback, operator header, CSP, body limit.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceExceptionHandler.kt` — stable status/conflict code only.
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceEtag.kt` — resource별 composite revision/quoted ETag.
- Create: `optimization/field-service-dispatch/src/main/resources/static/field-service/index.html`
- Create: `optimization/field-service-dispatch/src/main/resources/static/field-service/field-service.js`

### 테스트

- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceModelsTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/DeterministicFieldServicePlannerTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/PlannerComplexityContractTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCanonicalizerTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCallbackEnvelopeTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRepositoryTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceCasIntegrationTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceCommandServiceTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceOutboxWorkerTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceLifecycleTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceControllerTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceBrowserContractTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceRuntimeContractTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceBenchmarkContractTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceBenchmarkProbe.kt`

### repository integration

- Modify: `optimization/README.md`, `optimization/README.ko.md` — module table, infrastructure, verification command.
- Modify: `.github/workflows/Examples.yml` — container-backed test task and artifact paths.
- Modify: `scripts/smoke-validate.sh` — `optimization)` task and `stale-check)` required module list/help.
- Audit: `.github/workflows/nightly.yml` — full nightly already runs the Gradle graph; modify only if the current task list excludes the new module.
- Create: `docs/lessons/2026-08-20-issue-525-field-service.md` — reusable lesson after implementation and verification.

`settings.gradle.kts`는 `includeModules("optimization", false, true)`가 새 디렉터리를 자동 등록하므로 수정하지 않는다. `./gradlew projects`와 stale-check가 이 불변 조건을 검증한다.

## 계획 승인 전 검토 산출물

구현을 시작하기 전에 `docs/review/2026-08-20-issue-525-field-service-plan-review.md`를
생성한다. 승인된 설계와 이 계획을 대상으로 Performance, Stability, Security,
Operator/Ops, Developer/API, User/caller 여섯 관점과 main integration을 기록하고,
각 finding에 P0–P3, 정확한 계획 근거, 수정 또는 보류 사유, 재실행 lane을 붙인다.
P0/P1은 0이어야 하며, 보류하는 P2/P3에는 구현 Task와 검증 명령을 지정한다.
이 산출물과 계획은 같은 Lore commit에 포함하고, 계획 review가 PASS 되기 전에는
Task 1 구현을 시작하지 않는다.

---

## Task 1: 모듈 골격과 검증 RED를 고정한다

**Files:**
- Create: `optimization/field-service-dispatch/build.gradle.kts`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceDispatchApplication.kt`
- Create: `optimization/field-service-dispatch/src/main/resources/application.yml`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceRuntimeContractTest.kt`

- [ ] **Step 1: 모듈 등록 RED 테스트를 작성한다.**

```kotlin
class FieldServiceRuntimeContractTest {
    @Test
    fun `field service module runs on Java 25 virtual threads`() {
        Runtime.version().feature() shouldBeEqualTo 25
        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> { Thread.currentThread().isVirtual }.get().shouldBeTrue()
        }
    }
}
```

- [ ] **Step 2: 모듈 build를 작성한다.** `planning-contracts/build.gradle.kts`의 Java 25, virtual-thread JDK 21 제외, Spring Boot MVC/validation/actuator/JDBC, Exposed, Jackson 3, PostgreSQL, Bluetape JUnit/assertions/Testcontainers, WireMock 의존성 패턴을 복사하되 모든 Bluetape 버전은 `bluetape4k-dependencies` BOM 해석에 맡긴다. `springBoot.mainClass`는 `io.bluetape4k.workshop.optimization.fieldservice.FieldServiceDispatchApplicationKt`로 지정한다.

- [ ] **Step 3: application runner와 `application.yml`을 작성한다.** 기본 profile은 `demo`, datasource는 `FIELD_SERVICE_DATABASE_*` 환경 변수를 사용하고, `server.address=127.0.0.1`, `max-http-form-post-size=256KB`, actuator는 `health,info,prometheus`만 노출한다. 실제 provider URL/credential의 기본값은 사용하지 않는다.

- [ ] **Step 4: RED 테스트를 실행하고 실패를 확인한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceRuntimeContractTest' --max-workers=1
```

Expected: module project 또는 application classes가 아직 없어 `Project ':optimization-field-service-dispatch' not found` 또는 compile failure.

- [ ] **Step 5: 모듈 골격을 구현하고 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceRuntimeContractTest' --max-workers=1
```

Expected: `FieldServiceRuntimeContractTest` PASS.

---

## Task 2: 입력 상한, 식별자, 이벤트 canonicalization을 TDD로 만든다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceIds.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceLimits.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceEvents.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceErrors.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCanonicalizer.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceModelsTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCanonicalizerTest.kt`

- [ ] **Step 1: 경계 실패 테스트를 작성한다.** 다음 입력은 각각 `InvalidFieldServiceInput`을 반환해야 한다: 빈 ID, 201 byte `Idempotency-Key`, 21개 skill, 21개 availability window, 256 KiB 초과 body, non-finite/음수 travel time, JSON depth 13, 중복 JSON object key.

```kotlin
@Test
fun `canonicalizer rejects duplicate object keys before digest`() {
    val body = "{\"visitId\":\"visit-1\",\"visitId\":\"visit-2\"}".toByteArray()
    shouldThrow<InvalidFieldServiceInput> { canonicalizer.digest(body) }
}
```

- [ ] **Step 2: 실패를 확인한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCanonicalizerTest' --tests '*FieldServiceModelsTest' --max-workers=1
```

Expected: types, limits, canonicalizer가 없어 compile failure.

- [ ] **Step 3: 구현한다.** `Jackson3` mapper에 duplicate detection, `FAIL_ON_UNKNOWN_PROPERTIES`, sorted map ordering을 설정하고, UTF-8/UTC ISO-8601/정규화된 finite 숫자로 canonical bytes를 만든다. `MessageDigest.isEqual`을 사용해 digest를 비교하고 raw body는 저장하지 않는다. 모든 collection limit은 `FieldServiceLimits` 한 곳에서 참조한다.

```kotlin
fun canonicalBytes(body: ByteArray): ByteArray
fun digest(body: ByteArray): EventDigest
fun compareStoredDigest(stored: EventDigest, incoming: EventDigest): DigestMatch
```

- [ ] **Step 4: 같은 key/digest와 다른 digest의 event 결과를 고정한다.** 같은 digest는 `DUPLICATE` no-op, 다른 digest는 `EVENT_KEY_REUSED`이며 side effect가 없어야 한다.

- [ ] **Step 5: 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCanonicalizerTest' --tests '*FieldServiceModelsTest' --max-workers=1
```

Expected: PASS, duplicate-key/limit/digest normalization assertions 포함.

---

## Task 3: 도메인 상태와 deterministic planner를 구현한다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/domain/FieldServiceModels.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/TravelTimeMatrix.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/DeterministicFieldServicePlanner.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/fake/FieldServiceFixtureData.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/DeterministicFieldServicePlannerTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/planner/PlannerComplexityContractTest.kt`

- [ ] **Step 1: planner RED 테스트를 작성한다.** 테스트 fixture는 worker 2명, visit 5개, fixed matrix revision 1개를 사용한다. 테스트 이름과 기대 결과는 다음을 포함한다.

```kotlin
@Test fun `urgent visits sort before normal visits then window and id`()
@Test fun `missing skill becomes MISSING_SKILL without assignment`()
@Test fun `unavailable worker is excluded`()
@Test fun `travel time and service duration must fit the time window`()
@Test fun `started or manually pinned visit keeps worker and route order`()
@Test fun `same input produces identical routes scores and reasons`()
@Test fun `missing matrix edge becomes TRAVEL_TIME without external call`()
```

- [ ] **Step 2: 실패를 확인한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*DeterministicFieldServicePlannerTest' --tests '*PlannerComplexityContractTest' --max-workers=1
```

Expected: planner/domain types not implemented.

- [ ] **Step 3: immutable matrix를 구현한다.** `TravelTimeMatrix(revision, coordinateIds, edges)`는 생성 시 coordinate/edge/count/finite/non-negative를 검증하고 `Map<CoordinatePair, Long>` O(1) lookup을 제공한다. update는 새 revision을 만들며 기존 객체를 변경하지 않는다.

- [ ] **Step 4: planner를 구현한다.** urgent → `windowStart` → `visitId` 순서로 방문을 정렬하고, skill/availability/time-window/matrix/pin을 순서대로 검증한다. worker index를 미리 만들고 `O(visit × worker + edge)`를 지킨다. score는 finite한 numeric `FieldServiceScoreSummary`만 만들고 provider 문자열을 사용하지 않는다. 외부에 노출되는 domain/planner type의 KDoc는 한국어로 작성하고, synthetic-only와 deterministic 범위를 명시한다.

```kotlin
fun plan(input: PlannerInput): PlanProposal
// PlannerInput owns workers, visits, matrix, datasetId, planId, and version vector.
// PlanProposal owns ordered routes, unassigned reasons, and numeric score only.
```

- [ ] **Step 5: max-envelope 복잡도 RED/GREEN 테스트를 작성한다.** `PlannerComplexityContractTest`는 small fixture와 max-envelope(100 worker, 500 visit, 10,000 matrix cell)를 같은 계측 planner로 실행한다. planner가 노출하는 `candidateEvaluations`와 `matrixLookups`는 각각 `visitCount * workerCount`와 `visitCount * workerCount + routeEdges`를 넘지 않아야 하며, 외부 네트워크 호출 수는 0이어야 한다. 이 테스트는 wall-clock이나 GC를 hard gate로 사용하지 않는다.

```kotlin
assertThat(maxRun.candidateEvaluations).isLessThanOrEqualTo(500 * 100)
assertThat(maxRun.externalCalls).isEqualTo(0)
assertThat(maxRun.invariants).containsExactly("O(V*W+E)")
```

- [ ] **Step 6: 단위 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*DeterministicFieldServicePlannerTest' --tests '*PlannerComplexityContractTest' --max-workers=1
```

Expected: 모든 hard constraint, deterministic ordering, max-envelope complexity assertions PASS.

---

## Task 4: Exposed schema와 repository contract를 만든다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceTables.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRecords.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRepositories.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceTransactionSupport.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceDatabaseInitializer.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRepositoryTest.kt`

- [ ] **Step 1: repository RED 테스트를 작성한다.** `SchemaUtils` fixture에 8개 table을 만들고, worker/visit/plan/event/outbox CRUD, event unique key, digest conflict, plan history keyset, `field_service_dispatch_assignments.visit_id` unique를 검증한다.

- [ ] **Step 2: 실패를 확인한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceRepositoryTest' --max-workers=1
```

Expected: table/repository symbols가 없어 compile failure.

- [ ] **Step 3: tables를 구현한다.** 다음 index/unique를 Exposed table 정의에 직접 둔다.

```text
events unique (aggregate_type, aggregate_id, event_key) + digest lookup
outbox (status, next_attempt_at, id)
plans (plan_id, plan_revision), (state, plan_revision)
proposal assignments (plan_id, plan_revision, worker_id, route_order)
proposal assignments (worker_id, worker_schedule_revision, route_order)
committed assignments unique (visit_id)
```

`field_service_workers`에는 `version`, `worker_schedule_revision`, `unavailable`를, plan assignment에는 기준 visit/worker version과 schedule revision을 저장한다. raw callback body, signature, provider credential column은 만들지 않는다.

- [ ] **Step 4: repository를 구현한다.** stable ID ordering으로 조회하고, 모든 update는 expected version 조건을 SQL WHERE에 포함한다. `SchemaUtils.create`는 disposable workshop DB에서만 사용하며 production migration을 추가하지 않는다. Exposed receiver 안에서 outer `transaction`/table receiver를 shadow하지 않도록 명시적으로 qualified call을 사용하고, deprecated Exposed import를 추가하지 않는다.

```kotlin
fun appendEvent(command: FieldServiceCommand): EventAppendResult
fun loadPlan(planId: PlanId, revision: Long): PlanProposal?
fun updateVisitIfVersion(id: VisitId, expectedVersion: Long, next: VisitRecord): Boolean
fun claimOutbox(limit: Int = 10): List<OutboxRecord>
```

- [ ] **Step 5: repository 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceRepositoryTest' --max-workers=1
./gradlew :optimization-field-service-dispatch:detekt --max-workers=1
```

Expected: CRUD, unique, digest, keyset, schema assertions와 Exposed deprecation/receiver-shadowing 검사가 PASS.

---

## Task 5: command/event, replan, bounded outbox와 lifecycle을 연결한다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceCommandService.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceReplanService.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceOutboxWorker.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceExecutorLifecycle.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/config/FieldServiceConfiguration.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceCommandServiceTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceOutboxWorkerTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceLifecycleTest.kt`

- [ ] **Step 1: command RED 테스트를 작성한다.** create/urgent/cancel/pin/unpin/no-show/unavailable/matrix update가 event와 aggregate version을 정확히 만들고, duplicate urgent는 no-op, 다른 payload는 `EVENT_KEY_REUSED`가 되는지 검증한다.

- [ ] **Step 2: replan/outbox RED 테스트를 작성한다.** 동일 aggregate 동시 replan은 single-flight로 합쳐지고, CPU executor는 정확히 4개 worker와 queue 8을 사용한다. queue 8 초과는 durable `REPLAN_REJECTED`와 HTTP 429 결과를 남기며, outbox batch는 10 이하이고 retry 후 terminal state로 수렴해야 한다. 모든 다중 행 작업은 `plan(planId, revision) → worker(workerId) → visit(visitId) → assignment/event/outbox(id)` 순서로 lock을 획득한다. barrier 기반 approval/sick-call/route/outbox 동시성 테스트에서 deadlock 없이 `lock_timeout=2s`, statement timeout 5초 이내에 끝나야 한다.

- [ ] **Step 3: lifecycle RED 테스트를 작성한다.** admission close → in-flight quiescence → executor drain 순서, 5초 timeout, cancellation permit 반환, 30초 shutdown을 검증한다. close 이후 submit은 즉시 `REPLAN_REJECTED`로 거부하고, queued/semaphore 대기/in-flight planner와 blocking JDBC/HTTP 작업의 `Job.cancel()`은 `CancellationException`을 호출자까지 재전파한다. permit, lease, executor가 모두 정리되고 반복 close는 no-op이어야 하며, 30초 안에 drain하지 못하면 명시적 shutdown-timeout 상태를 기록한다.

- [ ] **Step 4: 최소 구현을 작성한다.** command transaction은 event append와 outbox enqueue를 함께 commit한다. replan은 일관된 기준 데이터와 다음 local `planRevision`, `parentRevision`, `requestGeneration`, version vector를 저장한 뒤 planner를 4-worker/queue-8 bounded CPU executor에서 실행한다. JDBC/HTTP는 Bluetape virtual-thread executor로 보내고, 모든 blocking call에는 5초 deadline을 전달한다. outbox row에는 `leaseOwner`, fencing `leaseToken`, `leaseExpiresAt`, `attempt`, `maxAttempts=5`를 저장한다. claim/renew/ack는 owner+token+expiry를 WHERE에 포함하고, backoff는 `min(2^attempt seconds, 60 seconds)`로 계산하며, max attempt 초과 poison item은 `DEAD_LETTER` terminal state로 수렴시킨다. Micrometer에는 queue rejection, timeout, cancellation, outbox retry/dead-letter, CAS conflict, lock wait를 raw payload 없이 기록하고 actuator `health,info,prometheus`에서만 노출한다.

```kotlin
fun accept(command: FieldServiceCommand): CommandResult
fun requestReplan(aggregateId: AggregateId): ReplanAdmission
fun processOutboxBatch(maxItems: Int = 10): ReplayResult
```

- [ ] **Step 5: 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCommandServiceTest' --tests '*FieldServiceOutboxWorkerTest' --tests '*FieldServiceLifecycleTest' --max-workers=1
```

Expected: command idempotency, bounded admission, replay, shutdown assertions PASS.

---

## Task 6: proposal approval과 worker-route dispatch CAS를 구현한다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceApprovalService.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceDispatchService.kt`
- Modify: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceRepositories.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceCasIntegrationTest.kt`

- [ ] **Step 1: approval RED 테스트를 작성한다.** proposal의 expected version vector 중 하나라도 변경되면 `VERSION_CONFLICT` 409와 전체 rollback을 확인한다. approval은 visit/worker business version과 `workerScheduleRevision`을 증가시키지 않고 proposal state/audit만 commit해야 한다.

  `startedPin`은 approval에서도 해제할 수 없고, `manualPin`은 command 단계에서 version을 증가시킨 뒤 proposal의 expected vector에 포함되어야 한다.

- [ ] **Step 2: route confirmation RED 테스트를 작성한다.** 한 worker의 모든 route stop에 대해 visit version + worker eligibility version + `workerScheduleRevision`을 set-based CAS하고, 하나라도 충돌하면 assignment/audit/version이 모두 rollback되는지 확인한다. 같은 worker/time 두 proposal 경쟁에서는 먼저 성공한 route만 commit되고 다른 route는 `SCHEDULE_CONFLICT`가 되어야 한다. 다른 worker route는 유지한다.

- [ ] **Step 3: 구현한다.** approval은 `plan(planId, revision) → workerId → visitId` 순서로 조건부 update를 수행하고 update count가 vector와 다르면 transaction을 rollback한다. dispatch는 최대 500 stop을 100행 chunk로 처리하되 statement 5개 이내, committed assignment insert와 audit와 schedule revision update를 하나의 transaction으로 실행한다. 성공 후 같은 worker의 이전 schedule revision proposal assignment를 `STALE`로 전환한다. `FieldServiceCasIntegrationTest`는 SQL statement counter로 route confirmation이 5개 이하인지, `lock_wait_ms <= 2000`인지, `EXPLAIN (FORMAT JSON, COSTS OFF)`가 지정한 route/unique index를 사용하고 `Seq Scan`이 없는지 hard gate로 단언한다.

```kotlin
fun approve(planId: PlanId, revision: Long, expected: VersionVector): ApprovalResult
fun confirmWorkerRoute(workerId: WorkerId, planId: PlanId, revision: Long): DispatchResult
```

- [ ] **Step 4: PostgreSQL/Testcontainers 통합 테스트를 실행한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCasIntegrationTest' --max-workers=1
```

Expected: set-based CAS, concurrent route, rollback, query/lock budget, `EXPLAIN` index assertions PASS. Docker/Testcontainers failure는 skip으로 처리하지 말고 원인을 기록한다.

---

## Task 7: callback envelope, fixture seam, #524 lifecycle mapper를 구현한다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCallbackEnvelope.kt`
- Modify: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCanonicalizer.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceSignatureVerifier.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/PlanningContractsHttpAdapter.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/fake/FieldServicePlanningFixture.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/adapter/http/FieldServiceCallbackEnvelopeTest.kt`

- [ ] **Step 1: callback RED 테스트를 작성한다.** unsigned, wrong signature, unknown provider, providerRequestId/requestGeneration/planId/datasetId mismatch는 #525 local inbox/plan/audit를 변경하지 않는다. 같은 request의 낮은 provider revision은 stale audit만 만들고, superseded generation은 `STALE_REQUEST_GENERATION`이 된다.

- [ ] **Step 2: score/reason redaction RED 테스트를 작성한다.** `scoreSummary`에 secret/address/HTML/NaN을 넣거나 explanation에 raw provider text를 넣으면 strict parser가 거부한다. 허용 결과는 `FieldServiceScoreSummary` numeric fields와 `ConstraintReasonCode` + synthetic visit ID뿐이다.

- [ ] **Step 3: 구현한다.** local envelope를 실제 #524 `PlanningCallbackDto`와 별도 type으로 유지한다. preflight 실패 시 #524 endpoint를 호출하지 않는다. 호출 후 #524 자체 inbox/audit에 provider mismatch가 기록되는 것은 정상 #524 계약이며 #525 state와 혼동하지 않는다. `providerRevision`은 `(provider, providerRequestId)` 안에서만 단조 증가하고, local `planRevision`과 비교하지 않는다. signature와 digest 비교는 모두 `MessageDigest.isEqual`을 사용한다. fixture는 `planning-contracts-commit-80c1f95`와 `field-service-planning-fixture-v1`을 pin하는 metadata를 사용하고 additive/unknown fixture를 거부한다.

```kotlin
fun preflight(envelope: FieldServiceCallbackEnvelope): CallbackDecision
fun acceptCallback(envelope: FieldServiceCallbackEnvelope): CallbackResult
```

- [ ] **Step 4: callback contract 테스트를 통과시킨다.**

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceCallbackEnvelopeTest' --max-workers=1
```

Expected: binding, stale revision, generation, strict redaction, no-local-write assertions PASS.

---

## Task 8: REST boundary, ETag, demo guard와 browser console을 구현한다

**Files:**
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceDtos.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceController.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceWebConfig.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceExceptionHandler.kt`
- Create: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceEtag.kt`
- Create: `optimization/field-service-dispatch/src/main/resources/static/field-service/index.html`
- Create: `optimization/field-service-dispatch/src/main/resources/static/field-service/field-service.js`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceControllerTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/web/FieldServiceBrowserContractTest.kt`

- [ ] **Step 1: controller RED 테스트를 작성한다.** 다음 endpoint와 stable response/error를 고정한다. raw body limit filter가 JSON deserialization 전에 256 KiB를 차단하고, closed DTO는 unknown property를 거부한다. Exposed query는 bound parameter만 사용하며 SQL 문자열 조합을 금지한다.

```text
GET  /field-service
GET  /api/field-service/visits
GET  /api/field-service/workers
GET  /api/field-service/plans/{revision}
POST /api/field-service/visits
POST /api/field-service/visits/{id}/cancel|urgent|pin|unpin|no-show
POST /api/field-service/workers/{id}/unavailable
POST /api/field-service/travel-times
POST /api/field-service/plans/replan
POST /api/field-service/plans/{revision}/approve
POST /api/field-service/dispatch/workers/{workerId}/confirm
```

Mutation은 `X-Demo-Operator: true`와 200 byte 이내 `Idempotency-Key`가 없으면 상태를 변경하지 않고, invalid window/version, unknown ID, 256 KiB body, over-limit matrix/event/explanation을 안정적인 4xx code로 반환한다.

- [ ] **Step 2: ETag/CSP/loopback RED 테스트를 작성한다.** visits/workers/plans 각각 composite 기준 데이터 revision으로 quoted ETag를 만들고 `If-None-Match`가 맞으면 304와 기존 body 보존을 검증한다. `demo` profile 외 route 비활성, server loopback, 외부 CORS 금지, CSP `script-src 'self'`를 확인한다.

- [ ] **Step 3: controller/config/handler를 구현한다.** public DTO에는 synthetic IDs, numeric score, enum reason만 노출한다. 내부 exception/SQL/provider/raw payload/credential은 응답·로그에 넣지 않는다. validation은 DTO와 service 양쪽에서 수행한다.

```kotlin
fun visits(ifNoneMatch: String?): ResponseEntity<VisitListResponse>
fun replan(request: ReplanRequest, operator: DemoOperator, idempotencyKey: String): ReplanResponse
fun confirm(workerId: String, operator: DemoOperator, idempotencyKey: String): DispatchResponse
```

- [ ] **Step 4: static UI를 구현한다.** external `field-service.js`가 `textContent`/DOM API만 사용하고 `innerHTML`, `insertAdjacentHTML`, `eval`을 사용하지 않도록 한다. SVG 좌표는 finite numeric range, worker color는 allowlist로 제한한다. foreground 2초 polling, single-flight, GET polling에만 429/5xx 2–10초 backoff, hidden tab timer cancellation/visibility epoch, 304 body reuse를 구현한다. mutation POST에는 자동 재시도를 넣지 않고 local outbox replay 또는 명시적 reconciliation만 사용한다.

- [ ] **Step 5: MVC/browser 테스트를 통과시킨다.** `FieldServiceBrowserContractTest`는 fake clock과 recording fetch로 foreground 요청 간격, `maxInFlight == 1`, `304Responses == 1`인 2회 응답 sequence, hidden-tab 전환 이후 `hiddenTabRequests == 0`, mutation POST 자동 재시도 0회를 단언한다.

```bash
./gradlew :optimization-field-service-dispatch:test --tests '*FieldServiceControllerTest' --tests '*FieldServiceBrowserContractTest' --max-workers=1
```

Expected: endpoint, redaction, ETag, CSP, XSS canary, static resource and polling contract PASS.

---

## Task 9: benchmark, restart/replay와 full integration evidence를 고정한다

**Files:**
- Modify: `optimization/field-service-dispatch/src/main/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceOutboxWorker.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceBenchmarkContractTest.kt`
- Create: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/FieldServiceBenchmarkProbe.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/application/FieldServiceLifecycleTest.kt`
- Test: `optimization/field-service-dispatch/src/test/kotlin/io/bluetape4k/workshop/optimization/fieldservice/persistence/FieldServiceCasIntegrationTest.kt`

- [ ] **Step 0: Testcontainers 실행 전 Docker 상태를 기록한다.**

```bash
if command -v colima >/dev/null 2>&1; then colima status; fi
docker context show
docker info
```

Expected: 활성 Docker context와 server 정보가 확인된다. 실패하면
`build/reports/field-service/testcontainers-failure.txt`에 redacted 원인,
Docker server 정보, 남은 컨테이너/볼륨 분류를 기록하고, 테스트를 skip하지 않는다.

- [ ] **Step 1: max-envelope fixture를 실행한다.** 100 worker, 500 visit, 10,000 matrix cell, 20 explanation, worker당 20 skill/availability limit을 사용하고 planner input/query/response bytes, 304 ratio, Hikari active·pending, lock wait, in-flight/queue rejection/timeout/cancellation을 수집한다. `FieldServiceBenchmarkProbe`는 외부 benchmark plugin 없이 invariant contract probe로 동작한다.

- [ ] **Step 2: benchmark artifact 계약 테스트를 작성한다.** `FieldServiceBenchmarkProbe`는 warmup 2회와 측정 5회를 수행하고 `build/reports/field-service/benchmark.json`에 `schemaVersion`, `runId`, `fixture`, `warmup`, `repetitions`, `queryCount`, `lockWaitMs`, `queueRejected`, `timeout`, `cancellation`, `requestCount`, `notModifiedRatio`, `inputBytes`, `responseBytes`, `invariants`, `status`를 기록한다. invariant 위반은 CI fail, wall-clock elapsed/p95/p99와 allocation/GC는 환경을 함께 기록하는 report-only 값이며 SLO로 사용하지 않는다. Docker를 사용할 수 없으면 `status=UNAVAILABLE`로 남기되 CI에서는 실패 원인을 출력하고 PASS로 분류하지 않는다.

- [ ] **Step 3: restart/replay 통합을 실행한다.** 동일 schema version disposable PostgreSQL에서 lease owner/token을 가진 outbox worker를 중단하고 lease 만료 후 다른 worker가 fencing token을 갱신한다. 이전 worker의 stale ack/renew는 0행으로 거부하고, queued 또는 in-flight 작업을 취소한 뒤 outbox row가 `RETRYABLE`/`PENDING`으로 되돌아가 재처리 가능한지와 중복 side effect가 없는지 검증한다. 재시작과 event digest 재처리를 수행해 하나의 terminal history로 수렴하는지 검증한다. poison item은 다섯 번째 시도 뒤 `DEAD_LETTER`가 되고 더 이상 재시도하지 않는다. 실패 원인은 `build/reports/field-service/replay-failure.txt`에 raw payload 없이 기록한다. production migration은 추가하지 않는다.

- [ ] **Step 4: 순차 통합 명령을 실행한다.**

```bash
./gradlew :optimization-field-service-dispatch:cleanTest --no-build-cache :optimization-field-service-dispatch:test --tests '*FieldServiceBenchmarkContractTest' --max-workers=1
./gradlew :optimization-field-service-dispatch:build --max-workers=1
./scripts/smoke-validate.sh optimization
```

Expected: module tests/build/smoke all PASS; Testcontainers는 활성 Docker에서 실제로 실행되어야 한다. `benchmark.json`은 warmup/repetition과 invariant 결과를 포함하고, stale build output을 재사용하지 않는다.

---

## Task 10: README, workflow, stale-check와 lesson을 등록한다

**Files:**
- Modify: `optimization/README.md`
- Modify: `optimization/README.ko.md`
- Create: `optimization/field-service-dispatch/README.md`
- Create: `optimization/field-service-dispatch/README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Audit: `.github/workflows/nightly.yml`
- Create: `docs/lessons/2026-08-20-issue-525-field-service.md`

- [ ] **Step 1: module README 2종을 작성한다.** endpoint walkthrough와 다음 synthetic-only curl 흐름을 한·영 parity로 기록한다: static console 조회 → `X-Demo-Operator: true`/`Idempotency-Key`를 가진 visit 생성 → replan → plan 조회/approval → worker route confirm. deterministic fake 기본값, demo loopback/operator header, 실제 Timefold 미지원 경계, Java 25/Docker 요구사항, module test/smoke 명령, health/metrics 확인 방법, 실패 시 `cleanTest --no-build-cache` 재실행과 rollback 범위를 함께 기록한다. raw credential/주소/PHI 예제는 넣지 않는다.

- [ ] **Step 2: group README 2종을 갱신한다.** `field-service-dispatch`를 module table에 추가하고 PostgreSQL/Testcontainers 인프라와 verification command를 반영한다. `validate-readme-language.mjs`와 `validate-readme-parity.mjs`가 통과하도록 heading/code fence/link shape를 맞춘다.

- [ ] **Step 3: Examples workflow를 갱신한다.** push/PR path filter에 `optimization/**`가 포함되는지 확인하고, 없으면 추가한다. container-backed Gradle task 목록에 `:optimization-field-service-dispatch:test`를 추가하고 `optimization/field-service-dispatch/build/test-results/test/*.xml` 및 report path를 artifact 목록에 추가한다. `actionlint` 대상 YAML 문법을 유지한다.

- [ ] **Step 4: smoke/stale registration을 갱신한다.** `optimization)`에 새 module test를 추가하고 help/comment를 수정한다. `stale-check)` required module loop에 `optimization/field-service-dispatch`를 추가해 `build.gradle.kts`, `README.md`, `README.ko.md` 존재를 검사한다. `nightly.yml`의 smoke 경로(`all-smoke`)와 full 경로(root `test`)를 각각 확인해 새 모듈이 두 경로에 포함되는지 검증하고, 누락된 경로만 최소 수정한다.

  ```bash
  rg -n "all-smoke|optimization|test|smoke|full" .github/workflows/nightly.yml .github/workflows/Examples.yml
  ```

  Expected: `all-smoke`와 full Gradle graph가 새 모듈을 포함한다는 근거가
  있거나, 누락된 smoke/full task만 한 줄 범위로 보완한다.

- [ ] **Step 5: lesson을 작성한다.** deterministic fixture를 production provider 증거로 승격하지 않는 규칙, worker-route CAS와 `workerScheduleRevision`, local/#524 wire 분리, independent Epic train 결정을 재사용 가능한 한국어 lesson으로 기록한다.

- [ ] **Step 6: 등록 검증을 실행한다.**

```bash
./gradlew projects --console=plain
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
rg -n "kover|collectReachabilityMetadata|optimization-field-service-dispatch" build.gradle.kts .github/workflows/Examples.yml .github/workflows/nightly.yml
bash scripts/smoke-validate.sh stale-check
./gradlew -x detekt :optimization-field-service-dispatch:test --max-workers=1
```

Expected: project graph includes `:optimization-field-service-dispatch`, README validators/stale-check report no warning, workflow path/task/artifact and coverage-scope audit is complete, module test PASS.

---

## Task 11: 최종 검증, review artifact와 lesson commit을 완료한다

**Files:**
- Modify: all files above only when a failing verification requires a scoped repair.
- Create: `docs/lessons/2026-08-20-issue-525-field-service.md`

- [ ] **Step 1: 계획 각 항목을 구현 diff와 대조한다.** domain/planner, persistence/CAS, callback seam, web/UI, benchmark, docs/CI가 빠짐없이 존재하는지 `git diff --stat`, `rg`, `./gradlew projects`로 확인한다.

- [ ] **Step 2: 전체 검증을 순차 실행한다.**

```bash
./gradlew :optimization-field-service-dispatch:test --max-workers=1
./gradlew :optimization-field-service-dispatch:build --max-workers=1
./gradlew detekt --max-workers=1
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
bash scripts/smoke-validate.sh stale-check
bash scripts/smoke-validate.sh optimization
git diff --check
```

Expected: all commands exit 0. Testcontainers failure/skip, missing artifact, warning, or unresolved compiler diagnostic is a verification gap, not PASS.

- [ ] **Step 3: 승인된 plan review artifact와 구현 diff를 대조한다.** 계획 review의 P0/P1=0 상태를 보존하고, 구현 중 계획을 변경했다면 영향받은 관점만 재실행한다. 구현 diff가 plan review의 traceability와 충돌하면 구현을 멈추고 계획 review gate로 돌아간다.

- [ ] **Step 4: implementation lesson과 review artifact를 Korean writer audit로 검사한다.**

```bash
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs docs/review/2026-08-20-issue-525-field-service-plan-review.md docs/lessons/2026-08-20-issue-525-field-service.md
```

Expected: findings=0.

- [ ] **Step 5: Lore commit을 만든다.** 구현·테스트·문서·workflow 변경만 stage하고, commit message는 의도 line과 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailers를 포함한다. PR 생성은 사용자의 별도 target/base/head 승인 이후로 남긴다.

---

## 의존성 순서와 중단/재실행 지점

## 설계 수용 기준 → 계획 추적성

| 설계 수용 기준 | 계획 Task | 주 검증 증거 |
|---|---|---|
| skill/availability/time-window hard constraint와 deterministic tie-break | Task 3 | `DeterministicFieldServicePlannerTest` |
| started/manual pin과 current visit/worker version 재확인 | Task 3, Task 6 | planner pin 테스트, `FieldServiceCasIntegrationTest` |
| duplicate event와 payload digest conflict | Task 2, Task 4, Task 5 | canonicalizer/model/repository/command 테스트 |
| sick call 중 approval conflict와 전체 rollback | Task 6 | PostgreSQL set-CAS 동시성 테스트 |
| stale callback, provider revision, request generation | Task 7 | callback envelope contract 테스트 |
| bounded outbox restart/replay와 lifecycle cancellation | Task 5, Task 9 | outbox/lifecycle/restart 테스트와 benchmark artifact |
| 명령 → replan → query → approval → dispatch HTTP 흐름 | Task 5, Task 6, Task 8 | MVC controller contract 테스트 |
| synthetic-only redaction, score/reason parser, CSP/DOM XSS | Task 7, Task 8 | callback/browser contract 테스트 |
| module/docs/CI/nightly/smoke/stale 등록 | Task 1, Task 10, Task 11 | `projects`, README validators, workflows, smoke/stale 검사 |

각 행의 주 검증 명령은 해당 Task 안에 있고, Task 11은 같은 목록을 실제 변경
diff와 대조한다.

1. Task 1 → Task 2 → Task 3은 module/compiler/domain 순서로 진행한다.
2. Task 4는 Task 2의 IDs/events를 사용하고, Task 5는 Task 3/4의 domain/repository를 사용한다.
3. Task 6은 Task 5가 event/replan과 schema를 통과한 뒤에만 시작한다.
4. Task 7은 Task 3의 score/reason과 Task 5의 plan state를 사용한다.
5. Task 8은 Task 5–7의 service contract가 고정된 뒤 controller/UI를 붙인다.
6. Task 9–11은 구현이 끝난 뒤 순차 실행한다. PostgreSQL/Testcontainers는 다른 container lane과 병렬 실행하지 않는다.
7. P0/P1 test failure는 해당 Task의 RED → 최소 수정 → targeted test부터 재실행한다. schema 변경이 필요하면 disposable DB를 재생성하고 migration을 추가하지 않는다.
8. provider/fixture compatibility가 깨지면 `planning-contracts-commit-80c1f95`와 local fixture pin을 먼저 비교하고, 실제 provider entitlement 없이 HTTP integration을 PASS로 바꾸지 않는다.

## Rollback

- 구현 중단 시 feature branch의 새 module 디렉터리와 workflow/docs 등록을 함께 revert할 수 있도록 각 Task를 독립 commit 단위로 유지한다.
- Exposed schema는 disposable workshop DB만 대상이며 production migration은 없으므로 schema rollback은 DB 재생성으로 수행한다.
- callback/dispatch contract 변경은 local fixture version을 올리고 incompatible fixture를 거부한다. #524 내부 구현이나 public SPI를 소급 수정하지 않는다.
- PR 생성 전까지 외부 GitHub mutation은 하지 않는다. PR/merge/cleanup은 별도 approval gate다.

## Plan self-review

- Spec coverage: hard constraints, pin, event digest, stale callback, worker-route CAS, restart/replay, browser UI, redaction, bounds, benchmark, README/CI/stale registration이 Task 2–10에 매핑됐다.
- Placeholder scan: 모든 task에 실제 경로, 테스트명, 명령, 기대 결과가 있으며 미정 표기나 무기한 지시를 사용하지 않았다.
- Type consistency: `planRevision`은 local stream, `providerRevision`은 `(provider, providerRequestId)` scope, `workerScheduleRevision`은 route CAS scope로 일관되게 사용한다.
- Known gaps: 실제 Timefold tenant/API, production auth/CSRF, production migration, route quality는 스펙 비목표이며 구현에서 추가하지 않는다.
- Conditional hazards: 새 모듈은 Spring Boot executable app이지 auto-configuration starter가 아니므로 conditional auto-configuration/registration ordering은 N/A로 기록한다. Kover/coverage aggregate도 현재 `planning-contracts`와 동일하게 별도 publication 대상이 아니며, workflow 범위 audit 결과를 Task 10에 남긴다.
