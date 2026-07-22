# Issue #552 — SaaS Usage Metering & Billing Ledger 구현 계획

> **실행 담당 agent:** REQUIRED SUB-SKILL: `superpowers:executing-plans`로 이 계획을 task 단위로 실행한다. 사용자의 명시적 요청에 따라 현재 feature worktree에서 inline으로만 구현하며 subagent 구현·리뷰를 사용하지 않는다. 각 단계는 checkbox(`- [ ]`)로 추적한다.

**목표:** Java 25 Spring Boot 예제로 중복 usage ingest, 시간 기반 가격 선택, 재시작 가능한 period close, 불변 ledger/invoice, late adjustment, reconciliation을 PostgreSQL 권위 아래 증명한다.

**아키텍처:** modular monolith 안에서 ingest, pricing, billing close, invoice, reconciliation 경계를 분리한다. mutable workflow state는 정규화된 Exposed table에 두고 금전 결과는 append-only ledger와 immutable invoice로 남긴다. correctness는 PostgreSQL unique constraint, row lock, conditional update/CAS, 짧은 idempotency receipt transaction으로 보장하며 Redis·leader·broker는 baseline 의존성에서 제외한다.

**기술 스택:** Kotlin 2.4, Java 25 toolchain, Spring Boot 4 MVC/Security/Actuator, virtual threads, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL, Testcontainers, Micrometer, JUnit 5, `bluetape4k-junit5`, `bluetape4k-assertions`.

**설계 기준:** `docs/superpowers/specs/2026-07-22-issue-552-usage-metering-billing-ledger-design.md`

**중단 조건:** Task 15의 검증과 inline 관점별 리뷰까지 끝낸 뒤 PR을 생성하고 CI/리뷰 결과를 보고한다. merge는 별도의 fresh approval 없이는 수행하지 않는다.

---

## 1. 고정 불변식

- 모든 concrete repository는 `MeteringExposedJdbcRepository` 또는 `AppendOnlyMeteringExposedJdbcRepository`를 통해 Bluetape `ExposedJdbcRepository`를 구현한다.
- production과 test fixture 모두 Exposed DAO/DSL만 사용한다. `JdbcTemplate`, `Connection`, `PreparedStatement`, `Statement`, `Transaction.exec`, migration SQL을 만들지 않는다.
- PostgreSQL이 idempotency, source event uniqueness, price schedule, period state, close checkpoint, ledger/invoice uniqueness의 유일한 correctness authority다. H2 test를 추가하지 않는다.
- ledger와 issued invoice는 append-only다. generic `save`, `saveAll`, `delete*` 호출은 명시적으로 거부하고 전용 append/query port만 노출한다.
- `receivedAt`은 server `Clock`으로 생성하고 close cutoff에 사용한다. caller의 `occurredAt`은 retention/future-skew 검증과 price selection에만 사용한다.
- idempotency receipt acquire/takeover는 짧은 `REQUIRES_NEW` transaction, domain mutation과 owner-token terminal CAS는 다음 transaction으로 분리한다.
- period는 `OPEN -> CLOSING -> FINALIZED`, close run은 `RUNNING -> FAILED_VALIDATION | READY_TO_FINALIZE -> FINALIZED` 단방향이다.
- close batch는 `(occurredAt, usageEventId)` keyset과 checkpoint CAS를 사용한다. scheduler와 operator `process-next`는 같은 application use case를 호출한다.
- late usage는 finalized period를 바꾸지 않고 server posting time을 포함하는 유일한 OPEN period에 양수 `DEBIT_ADJUSTMENT`로 기록한다. credit/debit 방향은 type으로 표현한다.
- reconciliation은 read-only다. repair는 stale digest 검증을 거친 별도 idempotent command다.

## 2. 파일 지도

### 모듈과 설정

- `commerce/usage-metering-billing-ledger/build.gradle.kts`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/UsageMeteringBillingApplication.kt`
- `commerce/usage-metering-billing-ledger/src/main/resources/application.yml`
- `commerce/usage-metering-billing-ledger/src/test/resources/junit-platform.properties`
- `commerce/usage-metering-billing-ledger/src/test/resources/logback-test.xml`

### domain/application/persistence

- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/MeteringTypes.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/PricingModels.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/BillingModels.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringTables.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringEntities.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringExposedJdbcRepository.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringRepositories.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringJdbcExecutor.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandFingerprint.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandReceiptService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/MeterService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/PriceActivationService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/UsageIngestionService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingPeriodService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingCloseService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/InvoiceService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/AdjustmentService.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/ReconciliationService.kt`

### runtime/web/docs

- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringProperties.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringConfiguration.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringMetrics.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringHealthIndicators.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/worker/BillingCloseScheduler.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/worker/CommandReceiptCleanupWorker.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/fixture/MeteringDemoFixture.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringSecurityConfiguration.kt`
- `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringExceptionHandler.kt`
- `commerce/usage-metering-billing-ledger/README.md`
- `commerce/usage-metering-billing-ledger/README.ko.md`
- `scripts/generate-usage-metering-billing-diagrams.mjs`
- `scripts/validate-usage-metering-billing-readme.mjs`
- `docs/images/readme-diagrams/usage-metering-billing-{architecture,state,ingestion-sequence,close-reconciliation}-01.{svg,png}`
- `docs/lessons/2026-07-22-issue-552-usage-metering-billing-ledger.md`

---

## 3. 순차 구현 작업

### Task 1: Java 25 Spring Boot module과 검증 task를 등록한다

**의존:** 승인된 spec/plan

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/build.gradle.kts`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/UsageMeteringBillingApplication.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/resources/application.yml`
- 생성: `commerce/usage-metering-billing-ledger/src/test/resources/junit-platform.properties`
- 생성: `commerce/usage-metering-billing-ledger/src/test/resources/logback-test.xml`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/MeteringRuntimeContractTest.kt`

- [ ] **RED:** `Runtime.version().feature() == 25`, preview 미사용, Boot main class, default test의 `integration`/`stress` tag 제외를 검사한다.
- [ ] `./gradlew :commerce-usage-metering-billing-ledger:test --tests '*MeteringRuntimeContractTest'`가 project 미등록으로 실패함을 확인한다.
- [ ] `commerce` 자동 등록 규칙을 이용해 directory/build를 만들고 Java/Kotlin toolchain 25, Spring Boot main class, virtual-thread JDK25 runtime을 설정한다.
- [ ] BOM은 root의 `bluetape4k-dependencies`만 사용한다. 새 version pin이나 개별 Bluetape BOM을 추가하지 않는다.
- [ ] 의존성은 `bluetape4k-core`, logging, idgenerators, micrometer, virtualthread API/JDK25, Exposed core/DAO/JDBC/Spring Boot JDBC/JDBC tests, Spring Boot MVC/JDBC/validation/security/actuator, PostgreSQL, Testcontainers, JUnit5/assertions로 제한한다.
- [ ] `test`, `integrationTest`, `stressTest` 모두 test mutex, zero-test guard, non-empty JUnit XML guard를 갖게 한다. `test`는 container-free unit/architecture test만 실행한다.
- [ ] `./gradlew projects | rg 'commerce-usage-metering-billing-ledger'`와 runtime contract를 통과시킨다.
- [ ] Lore commit: `Run the metering example on the Java 25 workshop baseline`.

### Task 2: 타입과 구성 기본값으로 입력 경계를 잠근다

**의존:** Task 1

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/MeteringTypes.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/PricingModels.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/domain/BillingModels.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringProperties.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/domain/MeteringTypesTest.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringPropertiesTest.kt`

- [ ] **RED:** blank/oversized `TenantId`, `MeterCode`, `SourceSystem`, `SourceEventId`, non-positive quantity/unit price, currency mismatch, scale/rounding, invalid duration/batch 범위를 검사한다.
- [ ] 다음 contract를 최소 구현한다.

```kotlin
@JvmInline
value class TenantId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 64) }
}

data class Money(val amount: BigDecimal, val currency: Currency) {
    fun normalized(): Money = copy(
        amount = amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_UP),
    )
}
```

- [ ] `CommandReceiptStatus`, `BillingPeriodState`, `CloseRunState`, `LedgerEntryType`, `ReconciliationFindingType`를 exhaustive enum/sealed contract로 정의한다.
- [ ] properties default를 receipt lease 30s/retention 24h/response 16KiB, lateness 48h, occurred retention 400d/future skew 5m, close batch 200/max 1000, batches per tick 5, scheduler delay 5s, reconciliation page 200/max 500으로 고정한다.
- [ ] mutable collection 노출, `!!`, platform type 확산이 없음을 architecture scan으로 검사한다.
- [ ] targeted tests와 `detektTest`를 통과시킨다.
- [ ] Lore commit: `Make metering time and money boundaries explicit`.

### Task 3: Exposed schema와 mandatory repository contract를 만든다

**의존:** Task 2

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringTables.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringEntities.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringExposedJdbcRepository.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringRepositories.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringJdbcExecutor.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringDatabaseFixture.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/persistence/MeteringRepositoryContractTest.kt`

- [ ] **RED:** 모든 concrete repository가 `ExposedJdbcRepository` assignable인지, append-only repository의 inherited mutation이 거부되는지, fixture가 Exposed로 authority를 seed하는지 검사한다.
- [ ] spec 9장의 receipt/meter/schedule/price/usage/calendar/period/close-run/ledger/invoice/line/provenance/reconciliation table과 unique/index/check constraint를 Exposed table로 정의한다.
- [ ] repository base는 실제 Bluetape delegate signature를 그대로 사용한다.

```kotlin
abstract class MeteringExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
```

- [ ] append-only base는 전용 `append`에서만 entity를 생성하고 generic mutation을 모두 `UnsupportedOperationException`으로 차단한다.

```kotlin
abstract class AppendOnlyMeteringExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : MeteringExposedJdbcRepository<E, ID>(domainClass) {
    final override fun <S : E> save(entity: S): S = immutableMutation()
    final override fun <S : E> saveAll(entities: Iterable<S>): List<S> = immutableMutation()
    final override fun deleteById(id: ID): Unit = immutableMutation()
    final override fun delete(entity: E): Unit = immutableMutation()
    final override fun deleteAllById(ids: Iterable<ID>): Unit = immutableMutation()
    final override fun deleteAll(entities: Iterable<E>): Unit = immutableMutation()
    final override fun deleteAll(): Unit = immutableMutation()

    private fun <T> immutableMutation(): T =
        throw UnsupportedOperationException("append-only repository")
}
```

- [ ] `MeteringDatabaseFixture.resetAndSeed()`는 `SchemaUtils.drop/createMissingTablesAndColumns`와 repository/service API만 사용한다. default/prod initializer는 만들지 않는다.
- [ ] PostgreSQL Testcontainers에서 unique/index/foreign-key와 repository query를 검증한다.
- [ ] 아래 금지 scan을 통과시킨다.

```bash
if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("|java\.sql\.|src/.*/db/migration' commerce/usage-metering-billing-ledger; then exit 1; fi
```

- [ ] Lore commit: `Keep billing authority inside mandatory Exposed repositories`.

### Task 4: command receipt의 acquire/replay/takeover/cleanup을 구현한다

**의존:** Task 3

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandFingerprint.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandReceiptService.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/worker/CommandReceiptCleanupWorker.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandFingerprintTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/idempotency/CommandReceiptPostgresIntegrationTest.kt`

- [ ] **RED:** same key/same fingerprint replay, same key/different fingerprint conflict, active lease contention, expired lease owner-token takeover, stale owner terminal CAS 거부, 16KiB 초과 response 거부, terminal-only cleanup을 검사한다.
- [ ] canonical JSON field order와 SHA-256 fingerprint를 구현하되 raw idempotency key와 request body는 저장하지 않는다.
- [ ] `@Transactional(propagation = REQUIRES_NEW)` acquire/takeover가 `Acquired`, `Replay`, `InProgress`, `Conflict`를 반환하도록 한다.
- [ ] domain transaction 성공/실패 후 `(receiptId, ownerToken, IN_PROGRESS)` 조건으로 `SUCCEEDED`/`FAILED` terminal response를 CAS 한다. retryable infrastructure failure는 lease expiry/takeover를 허용한다.
- [ ] cleanup worker는 `(terminalAt, receiptId)` keyset, retention predicate, bounded batch를 사용하고 live/재획득 가능 receipt를 지우지 않는다.
- [ ] multi-instance cleanup과 takeover race를 PostgreSQL concurrency test로 증명한다.
- [ ] Lore commit: `Make command retries recoverable without hiding committed work`.

### Task 5: meter와 시간 기반 price activation을 구현한다

**의존:** Tasks 2-4

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/MeterService.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/PriceActivationService.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/PriceActivationServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/PriceActivationPostgresIntegrationTest.kt`

- [ ] **RED:** meter/currency 등록, inclusive-from/exclusive-to selection, concurrent first activation, overlap rejection, normal backdate rejection, one-time previous open interval close, ledger가 참조한 historical range repair rejection을 검사한다.
- [ ] `(tenantId, meterCode, currency)` schedule authority row를 `insertIgnore` 후 `FOR UPDATE`하고 price interval을 직렬화한다.
- [ ] `unitPrice/effectiveFrom`은 immutable, 기존 open-ended row의 `effectiveTo`는 새 activation 시 한 번만 닫을 수 있게 한다.
- [ ] historical gap repair는 overlap이 없고 대상 interval에 ledger entry가 없을 때만 별도 operator command로 허용한다.
- [ ] meter 등록과 price activation endpoint 모두 `Idempotency-Key`를 요구하고 replay 시 원 status/body를 반환한다.
- [ ] concurrent activation 20회에서 단 하나의 valid timeline만 남음을 PostgreSQL로 증명한다.
- [ ] Lore commit: `Serialize price timelines where billing truth is chosen`.

### Task 6: tenant-safe usage ingestion과 HTTP contract를 구현한다

**의존:** Tasks 4-5

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/UsageIngestionService.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/UsageIngestionServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/web/UsageIngestionWebIntegrationTest.kt`

- [ ] **RED:** required `Idempotency-Key`, tenant mismatch, source key duplicate, request replay header, fingerprint conflict, occurredAt retention/future-skew, caller receivedAt 무시/거부를 검사한다.
- [ ] `POST /api/v1/tenants/{tenantId}/usage-events`가 tenant-scoped principal과 request tenant를 일치시키고 server `Clock.instant()`로 `receivedAt`을 생성한다.
- [ ] `(tenantId, sourceSystem, sourceEventId)` unique가 idempotency key와 독립적으로 producer retry를 차단하게 한다.
- [ ] terminal replay는 저장된 status/body와 `Idempotency-Replayed: true`를 반환한다. raw secret/key/body가 log/DB/metric에 나타나지 않는 test를 추가한다.
- [ ] JDK connector를 사용한 `WebTestClient` test로 validation/error body/headers를 고정한다.
- [ ] Lore commit: `Accept usage once across client and producer retries`.

### Task 7: billing calendar와 close 시작 CAS를 구현한다

**의존:** Tasks 3-6

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingPeriodService.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingPeriodServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingPeriodPostgresIntegrationTest.kt`

- [ ] **RED:** half-open period overlap, concurrent period creation, OPEN-only close start, fixed cutoff, allowed-lateness 미충족, duplicate close command replay를 검사한다.
- [ ] tenant/currency calendar authority row를 lock하고 겹치지 않는 `[startsAt, endsAt)` period만 만든다.
- [ ] period 생성 endpoint와 period/close status query를 tenant predicate를 포함해 구현한다.
- [ ] close start는 period `OPEN -> CLOSING` CAS와 `RUNNING` close run/checkpoint 생성, cutoff 확정을 한 transaction에서 수행한다.
- [ ] HTTP는 `202 Accepted`, stable run URI와 `Location` header를 반환한다.
- [ ] 동시에 20개 close start 요청을 보내도 하나의 run만 생성되는 PostgreSQL test를 통과시킨다.
- [ ] Lore commit: `Freeze one billing boundary before aggregation begins`.

### Task 8: 재시작 가능한 close batch와 append-only charge ledger를 구현한다

**의존:** Task 7

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingCloseService.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/worker/BillingCloseScheduler.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingCloseServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingCloseRestartIntegrationTest.kt`
- stress 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/BillingCloseStressTest.kt`

- [ ] **RED:** `(occurredAt, usageEventId)` ordering, batch boundary, no-price validation failure, duplicate worker claim, ledger unique replay, checkpoint-before/after failpoint restart를 검사한다.
- [ ] batch query는 fixed cutoff 이전, period occurred-time range 안, checkpoint 이후 usage만 최대 batch size로 읽는다.
- [ ] price를 usage `occurredAt`으로 선택하고 `quantity * unitPrice`를 currency scale/HALF_UP으로 charge한다.
- [ ] ledger append와 checkpoint CAS를 같은 transaction에 두고 `(usageEventId, CHARGE)` uniqueness로 retry를 무해하게 한다.
- [ ] 더 읽을 row가 없고 validation finding도 없을 때만 `READY_TO_FINALIZE` CAS 한다. pricing gap은 `FAILED_VALIDATION`과 stable finding을 남긴다.
- [ ] scheduler는 delay 5s, tick당 최대 5 batch를 처리한다. 여러 instance correctness는 DB CAS로 보장하고 leader 의존성을 추가하지 않는다.
- [ ] operator `POST .../close-runs/{runId}/process-next`도 정확히 같은 `processNextBatch` use case를 호출한다.
- [ ] `cleanIntegrationTest --no-build-cache`로 failpoint restart를 재검증하고 stress profile에서 10k usage, concurrent workers, bounded memory/query count를 측정한다.
- [ ] Lore commit: `Resume billing batches without duplicating financial entries`.

### Task 9: immutable invoice와 ledger provenance를 확정한다

**의존:** Task 8

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/InvoiceService.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/InvoiceServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/InvoiceFinalizationPostgresIntegrationTest.kt`

- [ ] **RED:** READY-only finalize, duplicate finalize replay, line grouping, line/total equality, provenance completeness, issued invoice generic mutation rejection, partial failure rollback을 검사한다.
- [ ] `(tenant, period, currency)` invoice uniqueness를 두고 ledger snapshot으로 invoice/lines/line-entry joins를 append한다.
- [ ] 모든 eligible ledger entry가 정확히 한 invoice line에 연결되고 `sum(line.amount) == invoice.total == sum(linked ledger.amount)`인지 transaction 안에서 검증한다.
- [ ] invoice 발행, close run `FINALIZED`, billing period `FINALIZED`를 같은 transaction으로 commit한다.
- [ ] invoice query는 tenant predicate 아래 header, lines, applied price version, ledger provenance를 한 contract로 반환한다.
- [ ] 발행 후 amount/currency/line/provenance 수정·삭제 API가 존재하지 않으며 inherited mutation test가 실패시킴을 증명한다.
- [ ] Lore commit: `Issue invoices only from a complete immutable ledger snapshot`.

### Task 10: late debit, credit adjustment, reconciliation과 stale-safe repair를 구현한다

**의존:** Task 9

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/AdjustmentService.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/application/ReconciliationService.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringApiModels.kt`
- 수정: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringControllers.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/AdjustmentServiceTest.kt`
- 통합 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/application/ReconciliationPostgresIntegrationTest.kt`

- [ ] **RED:** cutoff 이후 도착한 finalized-period usage의 debit, open posting period 부재/중복, positive credit/debit amount, original reference, duplicate adjustment, 여섯 finding type, read-only reconcile, stale digest repair 거부를 검사한다.
- [ ] late usage의 original occurred-time price를 사용하되 ledger posting period는 server posting time을 포함하는 유일한 OPEN period로 선택한다.
- [ ] `DEBIT_ADJUSTMENT`와 `CREDIT_ADJUSTMENT` amount는 항상 양수이고 direction/type과 reason/original entry/finding reference를 저장한다.
- [ ] reconciliation은 keyset page 200/max 500으로 authority를 읽고 immutable finding snapshot/digest만 기록한다.
- [ ] repair command는 idempotency receipt를 사용하고 current rows의 digest가 finding digest와 같을 때만 append한다. 자동 mutation은 하지 않는다.
- [ ] `UNLEDGERED_USAGE`, `UNLEDGERED_USAGE_AFTER_CUTOFF`, `LEDGER_PRICE_MISMATCH`, `INVOICE_LINE_MISMATCH`, `INVOICE_TOTAL_MISMATCH`, `TENANT_OR_CURRENCY_MISMATCH`를 각각 fixture로 증명한다.
- [ ] Lore commit: `Correct billing history with linked entries instead of rewrites`.

### Task 11: security, error, observability, health 경계를 완성한다

**의존:** Tasks 6-10

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringConfiguration.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringMetrics.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringHealthIndicators.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringSecurityConfiguration.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/main/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringExceptionHandler.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/web/MeteringSecurityIntegrationTest.kt`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/config/MeteringObservabilityTest.kt`

- [ ] **RED:** ingest/operator role 분리, cross-tenant denial, constant-time digest comparison, actuator 최소 노출, stable HTTP codes, secret/log redaction, metric tag cardinality를 검사한다.
- [ ] demo credential은 raw token을 저장하지 않고 digest만 비교한다. production 교체점은 authentication port로 격리한다.
- [ ] 오류를 validation 400, auth 401/403, not found 404, idempotency/in-progress/state/stale-finding conflict 409, pricing/close-not-ready 422로 안정화한다. lock timeout/deadlock/DB unavailable은 stable retriable error와 `Retry-After`를 포함해 503으로 반환한다.
- [ ] spec의 metric 이름을 등록하되 tag는 `result`, `operation`, `type` 같은 bounded enum만 허용하고 tenant/meter/source/idempotency key를 tag로 쓰지 않는다.
- [ ] health는 DB availability, oldest CLOSING run age, unresolved unpriced count, last successful reconciliation age, stale command receipt count를 bounded query로 제공하고 detail은 operator에게만 노출한다.
- [ ] Lore commit: `Expose billing operations without leaking tenant or secret cardinality`.

### Task 12: PostgreSQL/HTTP/concurrency/restart acceptance suite를 고정한다

**의존:** Tasks 4-11

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/support/AbstractMeteringPostgresIntegrationTest.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/MeteringEndToEndIntegrationTest.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/MeteringContextRestartIntegrationTest.kt`
- 생성: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/KotlinPatternArchitectureTest.kt`

- [ ] `PostgreSQLServer.Launcher`와 test mutex를 재사용하고 container test를 별도 Gradle process와 병렬 실행하지 않는다.
- [ ] end-to-end는 meter/price → duplicate ingest → close start/process/restart/finalize → late debit → credit → reconcile/repair를 HTTP로 실행한다.
- [ ] 20-way idempotency, activation, period start, close worker concurrency에서 unique/CAS 결과와 replay body가 결정적인지 검사한다.
- [ ] context restart 후 receipt takeover, close checkpoint resume, immutable invoice query가 유지되는지 검사한다.
- [ ] architecture test로 모든 concrete repository assignability, append-only mutation guard, raw SQL/JDBC 금지, production `SchemaUtils` 금지, `!!` 금지, controller-to-repository 직접 접근 금지를 검사한다.
- [ ] 다음 순서로 fresh evidence를 남긴다.

```bash
./gradlew :commerce-usage-metering-billing-ledger:test
./gradlew :commerce-usage-metering-billing-ledger:integrationTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:cleanIntegrationTest --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:stressTest --rerun-tasks --max-workers=1
```

- [ ] Lore commit: `Prove billing recovery against the PostgreSQL authority`.

### Task 13: bilingual runbook과 네 개 diagram을 만든다

**의존:** Tasks 1-12의 실제 contract

**파일:**
- 생성: `commerce/usage-metering-billing-ledger/README.md`
- 생성: `commerce/usage-metering-billing-ledger/README.ko.md`
- 생성: `scripts/generate-usage-metering-billing-diagrams.mjs`
- 생성: `scripts/validate-usage-metering-billing-readme.mjs`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-architecture-01.svg`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-architecture-01.png`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-state-01.svg`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-state-01.png`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-ingestion-sequence-01.svg`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-ingestion-sequence-01.png`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-close-reconciliation-01.svg`
- 생성: `docs/images/readme-diagrams/usage-metering-billing-close-reconciliation-01.png`
- 테스트: `commerce/usage-metering-billing-ledger/src/test/kotlin/io/bluetape4k/workshop/commerce/metering/MeteringReadmeContractTest.kt`

- [ ] README 양쪽에 문제/경계/quick start/API/데이터 모델/상태/실패·복구/운영/보안/test/production 차이/microservice extraction을 동일 구조로 작성한다.
- [ ] microservice guide는 ingest 분리 → pricing ownership 분리 → period/close/ledger/invoice consistency boundary 유지 → outbox/broker 추가 → reconciliation 독립 운영 순서와 API/event ownership을 설명한다.
- [ ] `bluetape-diagram` 계약에 따라 한 번에 한 asset을 생성한다. Graphviz를 쓰지 않고 canonical SVG를 XML parse한 뒤 CairoSVG scale 2로 PNG를 만든다.
- [ ] architecture에는 API/application/domain/Exposed/PostgreSQL 경계, state에는 period/receipt/close-run 세 상태기계, ingestion sequence에는 short receipt transaction과 domain/terminal CAS, close-reconciliation에는 batch checkpoint/restart/finalize/late/repair 흐름을 담는다.
- [ ] 각 asset마다 type-specific audit, font normalization, edge clipping/overlap 검사 후 `view_image` original detail로 full-size PNG를 직접 확인한다.
- [ ] 다음 검증을 통과시킨다.

```bash
node scripts/generate-usage-metering-billing-diagrams.mjs
node scripts/validate-usage-metering-billing-readme.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-diagram-qa.mjs
./gradlew :commerce-usage-metering-billing-ledger:test --tests '*MeteringReadmeContractTest'
```

- [ ] Lore commit: `Explain the billing ledger as an executable production runbook`.

### Task 14: repository module map, workflow, nightly, stale checks를 등록한다

**의존:** Tasks 12-13

**파일:**
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `.github/workflows/Examples.yml`
- 수정: `.github/workflows/nightly.yml`
- 수정: `scripts/smoke-validate.sh`
- 필요 시 수정: `AGENTS.md`

- [ ] root module map 양쪽에 Advanced 예제, 실제 Bluetape modules, PostgreSQL Testcontainers, one-line 목적과 test commands를 동일하게 추가한다.
- [ ] `Examples.yml` path filter에 module/docs images/generator/validator를 넣고 container job에 `test`, `integrationTest`, `koverXmlReport`, report existence와 artifact paths를 추가한다.
- [ ] `nightly.yml` full job에 integration/coverage evidence를 추가한다. container-free `all-smoke`에는 unit-only `test`만 추가하고 PostgreSQL task는 넣지 않는다.
- [ ] `scripts/smoke-validate.sh commerce`와 help/comment를 갱신하고 `stale-check`가 새 module image/link drift를 잡게 한다.
- [ ] 다음 검증을 통과시킨다.

```bash
./gradlew projects | rg 'commerce-usage-metering-billing-ledger'
./scripts/smoke-validate.sh stale-check
node scripts/validate-usage-metering-billing-readme.mjs
node scripts/validate-readme-diagram-qa.mjs
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
```

- [ ] Lore commit: `Keep the metering example visible in every validation lane`.

### Task 15: 전체 검증, 관점별 inline review, lesson, PR gate를 완료한다

**의존:** Tasks 1-14

**파일:**
- 생성: `docs/lessons/2026-07-22-issue-552-usage-metering-billing-ledger.md`
- 수정: 발견된 P0/P1 문제의 해당 production/test/docs/workflow 파일

- [ ] lesson을 한국어로 작성한다: 문제, 선택한 architecture, Exposed-only repository, idempotency split transaction, close checkpoint, append-only invoice/adjustment, 실패와 수정, 검증 evidence, production 적용 경계.
- [ ] spec 24장 DoD와 아래 추적표를 실제 test/file에 대조하고 빈 acceptance criterion이 없게 한다.
- [ ] placeholder scan을 수행한다: `TODO`, `FIXME`, `TBD`, `placeholder`, 빈 handler, 무조건 성공 fake, skip/disabled test를 분류하고 의도하지 않은 항목은 제거한다.
- [ ] dependency/type/signature scan을 수행한다: BOM-only, Java 25, `ExposedJdbcRepository`, append-only override, no raw SQL/JDBC, no H2, no Redis/leader/broker, controller/application/repository 방향.
- [ ] 다음 fresh verification을 순서대로 실행한다.

```bash
./gradlew :commerce-usage-metering-billing-ledger:test
./gradlew :commerce-usage-metering-billing-ledger:integrationTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:cleanIntegrationTest --no-build-cache --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:stressTest --rerun-tasks --max-workers=1
./gradlew :commerce-usage-metering-billing-ledger:detekt :commerce-usage-metering-billing-ledger:detektTest
./gradlew :commerce-usage-metering-billing-ledger:koverXmlReport
./gradlew projects
./scripts/smoke-validate.sh stale-check
node scripts/validate-usage-metering-billing-readme.mjs
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-diagram-qa.mjs
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
git diff --check
```

- [ ] **성능 관점:** keyset/index plan, batch bounds, N+1, lock duration, response/metric cardinality, stress result를 review한다.
- [ ] **안정성 관점:** crash points, transaction split, stale owner CAS, restart, scheduler concurrency, cleanup race, failpoint coverage를 review한다.
- [ ] **보안 관점:** tenant isolation, roles, digest comparison, secret/body/log/metric leakage, actuator exposure, error disclosure를 review한다.
- [ ] **운영 관점:** health/metrics, stuck close detection, process-next, failed validation, retention, production DDL boundary, runbook을 review한다.
- [ ] **개발자/API 관점:** status/header/error stability, validation, naming, immutable types, Exposed patterns, test determinism, README command 실행성을 review한다.
- [ ] **사용자/caller 관점:** duplicate retry, conflict recovery, late usage visibility, invoice provenance, repair safety, microservice migration 이해도를 review한다.
- [ ] 여섯 관점을 통합해 P0/P1을 모두 수정하고 해당 regression test와 전체 verification을 다시 실행한다. P2는 lesson/PR에 근거와 trade-off를 남긴다.
- [ ] 최종 Lore commit: `Document the operational proof behind the billing ledger example`.
- [ ] branch를 `origin/develop`에 rebase하고 검증을 재실행한 뒤 exact head로 push/PR 생성한다. PR body는 영어로 issue #552, issue #553 후속 범위, tests, diagrams, risks를 기록한다.
- [ ] CI, review, unresolved thread, exact local/remote head를 확인해 merge-ready 상태만 보고한다. merge는 별도의 사용자 승인까지 대기한다.

---

## 4. Spec 수용 기준 추적표

| Spec 기준 | 구현 task | 권위 검증 |
|---|---:|---|
| 동일 source event/idempotency retry가 usage를 한 번만 저장 | 4, 6, 12 | `CommandReceiptPostgresIntegrationTest`, `UsageIngestionWebIntegrationTest` |
| occurredAt price 선택과 concurrent timeline 무결성 | 2, 5, 12 | `PriceActivationPostgresIntegrationTest` |
| crash/restart/multi-instance close가 ledger를 중복하지 않음 | 7, 8, 12 | `BillingCloseRestartIntegrationTest`, `BillingCloseStressTest` |
| finalized period/invoice 불변과 complete provenance | 3, 9, 12 | `InvoiceFinalizationPostgresIntegrationTest`, repository contract |
| late usage와 credit/debit이 linked adjustment로 기록 | 10, 12 | `AdjustmentServiceTest`, end-to-end test |
| reconciliation은 read-only, repair는 stale-safe/idempotent | 10, 12 | `ReconciliationPostgresIntegrationTest` |
| tenant/operator/security/low-cardinality observability | 6, 11, 12 | security/observability tests |
| ExposedJdbcRepository mandatory, raw SQL/JDBC 없음 | 3, 12, 15 | repository/architecture tests와 forbidden scan |
| PostgreSQL authority, H2/Redis/leader 불필요 | 3-12, 15 | Testcontainers suite와 dependency scan |
| bilingual README, state/sequence/architecture, extraction guide | 13, 14 | README contract/validator/diagram QA |
| workflow/nightly/stale/module map 등록 | 14, 15 | `actionlint`, stale-check, Gradle projects |

## 5. 계획 자체 검토

### 5.1 정적 자체 검토

- [ ] 모든 생성/수정 경로가 실제 repository root 기준이며 `settings.gradle.kts` 자동 등록 규칙과 일치한다.
- [ ] task dependency는 domain → persistence → receipt/pricing/ingest → period/close → invoice/adjustment/reconcile → API/ops → docs/CI 순서다.
- [ ] 각 production behavior task에 RED/GREEN test와 targeted command가 있다.
- [ ] 모든 concrete repository와 append-only mutation 차단이 Task 3/12/15에 반복 검증된다.
- [ ] design spec의 default, state, HTTP, error, transaction, failure, observability, test, documentation criterion이 추적표에 연결된다.
- [ ] full Event Sourcing은 구현 task에 없고 issue #553 후속으로만 남는다.
- [ ] 계획 본문에 미결정 placeholder, 임시 성공 조건, raw SQL 우회, subagent 위임이 없다.

### 5.2 Step 3-R 관점별 계획 리뷰 결과

| 심각도 | 관점 | 발견 사항 | 계획 반영 |
|---|---|---|---|
| P1 | 성능 | close/reconciliation이 offset paging이면 큰 tenant에서 지연과 누락 위험 | Task 4/8/10에 stable keyset, batch max, tick bound와 stress test 고정 |
| P1 | 안정성 | receipt terminal update를 domain transaction과 잘못 묶으면 commit 후 IN_PROGRESS 고착 가능 | Task 4에 short `REQUIRES_NEW` acquire와 다음 transaction owner-token terminal CAS 고정 |
| P1 | 보안 | tenant 식별자를 metric/log에 넣으면 정보 유출과 cardinality 폭증 | Task 6/11에 redaction과 bounded enum tag contract test 추가 |
| P1 | 운영 | scheduler만 있으면 재현·복구가 어려움 | Task 8/11/13에 동일 use case의 `process-next`, stuck health, runbook 추가 |
| P1 | 개발자/API | `ExposedJdbcRepository`의 generic delete가 append-only 원칙을 우회 | Task 3에 모든 inherited mutation override와 reflection/behavior contract test 추가 |
| P1 | 사용자/caller | close cutoff 이후 usage가 사라진 것으로 보일 수 있음 | Task 10/13에 linked late debit, finding, invoice provenance 설명과 E2E 추가 |
| P1 | 통합 | module test만 등록하면 container/coverage artifact가 CI에서 누락 | Task 14에 Examples/nightly/report existence/artifact/stale 등록을 하나의 chain으로 고정 |
| P2 | 운영 | production migration artifact가 예제에 없음 | raw SQL 금지 범위를 유지하고 test/demo SchemaUtils와 production migration 책임을 README/lesson에 명시 |
| P2 | 범위 | tiered pricing/tax/refund/payment/full ES가 기대될 수 있음 | baseline은 flat unit price와 billing ledger에 한정하고 full ES는 issue #553으로 추적 |

최신 계획 리뷰 결과는 **P0=0, P1=0**이다. 위 P1은 모두 task/test/검증 명령에 반영했으며 P2는 명시적인 범위·운영 trade-off로 수용한다.
