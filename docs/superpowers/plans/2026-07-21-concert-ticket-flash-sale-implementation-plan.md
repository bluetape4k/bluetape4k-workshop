# Concert Ticket Flash Sale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 25 Spring Boot MVC 애플리케이션에서 PostgreSQL-authoritative 일반석 티켓 판매,
durable waiting room, USER/IP 이중 guard, 멱등 구매, 결제 timeout reconciliation, 환불·ticket
revoke, bounded SSE와 운영자 복구를 구현한다.

**Architecture:** 하나의 Spring Boot JAR 안에 `salecontrol`, `admission`, `purchase`, `payment`,
`ticketing`, `operations` Spring Modulith module을 둔다. PostgreSQL transaction과 fencing이 모든
업무 권위를 가지며 Redis/Bucket4j/Lettuce leader는 짧은 admission·scheduler 최적화만 제공한다.
외부 효과는 stable operation ID, publication receipt, lookup-first reconciliation로 재시작과 중복
전달 뒤에도 수렴한다.

**Tech Stack:** Kotlin 2.4.0, Java 25 toolchain/JVM target/runtime, Spring Boot 4 MVC,
Spring Modulith, Exposed JDBC, PostgreSQL, Lettuce/Redis Lua, Bucket4j, Bluetape leader,
Micrometer, virtual threads, JUnit 5, WebTestClient, Testcontainers, Mermaid +
`bluetape-diagram`.

---

## 구현 기준

- 설계 권위는 `docs/superpowers/specs/2026-07-21-concert-ticket-flash-sale-design.md`다.
- module은 `commerce/concert-ticket-flash-sale`, Gradle project는
  `:commerce-concert-ticket-flash-sale`, package root는
  `io.bluetape4k.workshop.commerce.ticket`이다.
- `platform(libs.bluetape4k.dependencies)`만 Bluetape version authority로 사용한다.
- PostgreSQL 동시성 fixture만 권위로 사용하고 H2를 대체 증명으로 사용하지 않는다.
- live HTTP test는 `RANDOM_PORT`와 network-bound `WebTestClient`를 사용한다.
- container test는 `--max-workers=1`로 실행하고 stress는 별도 opt-in task로 분리한다.
- 신규 production/test source는 Kotlin이며 Java 25 toolchain, `JvmTarget.JVM_25`, Java 25 runtime을
  module 안에 격리한다. Spring Modulith package metadata용 `package-info.java`만 예외이며 preview
  feature는 사용하지 않는다.
- public DTO/problem/config는 closed schema, `Serializable`, explicit `serialVersionUID`, English
  KDoc을 사용한다.
- production operational class는 `KLogging`, assertion은 Bluetape assertions/Kluent를 우선한다.
- #1065가 release되기 전 multi-key lease는 `RedisScriptRunner` 위 application-owned adapter다.

## Ecosystem capability selection

| 책임 | Bluetape capability | 사용 방식 / 제약 |
|---|---|---|
| validation/ID/JSON/logging | `bluetape4k-core`, `idgenerators`, `jackson3`, `logging` | DTO validation, UUID, canonical JSON, redacted log |
| JDBC/Exposed | `bluetape4k-exposed-core/jdbc`, `exposed-spring-boot-jdbc` | Spring transaction 안에서만 접근 |
| audit/publication | `AuditableLongIdTable`, `UserContext`, `exposed-spring-modulith` | operator audit와 after-commit event |
| Redis | `bluetape4k-lettuce` `RedisScriptRunner` | token bucket과 좁은 Lua lease adapter |
| rate limit | `bluetape4k-bucket4j` + Lettuce | route admission만 담당 |
| scheduler | `bluetape4k-leader` core/micrometer/Lettuce | tick 중복 억제; DB claim이 correctness authority |
| virtual thread | `virtualthread-api`, runtime `virtualthread-jdk25` | bounded request/worker executor |
| metrics | `bluetape4k-micrometer` | low-cardinality metric |
| test | `bluetape4k-testcontainers`, `junit5`, `assertions` | PostgreSQL/Redis와 hostile concurrency |
| multi-key lease | #1065 미출시 | application adapter; upstream 구현 금지 |
| payment/ticket provider | published capability 없음 | deterministic fake + stable operation ledger |

## 파일 구조와 책임

### Production

- `commerce/concert-ticket-flash-sale/build.gradle.kts`: Java 25와 versionless dependency/task 계약
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketFlashSaleApplication.kt`: Spring Boot entrypoint
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketConfiguration.kt`: typed properties, validation, Clock, permits, executors
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketMigrationRunner.kt`: checksum/advisory-lock migration
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketPublicationConfiguration.kt`: Modulith publication adapter
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketRedisConfiguration.kt`: Lettuce/Bucket4j/leader lifecycle
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketHealthIndicators.kt`, `TicketMetrics.kt`, `TicketLifecycle.kt`: 운영 계약
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/salecontrol/api/SaleApi.kt`, `salecontrol/internal/SaleService.kt`: sale lifecycle/policy
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/admission/api/AdmissionApi.kt`, `admission/internal/AdmissionService.kt`: waiting room/grant consume
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/api/PurchaseApi.kt`, `purchase/internal/PurchaseService.kt`: hold/order/refund orchestration
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/payment/internal/FakePaymentProvider.kt`, `payment/internal/PaymentWorker.kt`: operation intent/fencing/reconcile
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/ticketing/internal/TicketEffectWorker.kt`: issue/revoke effect ledger
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/operations/api/OperationsApi.kt`, `operations/internal/OperationsService.kt`: audit/projection/recovery
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/domain/TicketModels.kt`, `domain/TicketTransitions.kt`: stable states and pure transitions
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/identity/IdentityService.kt`, `identity/TrustedClientAddressResolver.kt`: principal/IP alias boundary
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/idempotency/IdempotencyFingerprint.kt`, `idempotency/HttpIdempotencyRepository.kt`: owner-scoped replay
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/redis/MultiKeyLeaseAdapter.kt`: two-key Lua acquire/renew/release
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketTables.kt`, `TicketRecords.kt`, `TicketRepositories.kt`, `TicketJdbcExecutor.kt`
- `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/CustomerTicketController.kt`, `OperatorTicketController.kt`, `TicketEventStream.kt`, `ApiExceptionHandler.kt`
- `src/main/resources/db/migration/V001__concert_ticket_flash_sale.sql`
- `src/main/resources/application.yml`
- `src/main/resources/static/{index.html,app.js,styles.css}`

### Tests and repository integration

- `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/AbstractTicketIntegrationTest.kt`: PostgreSQL/Redis/live HTTP fixture
- domain, migration, repository, identity, idempotency, Redis, purchase, worker, HTTP, SSE, health,
  redaction, hostile concurrency와 stress test
- module `commerce/concert-ticket-flash-sale/README.md` and
  `commerce/concert-ticket-flash-sale/README.ko.md`, diagram sources/PNG, root/commerce README,
  `AGENTS.md`
- `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`, 신규 stale/runbook validator
- `docs/lessons/2026-07-21-issue-521-concert-ticket-flash-sale.md`
- `docs/review/2026-07-21-issue-521-concert-ticket-flash-sale-review.md`

### Task 1: Module, Java 25 runtime, typed configuration, migration gate

**Files:**
- Create: `commerce/concert-ticket-flash-sale/build.gradle.kts`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketFlashSaleApplication.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketConfiguration.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketMigrationRunner.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/resources/application.yml`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketRuntimeContractTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketStartupValidationTest.kt`

- [ ] **Step 1: module 미존재 실패를 고정한다**

Run: `./gradlew :commerce-concert-ticket-flash-sale:tasks`

Expected: FAIL because the auto-registered module directory does not exist.

- [ ] **Step 2: Java 25/versionless dependency skeleton을 작성한다**

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
}
springBoot {
    mainClass.set("io.bluetape4k.workshop.commerce.ticket.TicketFlashSaleApplicationKt")
}
configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }
configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}
tasks.test {
    useJUnitPlatform { excludeTags("stress") }
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
}
dependencies {
    testImplementation(project(":shared"))
    implementation(libs.bluetape4k.core)
    implementation(libs.bluetape4k.logging)
    implementation(libs.bluetape4k.jackson3)
    implementation(libs.bluetape4k.idgenerators)
    implementation(libs.bluetape4k.micrometer)
    implementation(libs.bluetape4k.virtualthread.api)
    runtimeOnly(libs.bluetape4k.virtualthread.jdk25)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.spring.boot.jdbc)
    implementation(libs.exposed.spring.modulith)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.leader.redis.lettuce)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    runtimeOnly(libs.postgresql.driver)
    testImplementation(libs.spring.boot.starter.webflux.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
}
```

- [ ] **Step 3: 잘못된 capacity/config가 startup에서 실패하는 테스트를 작성한다**

```kotlin
@Test
fun `database lane permits must leave two hikari connections reserved`() {
    shouldThrow<TicketStartupException> {
        TicketStartupValidator.validate(
            TicketProperties(db = TicketDatabaseProperties(maxPoolSize = 20, foregroundPermits = 16,
                workerPermits = 3, ssePermits = 2, operatorPermits = 1)),
        )
    }.code shouldBeEqualTo TicketStartupFailure.INVALID_DATABASE_CAPACITY
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketStartupValidationTest'`

Expected: FAIL because configuration types and validator do not exist.

- [ ] **Step 4: typed properties, migration checksum/lock, Java 25 runtime을 구현한다**

```kotlin
@ConfigurationProperties(prefix = "workshop.ticket", ignoreUnknownFields = false)
data class TicketProperties(
    val db: TicketDatabaseProperties = TicketDatabaseProperties(),
    val redis: TicketRedisProperties = TicketRedisProperties(),
    val worker: TicketWorkerProperties = TicketWorkerProperties(),
    val sse: TicketSseProperties = TicketSseProperties(),
)

object TicketStartupValidator {
    fun validate(properties: TicketProperties) {
        val db = properties.db
        require(db.foregroundPermits + db.workerPermits + db.ssePermits + db.operatorPermits <=
            db.maxPoolSize - 2) { "INVALID_DATABASE_CAPACITY" }
        require(properties.redis.commandTimeout < properties.redis.renewInterval)
        require(properties.redis.renewInterval.multipliedBy(2) < properties.redis.leaseTtl)
    }
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketRuntimeContractTest' --tests '*TicketStartupValidationTest'`

Expected: PASS; runtime reports Java 25, preview disabled, invalid configuration rejected.

- [ ] **Step 5: Lore commit을 만든다**

```bash
git add commerce/concert-ticket-flash-sale
git commit -m "Isolate flash-sale capacity before opening the module" \
  -m "Constraint: Java 25 target and Hikari lane reservation are startup invariants" \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Tested: TicketRuntimeContractTest, TicketStartupValidationTest"
```

### Task 2: Modulith APIs and pure sale/purchase/ticket state machines

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/salecontrol/api/SaleApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/admission/api/AdmissionApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/api/PurchaseApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/payment/api/PaymentApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/ticketing/api/TicketingApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/operations/api/OperationsApi.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/domain/TicketModels.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/domain/TicketTransitions.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/domain/TicketTransitionsTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketModuleBoundaryTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/salecontrol/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/admission/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/purchase/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/payment/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/ticketing/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/operations/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/salecontrol/api/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/admission/api/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/purchase/api/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/operations/api/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/payment/api/package-info.java`
- Create: `commerce/concert-ticket-flash-sale/src/main/java/io/bluetape4k/workshop/commerce/ticket/ticketing/api/package-info.java`

- [ ] **Step 1: 상태 전이와 의존 방향의 실패 테스트를 작성한다**

```kotlin
@Test
fun `timeout never releases inventory`() {
    transition(PurchaseState.PAYMENT_AUTHORIZING, PaymentOutcome.UNKNOWN) shouldBeEqualTo
        PurchaseTransition(PurchaseState.RECONCILIATION_REQUIRED, heldDelta = 0, soldDelta = 0)
}

@Test
fun `modulith dependencies follow the approved graph`() {
    ApplicationModules.of(TicketFlashSaleApplication::class.java).verify()
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketTransitionsTest' --tests '*TicketModuleBoundaryTest'`

Expected: FAIL because states, APIs, and named interfaces do not exist.

- [ ] **Step 2: stable state codes와 pure transition table을 구현한다**

```kotlin
enum class PurchaseState(val code: String) {
    INVENTORY_HELD("inventory_held"), PAYMENT_AUTHORIZING("payment_authorizing"),
    RECONCILIATION_REQUIRED("reconciliation_required"), CANCELLATION_REQUESTED("cancellation_requested"),
    APPROVED("approved"), DECLINED("declined"), CANCELLED("cancelled"), EXPIRED("expired"),
    REFUND_PENDING("refund_pending"), REFUNDED("refunded"), REFUND_QUARANTINED("refund_quarantined")
}

data class PurchaseTransition(val next: PurchaseState, val heldDelta: Int, val soldDelta: Int)

fun transition(state: PurchaseState, outcome: PaymentOutcome): PurchaseTransition = when (state to outcome) {
    PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.UNKNOWN ->
        PurchaseTransition(PurchaseState.RECONCILIATION_REQUIRED, 0, 0)
    PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.APPROVED ->
        PurchaseTransition(PurchaseState.APPROVED, -1, 1)
    PurchaseState.PAYMENT_AUTHORIZING to PaymentOutcome.DECLINED ->
        PurchaseTransition(PurchaseState.DECLINED, -1, 0)
    else -> throw StaleTransition(state.code, outcome.name)
}
```

- [ ] **Step 3: one-way module API를 구현한다**

```kotlin
interface PurchaseCommands {
    fun start(command: StartPurchase): PurchaseSnapshot
    fun applyPaymentOutcome(command: ApplyPaymentOutcome): PurchaseSnapshot
    fun applyTicketOutcome(command: ApplyTicketOutcome): PurchaseSnapshot
}

data class AuthorizationRequested(val eventId: UUID, val attemptId: UUID, val operationId: UUID)
data class ConsumeGrant(val saleId: UUID, val grantNonce: UUID, val buyerSubjectId: UUID,
    val policyVersion: Long, val attemptId: UUID)
interface PaymentQueries { fun operation(operationId: UUID): PaymentOperationSnapshot? }
interface TicketingQueries { fun effect(operationId: UUID): TicketEffectSnapshot? }
```

각 module package에는 다음 metadata를 실제 allowed dependency에 맞게 작성한다.

```java
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"salecontrol :: api", "admission :: api"}
)
package io.bluetape4k.workshop.commerce.ticket.purchase;
```

각 `api/package-info.java`는 `@org.springframework.modulith.NamedInterface("api")`를 선언한다.
operations module metadata의 `allowedDependencies`는
`salecontrol :: api, admission :: api, purchase :: api, payment :: api, ticketing :: api`로 고정한다.
`application.yml`에는 `spring.modulith.detection-strategy: explicitly-annotated`를 두어 `config`,
`domain`, `identity`, `idempotency`, `persistence`, `redis`, `web` 지원 package가 별도 업무 module로
자동 감지되지 않게 한다. boundary test는 감지된 module 이름이 정확히
`salecontrol,admission,purchase,payment,ticketing,operations`인지도 assert한다.

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketTransitionsTest' --tests '*TicketModuleBoundaryTest'`

Expected: PASS; dependency graph is `admission -> salecontrol`, `purchase -> salecontrol,admission`,
`payment,ticketing -> purchase`, `operations -> *.api`.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Keep payment uncertainty inside explicit ticket states" \
  -m "Rejected: bidirectional purchase-payment module dependencies | they create a Modulith cycle" \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Tested: TicketTransitionsTest, TicketModuleBoundaryTest"
```

### Task 3: PostgreSQL schema, migrations, repositories, and lock order

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketTables.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketRecords.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketRepositories.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketJdbcExecutor.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/resources/db/migration/V001__concert_ticket_flash_sale.sql`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/persistence/TicketRepositoryIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketMigrationRunnerTest.kt`

- [ ] **Step 1: oversell, partial unique guard, migration reapply 실패 테스트를 작성한다**

```kotlin
@Test
fun `inventory check rejects held plus sold above total`() = postgresFixture {
    shouldThrow<ExposedSQLException> {
        inventory.forceQuantities(total = 1, held = 1, sold = 1)
    }
}

@Test
fun `user and ip active guards are unique per sale`() = postgresFixture {
    guards.insert(saleId, IdentityKind.USER, userSubject, firstAttempt)
    shouldThrow<ExposedSQLException> {
        guards.insert(saleId, IdentityKind.USER, userSubject, secondAttempt)
    }
}

@Test
fun `migration contract covers fresh forward checksum reapply serialization and readiness`() {
    migration.assertFreshSchemaStarts()
    migration.assertV1ToCurrentStarts()
    migration.assertChecksumDriftFails(TicketStartupFailure.MIGRATION_CHECKSUM_MISMATCH)
    migration.assertReapplyNoOp()
    migration.assertConcurrentStartRunsOnce()
    migration.assertReadinessIsFalseUntilComplete()
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketRepositoryIntegrationTest' --tests '*TicketMigrationRunnerTest' --max-workers=1`

Expected: FAIL because schema, migration runner, checksum history, advisory lock, and repositories do not exist.

- [ ] **Step 2: V001 schema를 작성한다**

```sql
CREATE TABLE ticket_inventory (
  sale_id UUID NOT NULL,
  grade VARCHAR(32) NOT NULL,
  total_quantity INTEGER NOT NULL,
  held_quantity INTEGER NOT NULL DEFAULT 0,
  sold_quantity INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (sale_id, grade),
  CHECK (held_quantity >= 0 AND sold_quantity >= 0
    AND held_quantity + sold_quantity <= total_quantity)
);
CREATE TABLE ticket_active_identity_guards (
  sale_id UUID NOT NULL,
  identity_kind VARCHAR(8) NOT NULL,
  identity_subject_id UUID NOT NULL,
  active_attempt_id UUID NOT NULL,
  PRIMARY KEY (sale_id, identity_kind, identity_subject_id)
);
CREATE INDEX ticket_waiting_claim_idx
  ON ticket_waiting_room_entries(sale_id, state, sequence, id);
CREATE UNIQUE INDEX ticket_provider_operation_uk
  ON ticket_payment_operations(provider, operation_id);
CREATE INDEX ticket_reconcile_due_idx
  ON ticket_payment_operations(status, next_reconcile_at, id);
```

- [ ] **Step 3: permit-required repository와 전역 lock order를 구현한다**

```kotlin
enum class TicketLockRank { IDEMPOTENCY, USER_GUARD, IP_GUARD, BUYER, INVENTORY, ATTEMPT_ORDER, EFFECT }

class TicketInventoryRepository(private val jdbc: TicketJdbcExecutor) {
    fun lock(saleId: UUID, grade: String): InventoryRecord = jdbc.requiredPermit {
        TicketInventoryTable.selectAll()
            .where { (TicketInventoryTable.saleId eq saleId) and (TicketInventoryTable.grade eq grade) }
            .forUpdate().single().toInventoryRecord()
    }
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketRepositoryIntegrationTest' --tests '*TicketMigrationRunnerTest' --max-workers=1`

Expected: PASS; constraint, index, checksum, advisory lock and reapply no-op are verified.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src/main/resources/db commerce/concert-ticket-flash-sale/src/main/kotlin commerce/concert-ticket-flash-sale/src/test
git commit -m "Make PostgreSQL the only ticket inventory authority" \
  -m "Constraint: USER and IP guards survive Redis key loss" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: TicketRepositoryIntegrationTest, TicketMigrationRunnerTest"
```

### Task 4: Identity aliases, trusted proxy, waiting room, and owner-scoped idempotency

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/identity/IdentityService.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/identity/TrustedClientAddressResolver.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/idempotency/IdempotencyFingerprint.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/idempotency/HttpIdempotencyRepository.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/admission/internal/AdmissionService.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/identity/IdentityRotationIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/identity/TrustedClientAddressResolverTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/idempotency/HttpIdempotencyRepositoryTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/admission/AdmissionConcurrencyIntegrationTest.kt`

- [ ] **Step 1: cross-principal replay, proxy spoof, grant reuse 실패 테스트를 작성한다**

```kotlin
@Test
fun `nonterminal idempotency replay bypasses redis`() {
    val first = repository.acquire(scope, fingerprint)
    repository.attachAttempt(first.id, attemptId)
    redis.stop()
    repository.acquire(scope, fingerprint) shouldBeEqualTo IdempotencyDecision.Replay(attemptId, completed = false)
}

@Test
fun `one admission grant can be consumed once`() = postgresFixture {
    service.consume(grantCommand(firstAttempt))
    shouldThrow<AdmissionExpired> { service.consume(grantCommand(secondAttempt)) }
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*IdentityRotationIntegrationTest' --tests '*TrustedClientAddressResolverTest' --tests '*HttpIdempotencyRepositoryTest' --tests '*AdmissionConcurrencyIntegrationTest' --max-workers=1`

Expected: FAIL because identity aliases, proxy parser, idempotency repository and grant service do not exist.

- [ ] **Step 2: versioned identity alias와 key digest를 구현한다**

```kotlin
class IdentityService(private val keys: IdentityKeyRing, private val aliases: IdentityAliasRepository) {
    fun resolve(kind: IdentityKind, canonical: String): IdentitySubject =
        keys.activeReadVersions.asSequence()
            .map { version -> version to keys.digest(version, kind, canonical) }
            .mapNotNull { (version, digest) -> aliases.find(kind, version, digest) }
            .firstOrNull()
            ?.also { aliases.ensureCurrentAlias(it, keys.currentVersion, keys.digestCurrent(kind, canonical)) }
            ?: aliases.create(kind, keys.currentVersion, keys.digestCurrent(kind, canonical))
}

fun idempotencyKeyDigest(secret: ByteArray, rawKey: String): ByteArray =
    hmacSha256(secret, "ticket-idempotency\u0000$rawKey")
```

- [ ] **Step 3: canonical FIFO claim과 MANDATORY grant consume을 구현한다**

```kotlin
@Transactional(propagation = Propagation.MANDATORY)
override fun consume(command: ConsumeGrant) {
    val updated = grants.consumeIfUnused(command.saleId, command.grantNonce, command.buyerSubjectId,
        command.policyVersion, command.attemptId, clock.instant())
    if (updated != 1) throw AdmissionExpired(command.grantNonce)
}

fun claimBatch(saleId: UUID, limit: Int = 50): List<WaitingEntry> =
    waitingRoom.claim("WHERE sale_id=? AND state='WAITING' ORDER BY sequence,id LIMIT ? FOR UPDATE SKIP LOCKED",
        saleId, limit)
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*IdentityRotationIntegrationTest' --tests '*TrustedClientAddressResolverTest' --tests '*HttpIdempotencyRepositoryTest' --tests '*AdmissionConcurrencyIntegrationTest' --max-workers=1`

Expected: PASS; rotation, dormant owner lookup, proxy chain, FIFO, grant single-use and replay pass.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Bind ticket admission to durable private identities" \
  -m "Directive: Replay owner-scoped idempotency before Redis" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: identity, trusted proxy, idempotency, admission integration tests"
```

### Task 5: Atomic Redis IP/user lease, rate limit, and degraded behavior

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/redis/MultiKeyLeaseAdapter.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketRedisConfiguration.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/redis/MultiKeyLeaseIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/redis/RedisUnavailableIntegrationTest.kt`

- [ ] **Step 1: partial acquire/wrong owner/response-loss/rotation 실패 테스트를 작성한다**

```kotlin
@Test
fun `acquire is all or nothing for ip and user keys`() {
    redis.set(ipKey, "other-owner", ttl)
    adapter.acquire(leaseRequest) shouldBeEqualTo LeaseDecision.Busy
    redis.exists(userKey) shouldBeFalse()
}

@Test
fun `wrong owner cannot renew or release`() {
    adapter.acquire(leaseRequest) shouldBeEqualTo LeaseDecision.Acquired
    adapter.release(leaseRequest.copy(ownerToken = wrongToken)) shouldBeFalse()
}

@Test
fun `acquire response loss across lease key rotation reuses retained owner token`() {
    val lost = adapter.acquireAndDropResponse(request, keyVersion = 1)
    keys.rotateTo(2, retain = setOf(1, 2))
    adapter.acquire(request) shouldBeEqualTo LeaseDecision.AlreadyOwned(version = 1)
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*MultiKeyLeaseIntegrationTest' --max-workers=1`

Expected: FAIL because adapter and scripts do not exist.

- [ ] **Step 2: `RedisScriptRunner` 기반 Lua adapter를 구현한다**

```lua
for _, key in ipairs(KEYS) do
  local value = redis.call('GET', key)
  if value and value ~= ARGV[1] then return 0 end
end
for _, key in ipairs(KEYS) do redis.call('SET', key, ARGV[1], 'PX', ARGV[2]) end
return 1
```

```kotlin
class MultiKeyLeaseAdapter(private val runner: RedisScriptRunner<String>) {
    fun acquire(request: LeaseRequest): LeaseDecision =
        request.ownerCandidates.firstNotNullOfOrNull { candidate ->
            runner.run(ACQUIRE, request.keys, candidate.value, request.ttl.toMillis().toString())
                .takeIf { it == 1L }?.let { LeaseDecision.Acquired(candidate.version) }
        } ?: LeaseDecision.Busy
}
```

- [ ] **Step 3: fail-closed 신규 purchase와 DB-claim fallback을 구현한다**

```kotlin
fun admit(request: LeaseRequest): LeaseHandle = try {
    when (lease.acquire(request)) {
        LeaseDecision.Acquired -> LeaseHandle(request, lease)
        LeaseDecision.Busy -> throw PurchaseApprovalInProgress()
    }
} catch (e: RedisException) {
    throw AdmissionTemporarilyUnavailable(e)
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*MultiKeyLease*' --tests '*RedisUnavailable*' --max-workers=1`

Expected: PASS; new purchase returns fail-closed while existing DB reconciliation remains runnable.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Use Redis only to reject duplicate foreground work early" \
  -m "Rejected: Redis as purchase ledger | key loss must not change business truth" \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Tested: MultiKeyLeaseIntegrationTest, RedisUnavailableIntegrationTest"
```

### Task 6: Atomic purchase hold, inventory accounting, and cancellation

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/internal/PurchaseService.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/salecontrol/internal/SaleService.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/PurchaseServiceIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/PurchaseConcurrencyIntegrationTest.kt`

- [ ] **Step 1: pre-open row-zero, same USER/IP, last inventory 실패 테스트를 작성한다**

```kotlin
@Test
fun `one nanosecond before opensAt creates no durable row`() = fixture {
    clock.set(opensAt.minusNanos(1))
    shouldThrow<SaleNotStarted> { purchase.start(command) }
    counts() shouldBeEqualTo DurableCounts.ZERO
}

@Test
fun `two buyers sharing one ip leave one active attempt`() = multithreaded(2) {
    purchase.start(commandForUser(it, sameIp))
}.also { result ->
    result.successes.size shouldBeEqualTo 1
    invariants.activeIpGuards(saleId) shouldBeEqualTo 1
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*Purchase*' --max-workers=1`

Expected: FAIL because purchase transaction is not implemented.

- [ ] **Step 2: fixed-order transaction과 after-commit event를 구현한다**

```kotlin
@Transactional
override fun start(command: StartPurchase): PurchaseSnapshot {
    sale.requireOpen(command.saleId, clock.instant())
    idempotency.lock(command.idempotencyScope)
    val identities = guards.lockOrCreate(command.saleId, command.userSubjectId, command.ipSubjectId)
    admission.consume(command.grant)
    val inventory = inventories.lock(command.saleId, command.grade)
    inventory.requireAvailable(command.quantity)
    val attempt = attempts.createHeld(command, inventory.policyVersion)
    inventories.applyDelta(inventory, held = command.quantity, sold = 0)
    guards.attach(identities, attempt.id)
    events.publish(AuthorizationRequested(attempt.eventId, attempt.id, attempt.authorizationOperationId))
    return attempt.snapshot()
}
```

- [ ] **Step 3: cancel/expire와 guard release table을 구현한다**

```kotlin
when (attempt.state) {
    PurchaseState.INVENTORY_HELD -> finalizeCancelled(attempt, heldDelta = -attempt.quantity, releaseGuard = true)
    PurchaseState.PAYMENT_AUTHORIZING,
    PurchaseState.RECONCILIATION_REQUIRED -> markCancellationRequested(attempt, releaseGuard = false)
    else -> replayTerminal(attempt)
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*Purchase*' --max-workers=1`

Expected: PASS with oversell 0, one active USER/IP attempt, pre-open durable row 0.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Serialize ticket holds where inventory is authoritative" \
  -m "Constraint: provider and Redis calls stay outside row locks" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: PurchaseServiceIntegrationTest, PurchaseConcurrencyIntegrationTest"
```

### Task 7: Payment fencing, lookup-first reconciliation, refund, issue, and revoke

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/payment/internal/FakePaymentProvider.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/payment/internal/PaymentWorker.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/ticketing/internal/TicketEffectWorker.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/internal/RefundService.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketPublicationConfiguration.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/payment/PaymentReconciliationIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/ticketing/TicketEffectIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/purchase/RefundRaceIntegrationTest.kt`

- [ ] **Step 1: timeout, stale worker, crash window, duplicate effect 실패 테스트를 작성한다**

```kotlin
@Test
fun `stale claim cannot apply late provider response`() = fixture {
    val first = worker.claim(operationId)
    clock.advance(claimTtl)
    val second = worker.claim(operationId)
    worker.apply(first, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.Stale
    worker.apply(second, PaymentOutcome.APPROVED) shouldBeEqualTo ApplyResult.Applied
}

@Test
fun `late approval suppresses issue and restocks only after refund`() = fixture {
    cancelWhileAuthorizing(attemptId)
    provider.complete(operationId, PaymentOutcome.APPROVED)
    reconcile()
    order(attemptId).ticketDisposition shouldBeEqualTo TicketDisposition.NEVER_ISSUED
    inventory().sold shouldBeEqualTo 1
    completeRefund()
    inventory().sold shouldBeEqualTo 0
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*Payment*' --tests '*TicketEffect*' --tests '*RefundRace*' --max-workers=1`

Expected: FAIL because provider, claim fencing, effect ledger and refund flow do not exist.

- [ ] **Step 2: stable operation intent와 fenced claim을 구현한다**

```kotlin
data class PaymentClaim(val operationId: UUID, val claimToken: UUID, val revision: Long)

fun apply(claim: PaymentClaim, outcome: PaymentOutcome): ApplyResult = transaction {
    val updated = operations.applyIfClaimOwner(claim.operationId, claim.claimToken, claim.revision, outcome)
    if (updated == 0) ApplyResult.Stale else ApplyResult.Applied
}

fun resume(operation: PaymentOperation): PaymentOutcome =
    provider.lookup(operation.operationId) ?: provider.authorize(operation.operationId, operation.request)
```

- [ ] **Step 3: effect receipt와 restock gate를 구현한다**

```kotlin
@Transactional
fun finalizeRefund(orderId: UUID, refund: EffectReceipt) {
    val order = orders.lock(orderId)
    receipts.insertOnce("refund", refund.operationId)
    check(order.ticketDisposition == TicketDisposition.NEVER_ISSUED ||
        order.ticketDisposition == TicketDisposition.REVOKED)
    inventories.applyDelta(order.saleId, order.grade, held = 0, sold = -order.quantity)
    guards.releaseRemediation(order.attemptId)
    orders.markRefunded(orderId)
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*Payment*' --tests '*TicketEffect*' --tests '*RefundRace*' --max-workers=1`

Expected: PASS; provider/effect count remains one across crash and duplicate delivery.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Reconcile uncertain payment without guessing its outcome" \
  -m "Directive: Preserve stable operation IDs across every retry and restart" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: payment, ticket effect, refund race integration tests"
```

### Task 8: Customer/operator HTTP, owner security, problems, and redaction

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/CustomerTicketController.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/OperatorTicketController.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/ApiExceptionHandler.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/AuthenticatedBuyerResolver.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketSecurityConfiguration.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/OperatorAccessFilter.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/RequestLoggingFilter.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/CustomerTicketWebIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/OperatorAccessIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketInputBoundaryIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketRedactionContractTest.kt`

- [ ] **Step 1: owner isolation, closed DTO, stable problem 실패 테스트를 작성한다**

```kotlin
@Test
fun `other buyer cannot discover an attempt`() {
    clientFor("buyer-b").get().uri("/api/v1/purchase-attempts/$buyerAAttempt")
        .exchange().expectStatus().isNotFound
}

@Test
fun `redis outage returns retryable stable problem`() {
    redis.stop()
    purchaseRequest().expectStatus().isEqualTo(503)
        .expectBody().jsonPath("$.code").isEqualTo("admission_temporarily_unavailable")
        .jsonPath("$.retryable").isEqualTo(true)
}

@Test
fun `missing invalid or unauthenticated production identity fails closed`() {
    productionClient().post().uri("/api/v1/sales/$saleId/purchase-attempts")
        .exchange().expectStatus().isUnauthorized
    fixture.durableCounts() shouldBeEqualTo DurableCounts.ZERO
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*CustomerTicketWebIntegrationTest' --tests '*OperatorAccessIntegrationTest' --tests '*TicketInputBoundaryIntegrationTest' --tests '*TicketRedactionContractTest' --max-workers=1`

Expected: FAIL because controllers, filters and problem mapping do not exist.

- [ ] **Step 2: owner-scoped controller와 normal `202` snapshot을 구현한다**

```kotlin
@PostMapping("/api/v1/sales/{saleId}/purchase-attempts")
fun purchase(@PathVariable saleId: UUID, @RequestHeader("Idempotency-Key") key: String,
    @Valid @RequestBody request: PurchaseRequest, principal: Principal): ResponseEntity<PurchaseSnapshot> {
    val snapshot = commands.start(request.toCommand(saleId, buyerResolver.resolve(principal), key))
    return ResponseEntity.accepted().location(URI.create("/api/v1/purchase-attempts/${snapshot.attemptId}"))
        .body(snapshot)
}
```

- [ ] **Step 3: demo-only operator/auth와 allowlist redaction을 구현한다**

```kotlin
@Bean
@Profile("demo")
fun demoBuyerResolver(environment: Environment): AuthenticatedBuyerResolver {
    require(environment.getProperty("server.address") in setOf("127.0.0.1", "::1"))
    return DemoHeaderBuyerResolver()
}

@Bean
@Profile("!demo")
fun productionSecurity(http: HttpSecurity,
    resolver: ObjectProvider<AuthenticatedBuyerResolver>): SecurityFilterChain {
    require(resolver.getIfAvailable() != null) { "production AuthenticatedBuyerResolver is required" }
    return http.authorizeHttpRequests {
        it.requestMatchers(HttpMethod.GET, "/api/v1/sales/*").permitAll()
            .anyRequest().authenticated()
    }.build()
}

data class TicketProblem(val code: String, val status: Int, val retryable: Boolean,
    val retryAt: Instant?, val nextAction: String?, val correlationId: String)
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*CustomerTicketWebIntegrationTest' --tests '*OperatorAccessIntegrationTest' --tests '*TicketInputBoundaryIntegrationTest' --tests '*TicketRedactionContractTest' --max-workers=1`

Expected: PASS; other-owner reads are 404 and canary raw values appear nowhere.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Expose ticket recovery without exposing another buyer" \
  -m "Constraint: demo identity and operator routes remain loopback-only" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: customer/operator/input/redaction HTTP integration tests"
```

### Task 9: Snapshot-first SSE, health, metrics, lifecycle, and operator recovery

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketEventStream.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/operations/internal/OperationsService.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketHealthIndicators.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketMetrics.kt`
- Create: `commerce/concert-ticket-flash-sale/src/main/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketLifecycle.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketEventStreamIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketEventStreamCapacityIntegrationTest.kt`
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketHealthLifecycleIntegrationTest.kt`

- [ ] **Step 1: snapshot gap, slow consumer, Redis-degraded health 실패 테스트를 작성한다**

```kotlin
@Test
fun `terminal event between snapshot and subscribe is caught up`() {
    val stream = fixture.pauseAfterSnapshot()
    fixture.completeAttempt()
    stream.resume()
    stream.events.map { it.version } shouldContain terminalVersion
}

@Test
fun `redis down degrades purchase readiness but keeps reconciliation`() {
    redis.stop()
    health.purchaseReadiness() shouldBeEqualTo Status.OUT_OF_SERVICE
    health.liveness() shouldBeEqualTo Status.UP
    reconciliation.runBatch().processed shouldBeGreaterThanOrEqualTo 1
}

@Test
fun `public sale stream never emits owner payment fields`() {
    publicStream(saleId).events.forEach { event ->
        event.payload.keys shouldBeEqualTo setOf("saleId", "status", "grades", "version", "serverTime")
    }
    ownerStream(otherBuyer, attemptId).status shouldBeEqualTo 404
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*EventStream*' --tests '*HealthLifecycle*' --max-workers=1`

Expected: FAIL because SSE broadcaster and health/lifecycle components do not exist.

- [ ] **Step 2: sale-shared broadcaster와 high-water catch-up을 구현한다**

```kotlin
fun subscribe(scope: StreamScope, cursor: Long?): TicketSubscription {
    val initial = source.snapshotWithHighWater(scope)
    val subscription = TicketSubscription(queueSize = 32, cursor = cursor ?: initial.highWater)
    poller(scope.saleId).attach(subscription)
    source.eventsAfter(scope, subscription.cursor, maxRows = 200).forEach(subscription::offer)
    return subscription
}
```

`PublicSaleScope`는 aggregate allowlist serializer만, `OwnerAttemptScope`는 authenticated principal과
attempt owner 검증 뒤 owner snapshot serializer만 사용한다. cursor signing scope에도 stream kind와
principal subject를 포함한다.

- [ ] **Step 3: bounded operator run, health, shutdown을 구현한다**

```kotlin
fun reconcile(command: OperatorReconcile): ReconcileSummary = operatorPermit.withPermit {
    reconciliation.runBatch(limit = command.limit.coerceAtMost(50), deadline = Duration.ofSeconds(10))
}

@PreDestroy
fun shutdown() {
    admission.stopNewWork()
    eventStream.closeWithReconnectHint()
    workers.stopClaiming()
    lifecycle.awaitTransactions(Duration.ofSeconds(10))
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*EventStream*' --tests '*HealthLifecycle*' --max-workers=1`

Expected: PASS; subscriber growth does not increase poll QPS and permits return to zero.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale/src
git commit -m "Bound ticket observation and recovery independently" \
  -m "Constraint: SSE network writes never retain JDBC permits" \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Tested: SSE capacity, health, lifecycle integration tests"
```

### Task 10: End-to-end failure matrix, migration, and stress evidence

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/AbstractTicketIntegrationTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketEndToEndIntegrationTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketHostileConcurrencyIntegrationTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketContextRestartIntegrationTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/config/TicketMigrationCompatibilityIntegrationTest.kt`
- Create: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/TicketStressProfileTest.kt`
- Modify: `commerce/concert-ticket-flash-sale/build.gradle.kts`

- [ ] **Step 1: complete failure matrix parameterized test를 작성한다**

```kotlin
@ParameterizedTest
@MethodSource("failureScenarios")
fun `every failure converges without oversell`(scenario: FailureScenario) = scenario.run(fixture).also {
    fixture.assertInventoryInvariant()
    fixture.assertNoDuplicateProviderEffect()
    fixture.assertNoRawCanary()
}

companion object {
    @JvmStatic
    fun failureScenarios() = FailureScenarioId.entries.stream().map(::scenarioFor)
}

enum class FailureScenarioId {
    PRE_OPEN_ROW_ZERO, OPEN_BOUNDARY_POLICY, IDEMPOTENT_SAME_PAYLOAD,
    IDEMPOTENT_CONFLICT, RAW_IDEMPOTENCY_REDACTION, SAME_USER_RACE, SAME_IP_RACE,
    REDIS_DELETE_SAME_IP, IDENTITY_KEY_ROTATION, LEASE_KEY_ROTATION_RESPONSE_LOSS,
    DORMANT_OWNER_ROTATION, GRANT_DOUBLE_CONSUME, LAST_INVENTORY_RACE,
    REDIS_ACQUIRE_RESPONSE_LOSS, REDIS_KEY_DELETE, DB_FAILURE_LEASE_REMAINS,
    PAYMENT_TIMEOUT_APPROVED, PAYMENT_TIMEOUT_CANCELLED, DUPLICATE_PROVIDER_RESULT,
    DUPLICATE_OUTBOX, PROVIDER_SUCCESS_CHECKPOINT_CRASH, STALE_CLAIM_RESPONSE,
    THREE_WAY_CANCEL_PAYMENT_REFUND, WORKER_RESTART, REFUND_SUCCESS_REVOKE_FAILURE,
    LATE_APPROVAL_ISSUE_RACE, UNTRUSTED_FORWARDED, MALFORMED_PROXY_CHAIN,
    SSE_CURSOR_EXPIRED, SSE_SNAPSHOT_SUBSCRIBE_GAP, CROSS_OWNER_ID_CURSOR,
    INVALID_INPUT_BOUNDARY, REDACTION_CANARY, PRODUCTION_AUTH_FAILURE, WILDCARD_TRUST_CIDR
}

@Test
fun `failure matrix is exhaustive and unique`() {
    val scenarios = failureScenarios().toList()
    scenarios.map { it.id }.toSet() shouldBeEqualTo FailureScenarioId.entries.toSet()
    scenarios.size shouldBeEqualTo FailureScenarioId.entries.size
}
```

`scenarioFor`는 각 ID마다 설계 §18.2의 HTTP code, public/internal terminal state, durable row count,
inventory delta, provider/effect count를 `FailureExpectation`으로 명시한다. 예를 들어
`PRE_OPEN_ROW_ZERO`는 `409 sale_not_started`, durable row 0이고,
`PROVIDER_SUCCESS_CHECKPOINT_CRASH`는 effect count 1과 terminal convergence를 요구한다.

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketEndToEndIntegrationTest' --max-workers=1`

Expected: FAIL until all fixture seams expose deterministic barriers and restart handles.

- [ ] **Step 2: deterministic fixture와 stress task를 구현한다**

```kotlin
val ticketStressRun = providers.gradleProperty("ticketStressRun")
val ticketStressTest by tasks.registering(Test::class) {
    useJUnitPlatform { includeTags("stress") }
    outputs.dir(layout.buildDirectory.dir("reports/ticket-stress"))
    doFirst {
        val run = ticketStressRun.orNull ?: error("-PticketStressRun=<unique-run-id> is required")
        systemProperty("ticket.stress.run", run)
    }
}
```

Run: `./gradlew :commerce-concert-ticket-flash-sale:ticketStressTest --max-workers=1`

Expected: FAIL before test execution with `-PticketStressRun=<unique-run-id> is required`.

Stress profiles:

```text
same-grade: inventory=10000 concurrency=200 duration=60s repeats=3
waiting-room: entries=100000 batch=50
sse: activeSales=32 connections=1..512
recovery: unknownOperations=10000 claimants=8 batch=50
```

각 profile은 warm-up 10초 뒤 60초 측정을 3회 수행하고 다음 assertion을 실행한다.

```kotlin
evidence.sameGrade.run {
    oversell shouldBeEqualTo 0
    duplicateEffects shouldBeEqualTo 0
    throughputPerSecond shouldBeGreaterThanOrEqualTo 100.0
    lockWaitP99 shouldBeLessThanOrEqualTo Duration.ofMillis(250)
    transactionP99 shouldBeLessThanOrEqualTo Duration.ofMillis(500)
    dbPermitRejectionRatio shouldBeLessThan 0.01
}
evidence.waitingRoom.run {
    scannedToGrantedRatio shouldBeLessThanOrEqualTo 2.0
    grantLagP95 shouldBeLessThanOrEqualTo Duration.ofSeconds(2)
    maxPoolUsage shouldBeLessThanOrEqualTo 18
}
evidence.sse.pollQueries shouldBeLessThanOrEqualTo(
    evidence.sse.activeSales * evidence.sse.measurementIntervals + evidence.sse.catchUpQueries,
)
evidence.recovery.run {
    drainRatePerSecond shouldBeGreaterThanOrEqualTo 200.0
    duplicateEffects shouldBeEqualTo 0
    permitLeaks shouldBeEqualTo 0
}
```

어떤 threshold라도 실패하면 JUnit failure로 task가 non-zero 종료한다. JSON/CSV에는 seed,
container image, host, JDK, CPU, RAM, warm-up, duration, repetition을 기록한다.

- [ ] **Step 3: serial integration과 opt-in stress를 실행한다**

Run:

```bash
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:ticketStressTest -PticketStressRun=local-issue-521 --max-workers=1
```

Expected: unit/integration PASS; stress JSON/CSV includes environment, p50/p95/p99, DB/Redis/permit,
oversell=0, duplicate-effect=0, leak=0.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale
git commit -m "Prove flash-sale recovery under hostile timing" \
  -m "Constraint: local latency is a regression gate, not a production capacity promise" \
  -m "Confidence: high" -m "Scope-risk: broad" \
  -m "Tested: full module test and ticketStressTest"
```

### Task 11: Accessible demo, bilingual README, state diagrams, and runbook

**Files:**
- Create: `commerce/concert-ticket-flash-sale/src/main/resources/static/index.html`
- Create: `commerce/concert-ticket-flash-sale/src/main/resources/static/app.js`
- Create: `commerce/concert-ticket-flash-sale/src/main/resources/static/styles.css`
- Create: `commerce/concert-ticket-flash-sale/README.md`
- Create: `commerce/concert-ticket-flash-sale/README.ko.md`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-architecture-01.svg`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-integrated-state-01.svg`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-normal-purchase-sequence-01.svg`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-timeout-refund-sequence-01.svg`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-authority-01.svg`
- Create: `docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-microservice-extraction-01.svg`
- Create: matching `.png` files for all six SVG paths
- Test: `commerce/concert-ticket-flash-sale/src/test/kotlin/io/bluetape4k/workshop/commerce/ticket/web/TicketBrowserContractTest.kt`
- Create: `scripts/validate-ticket-flash-sale-runbook.mjs`
- Create: `scripts/test-ticket-flash-sale-browser.mjs`

- [ ] **Step 1: browser/accessibility/runbook contract 실패 테스트를 작성한다**

```kotlin
@Test
fun `demo exposes accessible recovery status`() {
    val html = resource("static/index.html")
    html shouldContain "aria-live=\"polite\""
    html shouldContain "data-polling-fallback"
    resource("static/styles.css") shouldContain "prefers-reduced-motion"
}
```

headless Chrome CDP smoke는 실제 페이지에서 다음을 assert한다.

```javascript
await cdp("Emulation.setDeviceMetricsOverride", { width: 360, height: 800, deviceScaleFactor: 1, mobile: true });
await key("Tab");
const focus = await evaluate(`({
  tag: document.activeElement.tagName,
  outline: getComputedStyle(document.activeElement).outlineStyle
})`);
assert.notEqual(focus.tag, "BODY");
assert.notEqual(focus.outline, "none");
assert.equal(await evaluate(`document.documentElement.scrollWidth <= document.documentElement.clientWidth`), true);
assert.equal(await evaluate(`[...document.querySelectorAll("[data-status]")].every(
  el => el.textContent.trim().length > 0 && el.querySelector("[aria-label]")
)`), true);
await evaluate(`window.ticketDemo.disconnectSseForTest()`);
await waitFor(`[data-polling-fallback]:not([hidden])`);
assert.equal(await evaluate(`document.body.dataset.transport`), "polling");
```

script는 `GOOGLE_CHROME_BIN`, `google-chrome`, macOS Chrome 순으로 headless Chrome을 찾고 어느
경로도 없으면 실패한다. Node built-in `WebSocket`으로 CDP에 연결하므로 npm dependency는 추가하지
않는다.

Run: `./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketBrowserContractTest'`

Expected: FAIL because static assets and docs do not exist.

- [ ] **Step 2: dependency-free demo와 README learning path를 작성한다**

README sections must appear in this order:

```markdown
## Prerequisites and Java 25
## Run, seed, and reset
## Join the waiting room
## Purchase and replay a lost response
## Reconcile timeout and late approval
## Cancel, refund, revoke, and restock
## Operator invariant and backlog checks
## State mapping: internal state to customer action
## Redis and PostgreSQL authority
## Production security boundary
## Microservice extraction guide
```

- [ ] **Step 3: source + PNG diagrams를 생성·검증한다**

Diagram set:

```text
architecture
integrated-sale-purchase-payment-ticket-state
normal-purchase-sequence
timeout-late-approval-refund-sequence
redis-postgresql-authority
microservice-extraction
```

architecture/authority/microservice는 `common.md` + `architecture.md`, normal/timeout sequence는
`common.md` + `sequence.md`를 적용한다. integrated state는 sequence/lifecycle 규칙을 적용한다.
각 asset은 SVG 편집 -> XML -> text normalize -> CairoSVG `-s 2` -> connector/geometry/endpoint/
mixed-corner 및 kind audit -> full-size PNG inspection -> evidence ledger 순서로 하나씩 완료한다.
Graphviz는 사용하지 않는다.

Run:

```bash
node scripts/validate-ticket-flash-sale-runbook.mjs
node scripts/test-ticket-flash-sale-browser.mjs --url http://127.0.0.1:8080
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-readme-diagram-qa.mjs
node scripts/validate-sequence-diagrams.mjs
./gradlew :commerce-concert-ticket-flash-sale:test --tests '*TicketBrowserContractTest'
```

각 SVG에는 다음 direct gate도 실행한다.

```bash
TICKET_DIAGRAM=docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-architecture-01
xmllint --noout "${TICKET_DIAGRAM}.svg"
python3 "${CODEX_HOME}/skills/bluetape-diagram/scripts/diagram-svg-text-normalize.py" "${TICKET_DIAGRAM}.svg"
cairosvg "${TICKET_DIAGRAM}.svg" -o "${TICKET_DIAGRAM}.png" -s 2
python3 "${CODEX_HOME}/skills/bluetape-diagram/scripts/diagram-connector-audit.py" "${TICKET_DIAGRAM}.svg"
python3 "${CODEX_HOME}/skills/bluetape-diagram/scripts/diagram-geometry-audit.py" --fail-diagonal "${TICKET_DIAGRAM}.svg"
python3 "${CODEX_HOME}/skills/bluetape-diagram/scripts/diagram-endpoint-audit.py" "${TICKET_DIAGRAM}.svg"
python3 "${CODEX_HOME}/skills/bluetape-diagram/scripts/diagram-mixed-corner-audit.py" "${TICKET_DIAGRAM}.svg"
```

위 block을 다음 exact stem 각각에 대해 별도로 실행하고 매번 PNG를 full-size로 연다:

```text
docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-integrated-state-01
docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-normal-purchase-sequence-01
docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-timeout-refund-sequence-01
docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-authority-01
docs/images/readme-diagrams/commerce-concert-ticket-flash-sale-microservice-extraction-01
```

sequence 두 장과 integrated state에는 `diagram-sequence-style-audit.py`를 추가한다. Expected:
keyboard-only focus, visible focus, non-color status, 360px layout, 실제 SSE-disconnect-to-polling,
English/Korean link와 SVG/PNG pair가 완전하고, `text_hazards=0`, meaningful connector/card/label
counts, `shared_segments=0`, label collision 0, failures 0이며 각 PNG full-size inspection이 PASS다.

- [ ] **Step 4: commit한다**

```bash
git add commerce/concert-ticket-flash-sale docs/images/readme-diagrams scripts/validate-ticket-flash-sale-runbook.mjs scripts/test-ticket-flash-sale-browser.mjs
git commit -m "Teach operators where flash-sale truth survives failure" \
  -m "Constraint: state diagrams map customer action and restock timing" \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Tested: runbook validator, headless browser smoke, TicketBrowserContractTest, diagram ledger"
```

### Task 12: Repository matrices, lesson, review, and final verification

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `commerce/README.md`
- Modify: `commerce/README.ko.md`
- Modify: `AGENTS.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Modify: `src/test/resources/junit-platform.properties`
- Modify: `src/test/resources/logback-test.xml`
- Create: `docs/lessons/2026-07-21-issue-521-concert-ticket-flash-sale.md`
- Create: `docs/review/2026-07-21-issue-521-concert-ticket-flash-sale-review.md`

- [ ] **Step 1: stale registration failure를 먼저 확인한다**

Run:

```bash
rg -n "concert-ticket-flash-sale|commerce-concert-ticket-flash-sale" \
  README.md README.ko.md commerce/README.md commerce/README.ko.md AGENTS.md \
  .github/workflows/Examples.yml scripts/smoke-validate.sh
```

Expected: no repository integration entries before this task.

- [ ] **Step 2: smoke/full/nightly/Kover surface를 등록한다**

Workflow test group must include:

```yaml
- name: Run container-backed commerce examples
  run: |
    ./gradlew \
      :commerce-concert-ticket-flash-sale:test \
      --max-workers=1
```

Smoke validator must include:

```bash
:commerce-concert-ticket-flash-sale:test
```

JUnit/logback resources must register the package-specific serialization and log controls using the
same shape as `commerce/promotion-voucher-campaign`.

- [ ] **Step 3: Korean lesson과 review를 작성한다**

```markdown
## 핵심 교훈
- Redis의 원자성은 foreground 중복 억제에만 사용하고 PostgreSQL USER/IP guard를 남긴다.
- 결제 timeout을 실패로 해석하지 않고 stable operation ID로 조회한다.
- 환불과 ticket disposition이 모두 확정되기 전에는 재고를 복구하지 않는다.

## 남은 production 과제
- 실제 JWT/IdP와 operator RBAC adapter
- 실제 PG의 operation lookup/idempotency 계약 검증
- 측정된 트래픽으로 capacity 재산정
```

- [ ] **Step 4: 계층별 검증을 순서대로 실행한다**

Run:

```bash
./gradlew :commerce-concert-ticket-flash-sale:test --max-workers=1
./gradlew :commerce-concert-ticket-flash-sale:build --max-workers=1
./gradlew detekt
node scripts/validate-ticket-flash-sale-runbook.mjs
node scripts/test-ticket-flash-sale-browser.mjs --url http://127.0.0.1:8080
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-readme-diagram-qa.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml
bash scripts/smoke-validate.sh
git diff --check
```

Expected: all PASS with no diagnostics, YAML/action error, diagram audit gap, stale registration, missing
artifact, or uncommitted generated drift.

- [ ] **Step 5: 7-Tier review와 final Lore commit을 만든다**

Review must cover functional correctness, tests, error handling, docs, security, performance, and code quality;
all P0/P1 findings are fixed and rerun before commit.

```bash
git add README.md README.ko.md commerce/README.md commerce/README.ko.md AGENTS.md \
  .github/workflows/Examples.yml scripts src/test/resources docs/lessons docs/review
git commit -m "Register the flash-sale example as a maintained workshop surface" \
  -m "Constraint: smoke and full container lanes stay separate" \
  -m "Confidence: high" -m "Scope-risk: moderate" \
  -m "Tested: module build, detekt, runbook validator, smoke validator, diff check"
```

## 완료/PR gate

1. `git status --short`가 clean이다.
2. module test/build, detekt, validators, workflow syntax, diagram validator가 fresh PASS다.
3. capability import audit에서 개별 Bluetape BOM/version pin이 없다.
4. spec/plan/lesson/review/README와 실제 state/API/config가 일치한다.
5. PR은 exact feature head에서 `develop` 대상으로 생성한다.
6. CI와 unresolved review thread를 확인한 뒤 exact PR/head를 merge-ready로 보고한다.
7. merge는 별도 사용자 승인 전에는 실행하지 않는다.
8. merge 승인 후 develop을 local sync하고 이 feature worktree를 항상 제거한다. local branch는
   사용자가 별도로 삭제를 요청하지 않는 한 보존한다.
