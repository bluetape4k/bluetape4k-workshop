# Issue #537 Pre-generated Voucher Pool Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 25 Spring Boot MVC 애플리케이션에서 PostgreSQL-authoritative pre-generated voucher
pool을 구현하고, bounded import/generation, reserve/allocate/one-time reveal/redeem, replay fence,
worker recovery와 operator/browser runbook을 실제 PostgreSQL·Redis 경합으로 검증한다.

**Architecture:** 새 `:commerce-pre-generated-voucher-pool` consumer module이 campaign, batch,
entry, reservation, user-limit, idempotency tombstone와 worker claim을 PostgreSQL transaction 안에서
소유한다. Foreground는 campaign/batch `FOR SHARE`와 전역
`campaign -> batch -> user-limit -> reservation -> entry` 순서를 사용하고, policy/worker 전이는
exclusive update와 revision CAS로 순서를 확정한다. Redis/Bloom/leader는 advisory이고, JDK AES-GCM
envelope encryption과 tenant-lifetime digest가 raw code와 replay 경계를 보호한다.

**Tech Stack:** Kotlin 2.4.0 compiler with repository language/API level 2.3, Java 25, Spring Boot
4.1.0 MVC/Tomcat, Exposed 1.3.0 JDBC, PostgreSQL, HikariCP, Redis/Lettuce, Bucket4j, Bluetape leader,
Micrometer, JUnit 5, Kluent, MockK, live WebTestClient, `bluetape4k-dependencies:1.3.1`,
`bluetape4k-exposed-jdbc`, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-testcontainers`,
`bluetape4k-virtualthread-jdk25`, Kover 0.9.8 report-only XML.

---

## 구현 기준

- 설계 권위는
  `docs/superpowers/specs/2026-07-20-issue-537-pre-generated-voucher-pool-design.md`다.
- module은 `commerce/pre-generated-voucher-pool`, Gradle project는
  `:commerce-pre-generated-voucher-pool`, package는
  `io.bluetape4k.workshop.commerce.voucherpool`이다.
- root `platform(libs.bluetape4k.dependencies)`만 Bluetape version authority로 사용한다. 개별 BOM과
  명시 Bluetape module version은 추가하지 않는다. 승인 설계가 요구한 report-only Kover plugin만
  `gradle/libs.versions.toml`에 중앙 catalog와 같은 0.9.8로 등록하며 새 runtime dependency는 추가하지
  않는다.
- `promotion-voucher-campaign`의 Gradle/Testcontainers/HTTP/CI 패턴은 차용하지만 production source를
  복사하거나 cross-module runtime dependency로 연결하지 않는다.
- production operational class는 `KLogging`을 사용하고 raw code, user/device/IP, idempotency key,
  digest, secret과 exception message를 기록하지 않는다.
- PostgreSQL correctness test는 `PostgreSQLServer.Launcher.postgres`, Redis test는
  `RedisServer.Launcher.redis`를 사용한다. H2와 raw `GenericContainer`는 사용하지 않는다.
- live HTTP test는 `RANDOM_PORT + WebTestClient.bindToServer(JdkClientHttpConnector)`와 공통
  `HTTP_TIMEOUT = 60.seconds`를 사용한다. MockMvc와 in-process WebTestClient binding은 사용하지 않는다.
- 모든 container-backed Gradle command는 `--max-workers=1`로 순차 실행한다.
- 테스트는 JUnit 5 + Kluent/`bluetape4k-assertions`를 사용하고 PostgreSQL 경합에는 필요한 경우
  `MultithreadingTester`와 explicit transaction barrier를 함께 사용한다.
- public DTO와 configuration properties는 English KDoc, validation, `Serializable`, explicit
  `serialVersionUID`를 가진다. production Kotlin에 `!!`, deprecated
  `SqlExpressionBuilder.eq`, `println`과 raw payload `toString()`을 추가하지 않는다.
- Kover는 module-local report-only XML로 적용한다. hard threshold는 추가하지 않으며
  `:commerce-pre-generated-voucher-pool:koverXmlReport`와 XML artifact 존재를 local/Examples CI에서
  검증한다. Codecov uploader를 새로 도입하지 않고 XML artifact visibility를 보존한다.

## 파일 구조와 책임

### Production

- `commerce/pre-generated-voucher-pool/build.gradle.kts`: Java 25, versionless dependencies,
  `test`, `migrationCompatibilityTest`, `stressTest` task
- `gradle/libs.versions.toml`: Kover 0.9.8 plugin alias only
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/PreGeneratedVoucherPoolApplication.kt`: application entrypoint
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain/VoucherPoolModels.kt`: campaign/batch/entry/reservation/allocation 상태와 DTO
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain/VoucherPoolPolicies.kt`: validation, transition, TTL와 error policy
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolTables.kt`: Exposed table mapping
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRecords.kt`: immutable persistence records
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepository.kt`: canonical lock/CAS와 bounded query
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolJdbcExecutor.kt`: transaction deadline와 permit wrapper
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherDigestService.kt`: purpose-separated canonical HMAC
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherEnvelopeCrypto.kt`: per-entry AES-GCM envelope encryption
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolFingerprint.kt`: closed DTO fingerprint
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolIdempotencyRepository.kt`: owner lease, descriptor, tombstone
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/CampaignBatchCommandService.kt`: campaign/batch create/policy/import/generate
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/ReservationService.kt`: reserve/release/expiry
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/AllocationService.kt`: allocate/reveal/replacement
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/RedemptionService.kt`: redeem/revoke race
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkerRepository.kt`: claim/cursor/checkpoint CAS
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkers.kt`: expiry/revoke/reconciliation/purge triggers
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/DatabasePermitGate.kt`: foreground/worker/SSE permit lanes
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolAdmissionGate.kt`: Redis/Bucket4j와 node-local hard cap
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolRedisSignals.kt`: advisory Bloom/leader signals
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/query/VoucherPoolQueryService.kt`: owner/operator snapshots와 diagnostics
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/CustomerVoucherPoolController.kt`: customer routes
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorVoucherPoolController.kt`: operator routes
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolHttpCommandExecutor.kt`: idempotency owner와 HTTP descriptor 조정
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolExceptionHandler.kt`: stable HTTP vocabulary
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorAccessFilter.kt`: loopback/Host/Origin/secret/tenant guard
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolEventStream.kt`: snapshot-first bounded SSE
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolConfiguration.kt`: properties, datasource, virtual executor
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRedisConfiguration.kt`: optional Redis/leader lifecycle
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolLifecycle.kt`: graceful startup/shutdown
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMetrics.kt`: low-cardinality metrics
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolHealthIndicators.kt`: liveness/readiness/degraded state
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunner.kt`: checksum/advisory-lock migration
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetention.kt`: dependency-ordered purge
- `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/fixture/VoucherPoolFixtures.kt`: test-profile deterministic scenarios
- `commerce/pre-generated-voucher-pool/src/main/resources/db/migration/V001__voucher_pool.sql`: schema/index/check
- `commerce/pre-generated-voucher-pool/src/main/resources/application.yml`: bounded runtime defaults
- `commerce/pre-generated-voucher-pool/src/main/resources/static/{index.html,app.js,styles.css}`: browser console

### Test and repository integration

- `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/AbstractVoucherPoolIntegrationTest.kt`
- domain, security, persistence, idempotency, application, worker, admission, web, lifecycle, migration,
  backup/restore와 stress test files named in each task
- `commerce/pre-generated-voucher-pool/{README.md,README.ko.md}`
- `docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-{architecture,sequence}-01.{svg,png}`
- `scripts/validate-voucher-pool-runbook.mjs`
- `commerce/{README.md,README.ko.md}`, root `{README.md,README.ko.md}`, `AGENTS.md`
- `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`

## Acceptance traceability

| Acceptance criterion | Primary tasks | Fresh proof |
|---|---|---|
| entry당 concurrent winner 1명 | 3, 7, 13 | PostgreSQL barrier race + stress invariant |
| reservation/allocation idempotent consumption | 5, 7 | retry storm + descriptor/tombstone tests |
| redeem/revoke 단일 outcome | 7, 8 | forced race + audit policy version |
| expiry capacity 반환과 code non-reuse | 4, 7, 8 | expiry/replacement/dedup integration |
| replay-safe bounded import/generation | 3, 6 | chunk crash/resume + 10,000-entry finalize |
| secret/raw identity redaction | 4, 9, 10 | log/metric/audit/operator forbidden assertions |
| browser workflow 분리 | 10, 11 | live browser contract + accessibility smoke |
| Redis/Bloom/leader advisory boundary | 8, 9 | outage/degradation + PostgreSQL outcome |
| Java 25/Hikari/permit/deadline lifecycle | 1, 9, 13 | runtime contract + stress hard gates |
| docs/diagram/KDoc/repository registration | 14 | parity, validator, actionlint, stale-check |

## Risk prediction and rollback

| Risk | Signal | Mitigation and rerun point | Rollback |
|---|---|---|---|
| campaign shared lock가 exclusive queue로 변질 | Hikari pending, lock wait, throughput collapse | Task 7 barrier test와 Task 13 two-run stress 재실행 | foreground repository commit revert |
| worker reverse lock/deadlock | PostgreSQL deadlock, counter drift | candidate ID 선조회와 canonical relock, Task 8 forced race | worker task commit revert |
| reveal commit 뒤 response 유실 | duplicate raw code 또는 second pool consumption | safe descriptor + one replacement root test | reveal/replacement commit revert |
| tombstone key 누락 restore | post-purge retry가 새 effect 생성 | Task 12 key-manifest preflight와 `410` smoke | destructive down migration 금지; compatible previous binary rollback, 아니면 key manifest 포함 backup restore 후 roll-forward |
| shared schema test isolation | relation missing 또는 migration history drift | Base58 schema와 `currentSchema`, full module clean rerun | test-schema commit revert |
| Redis 장애가 terminal reject 생성 | outcome이 healthy profile과 다름 | node-local cap + always-on JDBC permit, outage profile | admission commit revert |
| module registration 누락 | project count/README/workflow artifact drift | Task 14 `105` count, actionlint와 sequential Commerce lane | registration-only commit revert |

### Task 1: Module, Java 25 runtime, permit and test harness

**Complexity:** M
**Depends on:** approved spec
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Create: `commerce/pre-generated-voucher-pool/build.gradle.kts`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/PreGeneratedVoucherPoolApplication.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolConfiguration.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/DatabasePermitGate.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolJdbcExecutor.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/resources/application.yml`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/RuntimeContractTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/DatabasePermitGateTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/AbstractVoucherPoolIntegrationTest.kt`

- [ ] **Step 1: missing module RED를 관찰한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:tasks
```

Expected: project directory가 없으므로 task lookup FAIL.

- [ ] **Step 2: Java 25와 versionless dependency build를 작성한다**

`build.gradle.kts`는 기존 campaign module의 task wiring만 차용하고 다음 핵심 계약을 둔다.

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}
springBoot {
    mainClass.set("io.bluetape4k.workshop.commerce.voucherpool.PreGeneratedVoucherPoolApplicationKt")
}
kover {
    reports {
        total {
            xml { onCheck = false }
        }
    }
}
configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}
```

Dependencies are the existing aliases for core/logging/jackson3/idgenerators/micrometer/virtualthread,
Exposed JDBC, Bucket4j/Lettuce/leader, Hikari/PostgreSQL, Spring MVC/Actuator/validation and the existing
JUnit/Kluent/Testcontainers/MockK test stack. Add only `kover = "0.9.8"` under `[versions]` and
`kover = { id = "org.jetbrains.kotlinx.kover", version.ref = "kover" }` under `[plugins]`.

Keep all tests under `src/test`; create custom `Test` tasks over the same compiled test output and exclude
their tags from default `test`:

```kotlin
tasks.test {
    useJUnitPlatform { excludeTags("stress", "migration-compatibility") }
}

fun Test.failOnZeroTests() {
    var executed = 0L
    afterTest { _, _ -> executed += 1 }
    doLast { check(executed > 0) { "$name discovered zero tests" } }
}

val migrationCompatibilityTest by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("migration-compatibility") }
    failOnZeroTests()
}

val stressTest by tasks.registering(Test::class) {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("stress") }
    failOnZeroTests()
}
```

Migration compatibility classes use `@Tag("migration-compatibility")`; stress classes use `@Tag("stress")`.
Verification asserts non-empty XML under each custom task's test-result directory. Container-backed
executions always use `--max-workers=1`.

- [ ] **Step 3: runtime and permit RED tests를 작성한다**

```kotlin
@Test
fun `runtime uses Java 25 virtual threads and excludes the JDK21 provider`() {
    Runtime.version().feature() shouldBeEqualTo 25
    val thread = Thread.ofVirtual().start { }
    thread.join()
    thread.isVirtual.shouldBeTrue()
}

@Test
fun `foreground timeout releases its permit`() {
    val gate = DatabasePermitGate(
        hikariMaximumPoolSize = 3,
        configs = mapOf(
            PermitLane.FOREGROUND to PermitLaneConfig(1, 25.milliseconds),
            PermitLane.WORKER to PermitLaneConfig(1, 1.seconds),
            PermitLane.SSE to PermitLaneConfig(1, 1.seconds),
        ),
    )
    gate.withForegroundPermit { invoking { gate.withForegroundPermit { Unit } } shouldThrow PoolBusyException::class }
    gate.snapshot().foregroundInUse shouldBeEqualTo 0
}

@Test
fun `default lane budgets fit Hikari and nested acquisition fails immediately`() {
    val gate = DatabasePermitGate.default(hikariMaximumPoolSize = 16)
    gate.snapshot().capacities shouldBeEqualTo mapOf(
        PermitLane.FOREGROUND to 12,
        PermitLane.WORKER to 1,
        PermitLane.SSE to 3,
    )
    gate.withForegroundPermit {
        invoking { gate.withSsePermit { Unit } } shouldThrow NestedPermitException::class
    }
}
```

- [ ] **Step 4: minimal runtime/permit implementation을 작성한다**

```kotlin
enum class PermitLane { FOREGROUND, WORKER, SSE }

data class PermitLaneConfig(val capacity: Int, val wait: kotlin.time.Duration)

data class PermitSnapshot(
    val foregroundInUse: Int,
    val workerInUse: Int,
    val sseInUse: Int,
    val capacities: Map<PermitLane, Int>,
)

class DatabasePermitGate(
    hikariMaximumPoolSize: Int,
    private val configs: Map<PermitLane, PermitLaneConfig>,
) {
    init {
        require(configs.keys == PermitLane.entries.toSet())
        require(configs.values.sumOf { it.capacity } <= hikariMaximumPoolSize)
        require(configs.values.all { it.capacity > 0 && it.wait.isPositive() })
    }

    private val permits = configs.mapValues { (_, config) -> Semaphore(config.capacity, true) }
    private val activeLane = ThreadLocal<PermitLane?>()

    fun <T> withForegroundPermit(block: () -> T): T = withPermit(PermitLane.FOREGROUND, block)
    fun <T> withWorkerPermit(block: () -> T): T = withPermit(PermitLane.WORKER, block)
    fun <T> withSsePermit(block: () -> T): T = withPermit(PermitLane.SSE, block)

    fun snapshot(): PermitSnapshot = PermitSnapshot(
        foregroundInUse = inUse(PermitLane.FOREGROUND),
        workerInUse = inUse(PermitLane.WORKER),
        sseInUse = inUse(PermitLane.SSE),
        capacities = configs.mapValues { it.value.capacity },
    )

    private fun <T> withPermit(lane: PermitLane, block: () -> T): T {
        if (activeLane.get() != null) throw NestedPermitException(activeLane.get(), lane)
        val semaphore = permits.getValue(lane)
        val wait = configs.getValue(lane).wait
        if (!semaphore.tryAcquire(wait.inWholeNanoseconds, TimeUnit.NANOSECONDS)) {
            throw PoolBusyException(lane)
        }
        activeLane.set(lane)
        return try {
            block()
        } finally {
            activeLane.remove()
            semaphore.release()
        }
    }

    private fun inUse(lane: PermitLane): Int =
        configs.getValue(lane).capacity - permits.getValue(lane).availablePermits()
}
```

Use `java.util.concurrent.Semaphore` and `TimeUnit`. `VoucherPoolJdbcExecutor` keeps three independent
budgets: Hikari connection acquisition `2.seconds`, foreground/operator transaction and lock `5.seconds`,
worker chunk `10.seconds`. Timeout metrics and error mapping distinguish acquisition, permit, lock and
transaction deadline; boundary tests assert each duration and release/cancellation behavior. The executor
uses Spring `TransactionTemplate` around Exposed/JDBC work and exposes an explicit `afterCommit` hook; tests
assert transaction synchronization is active and never signal after rollback.

- [ ] **Step 5: targeted GREEN과 module registration을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*RuntimeContractTest' --tests '*DatabasePermitGateTest'
./gradlew :commerce-pre-generated-voucher-pool:tasks --all
```

Expected: tests PASS; `test`, `stressTest`, `migrationCompatibilityTest`, `detekt`, `detektTest`,
`koverXmlReport` visible.

- [ ] **Step 6: Lore commit을 만든다**

```bash
git add gradle/libs.versions.toml commerce/pre-generated-voucher-pool
git commit -m "feat: bound voucher pool runtime capacity" -m "Constraint: Java 25 virtual traffic must not outrun the PostgreSQL pool.\nConfidence: high\nScope-risk: moderate\nTested: RuntimeContractTest and DatabasePermitGateTest.\nNot-tested: Database behavior begins in the next task."
```

**Rollback/rerun:** module-only commit을 revert하고 `./gradlew projects`로 graph를 복구한다.

### Task 2: Domain values, policy and transition matrix

**Complexity:** M
**Depends on:** Task 1
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain/VoucherPoolModels.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain/VoucherPoolPolicies.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain/VoucherPoolPoliciesTest.kt`

- [ ] **Step 1: state and semantic boundary RED tests를 작성한다**

```kotlin
@ParameterizedTest
@CsvSource("DRAFT,ACTIVE,true", "ACTIVE,PAUSED,true", "PAUSED,ACTIVE,true", "REVOKING,ACTIVE,false")
fun `campaign transition matrix is closed`(from: CampaignState, to: CampaignState, allowed: Boolean) {
    CampaignPolicy.canTransition(from, to) shouldBeEqualTo allowed
}

@Test
fun `control characters and oversized voucher codes are rejected`() {
    invoking { CanonicalVoucherCode.of("ABC\u0000DEF") } shouldThrow IllegalArgumentException::class
    invoking { CanonicalVoucherCode.of("A".repeat(257)) } shouldThrow IllegalArgumentException::class
}
```

- [ ] **Step 2: immutable domain types와 stable error를 작성한다**

```kotlin
enum class CampaignState { DRAFT, ACTIVE, PAUSED, REVOKING, REVOKED }
enum class BatchState { STAGING, ACTIVE, PAUSED, REVOKING, EXPIRING, REVOKED, EXPIRED, FAILED_RETRYABLE, FAILED_TERMINAL }
enum class EntryState { AVAILABLE, RESERVED, ALLOCATED, REDEEMED, RELEASED, REVOKED, EXPIRED }
enum class ReservationState { ACTIVE, ALLOCATED, EXPIRED, RELEASED, REVOKED }

data class VoucherPoolPolicy(
    val perUserLimit: Int,
    val reservationTtl: kotlin.time.Duration,
    val allocationTtl: kotlin.time.Duration,
    val replacementAllowance: Int,
) : Serializable {
    companion object {
        @JvmField
        val serialVersionUID: Long = 1L
    }
}

enum class VoucherPoolErrorCode {
    COMMAND_IN_PROGRESS, IDEMPOTENCY_FINGERPRINT_CONFLICT, REPLAY_WINDOW_EXPIRED,
    POOL_BUSY, POOL_EXHAUSTED, USER_LIMIT_REACHED, STALE_REVISION,
    CAMPAIGN_NOT_ACTIVE, CAMPAIGN_PAUSED, CAMPAIGN_REVOKING, CAMPAIGN_REVOKED,
    BATCH_PAUSED, BATCH_EXPIRING, BATCH_REVOKED, BATCH_EXPIRED,
    BATCH_FAILED_RETRYABLE, BATCH_FAILED_TERMINAL,
    RESERVATION_EXPIRED, ALLOCATION_EXPIRED, WRONG_OWNER, SCOPE_NOT_FOUND,
    RATE_LIMITED, BACKEND_TIMEOUT, KEY_MATERIAL_UNAVAILABLE, CIPHERTEXT_INVALID,
    ALREADY_REVEALED, REPLACEMENT_LIMIT_REACHED,
}
```

Use validated factories based on `require*`; keep constructors private where generated copy paths could
bypass validation. Public contracts receive English KDoc and `serialVersionUID`.
Add a parameterized error-catalog test that covers every enum value and asserts its HTTP status,
retryability, descriptor action, tombstone action and caller recovery instruction.

- [ ] **Step 3: domain GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolPoliciesTest'
```

Expected: all transition, TTL, length, Unicode/control and lifetime-limit tests PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/domain
git commit -m "feat: close voucher pool domain transitions" -m "Constraint: Every HTTP and worker path needs one stable state vocabulary.\nConfidence: high\nScope-risk: narrow\nTested: VoucherPoolPoliciesTest.\nNot-tested: Persistence constraints follow in Task 3."
```

### Task 3: PostgreSQL schema, records and canonical locking repository

**Complexity:** H
**Depends on:** Tasks 1-2
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolTables.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRecords.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepository.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/resources/db/migration/V001__voucher_pool.sql`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunner.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence/VoucherPoolRepositoryIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunnerTest.kt`

- [ ] **Step 1: physical constraint and lock-order RED tests를 작성한다**

```kotlin
@Test
fun `stable digest is unique across campaigns`() = withPostgres {
    insertDedup(tenant = TENANT, campaign = "c1", digest = DIGEST)
    invoking { insertDedup(tenant = TENANT, campaign = "c2", digest = DIGEST) } shouldThrow SQLException::class
}

@Test
fun `foreground campaign guards are compatible shared locks`() = withTwoTransactions {
    first { lockCampaignForShare(CAMPAIGN) }
    second { lockCampaignForShare(CAMPAIGN) }
    bothCompletedWithin(500.milliseconds).shouldBeTrue()
    concurrentSharedLockHolders(CAMPAIGN) shouldBeGreaterOrEqualTo 2
    exclusiveWaiters(CAMPAIGN) shouldBeEqualTo 0
}
```

- [ ] **Step 2: migration을 작성한다**

`V001__voucher_pool.sql` creates campaign, batch, entry, reservation, user-limit, allocation, dedup,
idempotency, command tombstone, audit, reconciliation inbox, quarantine and worker-claim tables. Required
constraints include:

```sql
ALTER TABLE voucher_pool_entries
  ADD CONSTRAINT voucher_pool_entry_cipher_contract CHECK (
    (revealed_at IS NULL AND code_ciphertext IS NOT NULL AND wrapped_dek IS NOT NULL)
    OR
    (revealed_at IS NOT NULL AND code_ciphertext IS NULL AND wrapped_dek IS NULL)
  );
CREATE UNIQUE INDEX uq_voucher_pool_dedup ON voucher_pool_code_dedup(tenant_id, stable_dedup_digest);
CREATE UNIQUE INDEX uq_voucher_pool_reservation_entry ON voucher_pool_reservations(tenant_id, entry_id);
CREATE UNIQUE INDEX uq_voucher_pool_allocation_entry ON voucher_pool_allocations(tenant_id, entry_id);
CREATE INDEX ix_voucher_pool_available ON voucher_pool_entries(tenant_id, campaign_id, batch_id, source_ordinal)
  WHERE state = 'AVAILABLE' AND quarantined_at IS NULL;
```

All FK, non-negative counters, revision, nonce uniqueness, active worker claim and cursor indexes from the
spec are explicit. Migration runner stores checksum and uses a PostgreSQL advisory startup lock.

- [ ] **Step 3: repository lock API를 작성한다**

```kotlin
interface VoucherPoolRepository {
    fun lockCampaignForShare(tenantId: String, campaignId: UUID): CampaignRecord
    fun lockBatchForShare(tenantId: String, batchId: UUID): BatchRecord
    fun lockUserLimit(tenantId: String, campaignId: UUID, userDigest: ByteArray): UserLimitRecord
    fun selectAvailableEntrySkipLocked(tenantId: String, campaignId: UUID): EntryRecord?
    fun lockCanonicalChain(candidate: WorkerCandidate): LockedWorkerChain?
    fun appendAudit(event: VoucherPoolAuditRecord)
}
```

Use raw JDBC only for PostgreSQL lock syntax unavailable through clear Exposed DSL. Exposed writes extract
colliding values before `insert`/`update`; operators use current top-level imports.

- [ ] **Step 4: repository GREEN과 query plan을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolRepositoryIntegrationTest' --tests '*VoucherPoolMigrationRunnerTest' --max-workers=1
```

Expected: physical checks, cross-campaign dedup, barrier-proven concurrent shared holders, zero accidental
exclusive waiter, exclusive policy update ordering and `SKIP LOCKED` progress PASS. For allocation candidate,
worker candidate and operator pool-depth queries, load 10,000 entries and persist
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` artifacts; reject sequential scans of the entry table and assert
the following fixed ceilings:

| Query | Returned rows | Heap fetches | Shared hit + read blocks | Allowed scan nodes |
|---|---:|---:|---:|---|
| allocation candidate | 1 | 4 | 128 | `Limit`, `LockRows`, `Index Scan` |
| worker candidate chunk | 100 | 200 | 512 | `Limit`, `Index Scan` |
| operator pool depth | 4 | 64 | 1,024 | `Aggregate`, `Index Only Scan`, bounded `Bitmap Heap/Index Scan` |

- [ ] **Step 5: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunner.kt commerce/pre-generated-voucher-pool/src/main/resources/db/migration commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/persistence commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationRunnerTest.kt
git commit -m "feat: make PostgreSQL voucher pool authority explicit" -m "Constraint: Allocation correctness depends on physical constraints and one lock order.\nConfidence: high\nScope-risk: broad\nTested: Repository and migration integration tests.\nNot-tested: Encryption and HTTP behavior follow."
```

### Task 4: Purpose-separated digest, envelope encryption and quarantine

**Complexity:** H
**Depends on:** Task 3
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherDigestService.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherEnvelopeCrypto.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherDigestServiceTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherEnvelopeCryptoTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security/VoucherCryptoQuarantineIntegrationTest.kt`

- [ ] **Step 1: cryptographic RED tests를 작성한다**

```kotlin
@Test
fun `stable dedup ignores campaign and key rotation`() {
    val first = service.stableDedup(TENANT, CanonicalVoucherCode.of("POOL-001"))
    service.rotateVerificationKey()
    val second = service.stableDedup(TENANT, CanonicalVoucherCode.of("POOL-001"))
    first shouldBeEqualTo second
}

@Test
fun `repository crypto erasure destroys encrypted material`() = withPostgres {
    val entry = insertEncryptedEntry("POOL-002")
    decryptAndEraseCiphertext(entry)
    loadEntry(entry).apply {
        revealedAt.shouldNotBeNull()
        codeCiphertext.shouldBeNull()
        wrappedDek.shouldBeNull()
    }
}
```

- [ ] **Step 2: digest and AES-GCM implementation을 작성한다**

```kotlin
enum class DigestPurpose { STABLE_DEDUP, VERIFICATION, USER_IDENTITY, COMMAND_TOMBSTONE, REDIS_SIGNAL, AUDIT }

data class EncryptedVoucherCode(
    val ciphertext: ByteArray,
    val codeNonce: ByteArray,
    val wrappedDek: ByteArray,
    val wrapNonce: ByteArray,
    val kekVersion: String,
)

interface VoucherEnvelopeCrypto {
    fun encrypt(entryIdentity: EntryIdentity, code: CanonicalVoucherCode): EncryptedVoucherCode
    fun decrypt(entryIdentity: EntryIdentity, encrypted: EncryptedVoucherCode): CanonicalVoucherCode
}
```

Use `SecureRandom`, 96-bit unique nonces, `AES/GCM/NoPadding`, immutable AAD and a random entry DEK. Decrypt
recomputes stable dedup. Missing key is fail-closed; tag/digest failure inserts a quarantine row without raw
payload.

- [ ] **Step 3: crypto GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherDigestServiceTest' --tests '*VoucherEnvelopeCryptoTest' --tests '*VoucherCryptoQuarantineIntegrationTest' --max-workers=1
```

Expected: round trip, nonce uniqueness, rotation, unknown key, tag failure, row swap, quarantine and
ciphertext erasure tests PASS. The customer reveal transaction and public result are intentionally deferred
to Task 7, which owns the allocation lifecycle.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/security
git commit -m "feat: protect voucher codes until one-time reveal" -m "Constraint: Raw pool codes must survive import without becoming queryable secrets.\nRejected: Plaintext storage | It leaks through backups and operator queries.\nConfidence: high\nScope-risk: broad\nTested: Digest, envelope crypto, and quarantine tests.\nNot-tested: HTTP reveal begins in Task 7."
```

### Task 5: Idempotency owner lease, descriptor and tenant-lifetime tombstone

**Complexity:** H
**Depends on:** Tasks 3-4
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolFingerprint.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolIdempotencyRepository.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolFingerprintTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency/VoucherPoolIdempotencyIntegrationTest.kt`

- [ ] **Step 1: replay-fence RED tests를 작성한다**

```kotlin
@Test
fun `descriptor purge keeps same key from executing again`() = withPostgres {
    val owner = acquire(SCOPE, IDEMPOTENCY_KEY, FINGERPRINT) as IdempotencyDecision.Execute
    inTransaction {
        finalize(owner.ownerToken, DESCRIPTOR, EffectReference(EFFECT_ID))
    }
    purgeDescriptors(limit = 1)
    acquire(SCOPE, IDEMPOTENCY_KEY, FINGERPRINT).apply {
        this shouldBeInstanceOf IdempotencyDecision.Expired::class
        (this as IdempotencyDecision.Expired).effectId shouldBeEqualTo EFFECT_ID
    }
}
```

- [ ] **Step 2: repository contract를 구현한다**

```kotlin
sealed interface IdempotencyDecision {
    data class Execute(val ownerToken: String) : IdempotencyDecision
    data class Replay(val descriptor: SafeResponseDescriptor) : IdempotencyDecision
    data class Expired(val effectId: UUID?, val terminalCode: VoucherPoolErrorCode?) : IdempotencyDecision
    data object InProgress : IdempotencyDecision
    data object FingerprintConflict : IdempotencyDecision
}

interface VoucherPoolIdempotencyRepository {
    fun acquire(scope: CommandScope, rawKey: String, fingerprint: ByteArray): IdempotencyDecision
    fun finalize(ownerToken: String, descriptor: SafeResponseDescriptor, effect: EffectReference)
    fun releaseRetryable(scope: CommandScope, ownerToken: String)
    fun purgeDescriptors(limit: Int): Int
}
```

Application services call `finalize` inside the same `VoucherPoolJdbcExecutor` transaction that writes their
business effect; Task 5 owns lease/descriptor/tombstone persistence without inventing a pre-Task-6 business
command. A test-only effect marker proves rollback atomicity here, while Task 7 repeats commit-loss injection
with real reservation/allocation effects. Tombstone lookup uses the tenant-lifetime command key/version;
purge refuses descriptor deletion without its tombstone.

- [ ] **Step 3: idempotency GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolFingerprintTest' --tests '*VoucherPoolIdempotencyIntegrationTest' --max-workers=1
```

Expected: same/same replay, same/different conflict, in-progress, stale takeover, retryable release,
commit-loss and concurrent purge/retry tests PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/idempotency
git commit -m "feat: retain voucher command replay fences" -m "Constraint: Descriptor retention is shorter than effect safety.\nConfidence: high\nScope-risk: broad\nDirective: Never purge a descriptor without a durable tombstone.\nTested: Fingerprint and idempotency integration tests.\nNot-tested: Route mappings follow in Task 10."
```

### Task 6: Campaign, batch import and generation checkpoints

**Complexity:** H
**Depends on:** Tasks 2-5
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/CampaignBatchCommandService.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/CampaignBatchCommandServiceTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/VoucherBatchIngestIntegrationTest.kt`

- [ ] **Step 1: bounded ingest RED tests를 작성한다**

```kotlin
@Test
fun `partial import resumes from the last committed ordinal`() = withPostgres {
    val batch = createDraftBatch(expectedCount = 10_000)
    importChunk(batch, firstOrdinal = 0, codes = codes(100))
    simulateProcessRestart()
    importChunk(batch, firstOrdinal = 100, codes = codes(100, offset = 100))
    batchSnapshot(batch).nextOrdinal shouldBeEqualTo 200
}
```

- [ ] **Step 2: campaign/batch command implementation을 작성한다**

```kotlin
interface CampaignBatchCommandService {
    fun createCampaign(command: CreateCampaignCommand): CampaignSnapshot
    fun updatePolicy(command: UpdateCampaignPolicyCommand): CampaignSnapshot
    fun activateCampaign(command: CampaignRevisionCommand): CampaignSnapshot
    fun createImportBatch(command: CreateImportBatchCommand): BatchSnapshot
    fun importChunk(command: ImportChunkCommand): BatchSnapshot
    fun generateChunk(command: GenerateChunkCommand): BatchSnapshot
    fun activateBatch(command: BatchRevisionCommand): BatchSnapshot
}
```

Parsing, canonical validation and encryption finish in bounded buffers before the transaction. Each chunk is
`<=500`; source ordinal, checkpoint digest and accepted/rejected count are committed atomically. Activation
requires exact expected count, no gap and no unresolved failure.

- [ ] **Step 3: ingest GREEN과 10,000-entry finalize를 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*CampaignBatchCommandServiceTest' --tests '*VoucherBatchIngestIntegrationTest' --max-workers=1
```

Expected: import/generation validation, duplicate ordinal/digest, crash/resume, rollback/regeneration,
cross-campaign duplicate reject and activation gate PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/CampaignBatchCommandService.kt commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application
git commit -m "feat: checkpoint voucher pool ingestion" -m "Constraint: Large imports must resume without one long transaction.\nConfidence: high\nScope-risk: broad\nTested: Campaign command and batch ingest integration tests.\nNot-tested: Customer lifecycle follows in Task 7."
```

### Task 7: Reservation, allocation, one-time reveal, replacement and redemption

**Complexity:** H
**Depends on:** Tasks 2-6
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/ReservationService.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/AllocationService.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/RedemptionService.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/VoucherPoolLifecycleIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application/VoucherPoolConcurrencyIntegrationTest.kt`

- [ ] **Step 1: lifecycle and race RED tests를 작성한다**

```kotlin
@Test
fun `concurrent reservations select distinct entries`() = runVirtualRace(callers = 64) {
    reserve(CAMPAIGN, uniqueUser())
}.also { results ->
    results.filter { it.isSuccess }.map { it.entryId }.distinct().size shouldBeEqualTo results.count { it.isSuccess }
}

@Test
fun `lost reveal response permits exactly one replacement`() = withPostgres {
    val allocation = reserveAndAllocate()
    revealAndDropResponse(allocation)
    replace(allocation).state shouldBeEqualTo ReservationState.ACTIVE
    invoking { replace(allocation) } shouldThrow ReplacementLimitReachedException::class
}

@Test
fun `effect descriptor and tombstone rollback or commit together`() = withPostgres {
    injectCommitLossAfterAllocationEffect()
    retryAllocation(SAME_KEY).assertSingleEffectAndSafeReplay()
}

@Test
fun `same campaign reservations retain concurrent shared progress`() = withPostgresBarrier {
    val results = runCompleteReservations(
        campaign = CAMPAIGN,
        batch = BATCH,
        uniqueUsers = 4,
        barrier = BarrierPoint.AFTER_CAMPAIGN_AND_BATCH_GUARDS,
    )
    concurrentSharedLockHolders(CAMPAIGN) shouldBeGreaterOrEqualTo 4
    concurrentSharedLockHolders(BATCH) shouldBeGreaterOrEqualTo 4
    exclusiveWaiters(CAMPAIGN) shouldBeEqualTo 0
    exclusiveWaiters(BATCH) shouldBeEqualTo 0
    results.completedWithin(2.seconds).shouldBeTrue()
    results.map { it.entryId }.distinct().size shouldBeEqualTo 4
}
```

- [ ] **Step 2: services를 canonical lock order로 구현한다**

```kotlin
interface ReservationService {
    fun reserve(command: ReserveVoucherCommand): ReservationSnapshot
    fun release(command: ReleaseReservationCommand): ReservationSnapshot
}

interface AllocationService {
    fun allocate(command: AllocateVoucherCommand): AllocationSnapshot
    fun reveal(command: RevealVoucherCommand): RevealResult
    fun replaceLostReveal(command: ReplaceLostRevealCommand): ReservationSnapshot
}

interface RedemptionService {
    fun redeem(command: RedeemVoucherCommand): AllocationSnapshot
    fun revoke(command: RevokeAllocationCommand): AllocationSnapshot
}
```

Each transaction locks campaign/batch `FOR SHARE`, then user-limit, reservation and entry. `SKIP LOCKED`
empty distinguishes contention from true exhaustion. Reveal deletes ciphertext in the same transaction and
returns raw code only after commit; duplicate reveal returns safe `ALREADY_REVEALED` without code.

- [ ] **Step 3: lifecycle GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolLifecycleIntegrationTest' --tests '*VoucherPoolConcurrencyIntegrationTest' --max-workers=1
```

Expected: reservation/user-limit counters, same-campaign shared-lock throughput, pause/revoke races,
expiry/reuse, lost reveal replacement, redemption/revoke and leak/deadlock assertions PASS.
`REPLACEMENT_LIMIT_REACHED` is a stable terminal response with no additional pool consumption and a safe
request ID/operator-escalation action.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/application
git commit -m "feat: preserve one-time voucher allocation" -m "Constraint: Allocation and reveal must converge under retry and policy races.\nConfidence: high\nScope-risk: broad\nTested: Lifecycle and concurrency integration tests.\nNot-tested: Background terminalization follows in Task 8."
```

### Task 8: Durable workers, campaign/batch revoke, expiry and reconciliation

**Complexity:** H
**Depends on:** Tasks 3, 6-7
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkerRepository.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkers.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkerRepositoryTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker/VoucherPoolWorkerIntegrationTest.kt`

- [ ] **Step 1: worker race RED tests를 작성한다**

```kotlin
@Test
fun `worker and replacement preserve lock order and exact counters`() = withBarrier {
    race({ runExpiryChunk(BATCH) }, { replaceLostReveal(ALLOCATION) })
    deadlocks() shouldBeEqualTo 0
    reconcileUserLimit(USER).drift shouldBeEqualTo 0
}

@Test
fun `failed chunks back off then poison without stale finalize`() = withDatabaseClock {
    repeat(5) { attempt ->
        failChunkAndAdvanceTo(nextBackoff = minOf(1.shl(attempt), 30).seconds)
    }
    claimSnapshot().apply {
        state shouldBeEqualTo WorkerClaimState.POISONED
        attempt shouldBeEqualTo 5
        nextAction.shouldNotBeBlank()
    }
    invoking { finalizeWithStaleRevision() } shouldThrow StaleWorkerClaimException::class
}

@Test
fun `cancellation commits current chunk then releases claim`() = withPostgres {
    cancelDuringSecondChunk()
    committedChunkCount() shouldBeEqualTo 1
    claimSnapshot().owner.shouldBeNull()
}
```

- [ ] **Step 2: claim/cursor worker를 구현한다**

```kotlin
data class WorkerClaim(
    val owner: String,
    val revision: Long,
    val claimUntil: Instant,
    val cursor: Long,
    val attempt: Int,
    val nextAttemptAt: Instant,
    val runDeadline: Instant,
)

interface VoucherPoolWorkerRepository {
    fun claim(kind: WorkerKind, scopeId: UUID, owner: String): WorkerClaim?
    fun nextCandidates(claim: WorkerClaim, limit: Int = 100): List<WorkerCandidate>
    fun commitChunk(claim: WorkerClaim, results: List<WorkerResult>): WorkerClaim
    fun finalize(claim: WorkerClaim): WorkerOutcome
}
```

Candidate IDs are read without retained entry locks. Each chunk reacquires campaign, batch, sorted user-limit,
reservation and entry locks; final entry uses `SKIP LOCKED` or expected revision CAS. Claim, checkpoint and
finalize reject stale owners. Leader and operator use the same claim API. Defaults are claim/renewal
`15.seconds`, chunk deadline `10.seconds`, run deadline `30.seconds`, max attempt `5`, and bounded exponential
backoff `1..30.seconds`. Every successful checkpoint renews the claim using database time and owner/revision
CAS. Renewal failure forbids the next chunk; cancellation finishes or rolls back the current chunk, releases
the claim, and exposes a redacted reason/next action. Revoke chunks are `<=100`; reconciliation chunks are
`<=50`.

- [ ] **Step 3: worker GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolWorkerRepositoryTest' --tests '*VoucherPoolWorkerIntegrationTest' --max-workers=1
```

Expected: duplicate claim, lease renewal/takeover, restart, cursor wrap-around, campaign `REVOKING`, batch
expiry/revoke, worker-versus-allocation/replacement, 10/30-second deadline, 15-second renewal, 1..30-second
backoff, max-attempt `POISONED`, cancellation release, healthy-run no-takeover, stale-finalize rejection and
reconciliation drift PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/worker
git commit -m "feat: recover voucher pool workers from checkpoints" -m "Constraint: Duplicate triggers and restarts must not reverse lock order.\nConfidence: high\nScope-risk: broad\nTested: Worker repository and forced-race integration tests.\nNot-tested: External admission and health follow in Task 9."
```

### Task 9: Redis admission, leader trigger, metrics, health and lifecycle

**Complexity:** H
**Depends on:** Tasks 1, 7-8
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolAdmissionGate.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolRedisSignals.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRedisConfiguration.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolLifecycle.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMetrics.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolHealthIndicators.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/VoucherPoolAdmissionGateTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission/LettuceVoucherPoolAdmissionIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolHealthIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolLifecycleIntegrationTest.kt`

- [ ] **Step 1: degraded continuity RED tests를 작성한다**

```kotlin
@Test
fun `Redis outage keeps PostgreSQL outcome authoritative`() {
    stopRedis()
    reserveThroughLiveHttp().status shouldBeEqualTo HttpStatus.CREATED
    readiness().components.getValue("redis").status shouldBeEqualTo "DEGRADED"
}

@Test
fun `management client tolerates CI readiness delay`() {
    managementWebTestClient.responseTimeout shouldBeEqualTo HTTP_TIMEOUT
    HTTP_TIMEOUT shouldBeEqualTo 60.seconds
}

@ParameterizedTest
@MethodSource("healthMatrix")
fun `health state follows authoritative component matrix`(failure: FailureMode, expected: HealthState) {
    activateFailure(failure)
    readiness().state shouldBeEqualTo expected
    liveness().state shouldBeEqualTo HealthState.UP
    readiness().reason.shouldBeIn(ALLOWED_REDACTED_REASONS)
}

@Test
fun `Redis outage keeps operator auth hard cap and uniform timing`() {
    stopRedis()
    repeat(OPERATOR_AUTH_LIMIT_PER_MINUTE) { invalidOperatorSecret().expectStatus().isNotFound }
    invalidOperatorSecret().expectStatus().isEqualTo(429)
    timingEnvelope(invalidSecret(), unknownTenant(), unknownResource()).spreadMillis shouldBeLessOrEqualTo 25
}

@Test
fun `shutdown follows bounded order and releases cancelled claim`() {
    startWorkerTransaction(duration = 10.seconds)
    shutdown()
    lifecycleEvents() shouldContainInOrder listOf(
        "readiness-down", "commands-and-sse-rejected", "triggers-stopped", "claims-stopped",
        "transaction-drain", "sse-and-poller-closed", "advisory-resources-closed",
        "executor-closed", "datasource-closed",
    )
    shutdownDuration() shouldBeLessOrEqualTo 45.seconds
    staleClaims() shouldBeEmpty()
}
```

- [ ] **Step 2: advisory infrastructure를 구현한다**

```kotlin
interface VoucherPoolAdmissionGate {
    fun admit(namespace: AdmissionNamespace, principalDigest: ByteArray): AdmissionDecision
}

enum class AdmissionDecision { ALLOW, RATE_LIMITED, DEGRADED_ALLOW, DATABASE_BUSY }
```

Use operation-specific Redis/Bucket4j namespaces, node-local hard caps for reveal/redeem/operator-auth,
always-on JDBC permits, bounded timeout and hysteresis. Leader only schedules the Task 8 claim path.
Lifecycle sequence is readiness DOWN → reject new command/SSE → stop scheduler/leader trigger → stop claims
→ drain in-flight transactions for `12.seconds` → close SSE/poller → close leader/Redis/Bloom → close owned
executor → close DataSource. On drain timeout it cancels, verifies rollback and waits another `5.seconds` for
claim release; all other phases are bounded by `5.seconds`, total shutdown by `45.seconds`, and close is
idempotent. Restart tests prove no stale claim survives.

The health matrix fixes PostgreSQL, migration and referenced-key failure as readiness `DOWN`; process-only
liveness stays `UP`; quarantine and Redis/leader loss are redacted `DEGRADED`; recovery work is `RECOVERING`.
Readiness includes allowlisted reason plus last-success/error time. Management binds loopback and exposes only
health/metrics. Metrics enforce bounded tags and cover Hikari active/pending, lane wait, worker backlog/oldest
age/checkpoint, SSE subscriber/reset, pool depth, degraded component, purge and restore. Alert tests fix
warning/recovery windows: Hikari pending `>0/10s`, no-progress `30s`, Redis degraded `30s`, eligible depth
`<10%/60s`, SSE reset `10/min`, quarantine `>=1`, purge lag `24h`, restore failure immediately.

- [ ] **Step 3: infrastructure GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolAdmissionGateTest' --tests '*LettuceVoucherPoolAdmissionIntegrationTest' --tests '*VoucherPoolHealthIntegrationTest' --tests '*VoucherPoolLifecycleIntegrationTest' --max-workers=1
```

Expected: healthy/degraded admission, timeout, readiness/liveness, 60-second management client, graceful
shutdown, component health matrix, management allowlist, Redis healthy/unavailable operator-auth caps,
constant-time uniform failure, exact alert/recovery windows, resource close and bounded metric tags PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/admission commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config
git commit -m "feat: degrade voucher admission without losing authority" -m "Constraint: Redis and leader failures must not change PostgreSQL outcomes.\nConfidence: high\nScope-risk: broad\nTested: Admission, health, and lifecycle integration tests.\nNot-tested: Public HTTP contract follows in Task 10."
```

### Task 10: Customer/operator HTTP, security filter, errors and SSE

**Complexity:** H
**Depends on:** Tasks 5-9
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/query/VoucherPoolQueryService.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/CustomerVoucherPoolController.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorVoucherPoolController.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolHttpCommandExecutor.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolExceptionHandler.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorAccessFilter.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/TenantPrincipalResolver.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolEventStream.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/CustomerVoucherPoolWebIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorVoucherPoolWebIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/OperatorAccessFilterIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/TenantPrincipalAuthorizationIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolEventStreamIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolInputBoundaryIntegrationTest.kt`

- [ ] **Step 1: route matrix RED tests를 작성한다**

```kotlin
@Test
fun `first reveal returns code and duplicate reveal is safe`() {
    postReveal(ALLOCATION, KEY, REVISION).expectStatus().isOk.expectBody<RevealResponse>().value { first ->
        first.code.shouldNotBeNull()
    }
    postReveal(ALLOCATION, KEY, REVISION).expectStatus().isOk.expectBody<RevealResponse>().value { duplicate ->
        duplicate.code.shouldBeNull()
        duplicate.outcome shouldBeEqualTo "ALREADY_REVEALED"
    }
}

@Test
fun `customer scope mismatch and forged identity are uniform not found`() {
    getReservation(asPrincipal = OTHER_PRINCIPAL).expectStatus().isNotFound
    getReservation(asTenant = OTHER_TENANT).expectStatus().isNotFound
    getReservation(extraHeader = "X-Demo-Principal: forged").expectStatus().isNotFound
}
```

- [ ] **Step 2: HTTP contracts를 구현한다**

```kotlin
@RestController
@RequestMapping("/api/v1")
class CustomerVoucherPoolController(
    private val commands: VoucherPoolHttpCommandExecutor,
    private val queries: VoucherPoolQueryService,
) {
    @PostMapping("/campaigns/{campaignId}/reservations")
    fun reserve(
        principal: TenantPrincipal,
        @PathVariable campaignId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @Valid @RequestBody request: ReserveVoucherRequest,
    ): ResponseEntity<ReservationResponse> =
        commands.reserve(principal, campaignId, idempotencyKey, ifNoneMatch, request)

    @PostMapping("/allocations/{allocationId}/code-reveals")
    fun reveal(
        principal: TenantPrincipal,
        @PathVariable allocationId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
    ): ResponseEntity<RevealResponse> =
        commands.reveal(principal, allocationId, idempotencyKey, expectedRevision)
}

@RestController
@RequestMapping("/operator/api/v1")
class OperatorVoucherPoolController(
    private val commands: VoucherPoolHttpCommandExecutor,
    private val queries: VoucherPoolQueryService,
) {
    @PostMapping("/campaigns")
    fun createCampaign(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-None-Match") ifNoneMatch: String,
        @Valid @RequestBody request: CreateCampaignRequest,
    ): ResponseEntity<CampaignResponse> = commands.createCampaign(idempotencyKey, ifNoneMatch, request)

    @PostMapping("/campaigns/{campaignId}/revoke-preview")
    fun previewCampaignRevoke(
        @PathVariable campaignId: UUID,
        @RequestHeader("If-Match") expectedRevision: String,
    ): RevokePreviewResponse = queries.previewCampaignRevoke(campaignId, expectedRevision)

    @PostMapping("/campaigns/{campaignId}/revoke")
    fun revokeCampaign(
        @PathVariable campaignId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestHeader("If-Match") expectedRevision: String,
        @Valid @RequestBody request: RevokeCampaignRequest,
    ): ResponseEntity<WorkerProgressResponse> =
        commands.revokeCampaign(campaignId, idempotencyKey, expectedRevision, request)
}

data class RevokeCampaignRequest(
    val previewToken: String,
    val confirmedCampaignId: UUID,
) : Serializable {
    companion object {
        @JvmField
        val serialVersionUID: Long = 1L
    }
}
```

The black-box route matrix covers every exact design path: customer reserve, reservation GET/allocate,
allocation code-reveal/GET/replacement/redeem/release, snapshot and SSE under `/api/v1`; operator batch
import/generate, chunk, resume, activate, pause, revoke-preview/revoke; campaign create/policy/activate/pause/
resume/revoke-preview/revoke; batch/pool-depth/stuck-reservation/diagnostic queries, reconciliation run,
snapshot and SSE under `/operator/api/v1`. Creates require `If-None-Match: *`; existing-resource mutations
require `If-Match`; commands require idempotency; revoke requires preview token and confirmed identity; async
revoke/reconciliation return `202` progress snapshots.

`TenantPrincipalResolver` accepts only server-established authentication, derives user identity without a
body `userRef`, and applies owner predicates to every customer GET/mutation/SSE. Cross-tenant,
cross-principal and forged demo headers return uniform `404`; public bind without a production auth adapter
fails startup. Operator preview tokens are purpose-signed, single-use and bound to tenant, aggregate ID,
revision, affected-count digest and expiry. Revoke DTO requires the token plus retyped campaign/batch identity;
stale, replayed and cross-resource tokens fail without effect.

Implement every route matrix row with closed request DTO, stable error code and safe descriptor. Reveal adds
`Cache-Control: no-store`,
`Pragma: no-cache`, CSP and `X-Content-Type-Options`. Operator filter enforces loopback bind,
Host/Origin allowlist, constant-time secret, tenant scope and uniform `404`.

- [ ] **Step 3: live HTTP GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*CustomerVoucherPoolWebIntegrationTest' --tests '*OperatorVoucherPoolWebIntegrationTest' --tests '*OperatorAccessFilterIntegrationTest' --tests '*TenantPrincipalAuthorizationIntegrationTest' --tests '*VoucherPoolEventStreamIntegrationTest' --tests '*VoucherPoolInputBoundaryIntegrationTest' --max-workers=1
```

Expected: route status/precondition/error/descriptor matrix, same-origin security, redaction, SSE
snapshot/reset/slow consumer, Unicode/control/size and unknown-key tests PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/query commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web
git commit -m "feat: expose safe voucher pool workflows" -m "Constraint: One-time code disclosure and operator commands require different trust boundaries.\nConfidence: high\nScope-risk: broad\nTested: Live customer, operator, security, input, and SSE tests.\nNot-tested: Browser UX follows in Task 11."
```

### Task 11: Deterministic fixtures and accessible browser console

**Complexity:** M
**Depends on:** Task 10
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/fixture/VoucherPoolFixtures.kt`
- Create: `commerce/pre-generated-voucher-pool/src/main/resources/static/index.html`
- Create: `commerce/pre-generated-voucher-pool/src/main/resources/static/app.js`
- Create: `commerce/pre-generated-voucher-pool/src/main/resources/static/styles.css`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/fixture/VoucherPoolFixturesTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolBrowserContractTest.kt`

- [ ] **Step 1: fixture replay and browser RED tests를 작성한다**

```kotlin
@Test
fun `fixture signal arms only after transaction commit`() {
    configureFixtureAndRollback("reveal-response-loss")
    fixtureState("reveal-response-loss").armed.shouldBeFalse()
}

@Test
fun `browser exposes keyboard focus announcement and destructive confirmation behavior`() {
    browserDom.button("reveal").accessibleName shouldBeEqualTo "Reveal voucher code"
    browserDom.activateByKeyboard("reveal")
    browserDom.focusedElementId shouldBeEqualTo "reveal-confirmation"
    browserDom.cancelRevoke()
    operatorCommands().shouldBeEmpty()
    browserDom.liveRegionText.shouldContain("Reveal cancelled")
}
```

- [ ] **Step 2: deterministic fixture and UI를 구현한다**

Fixtures cover Redis outage, Bloom false positive, reveal response loss, pause/allocation race,
redeem/revoke race, worker takeover, ciphertext quarantine and restore smoke. Fixture mutation occurs only
after commit. Browser separates customer/operator views, never persists raw code, clears it on navigation,
supports keyboard/focus/semantic controls, shows state without color-only meaning, and falls back from SSE to
bounded polling.

```kotlin
@Component
@Profile("test")
class VoucherPoolFixtures(
    private val scenarios: ConcurrentMap<String, FixtureScenario>,
    private val jdbcExecutor: VoucherPoolJdbcExecutor,
) {
    fun armAfterCommit(name: String) {
        require(name in SUPPORTED_SCENARIOS) { "Unsupported fixture scenario" }
        jdbcExecutor.afterCommit {
            scenarios.computeIfPresent(name) { _, current -> current.armOnce() }
        }
    }
}
```

```javascript
const secretState = { revealedCode: null, operatorSecret: null };

export function confirmReveal(run) {
  if (!window.confirm("Reveal this voucher once?")) return;
  run().finally(() => window.addEventListener("pagehide", clearSecrets, { once: true }));
}

export function confirmRevoke(preview, typedIdentity, run) {
  if (typedIdentity !== preview.aggregateIdentity) return announce("Identity does not match");
  if (!window.confirm(`Revoke ${preview.affectedCount} vouchers?`)) return announce("Revoke cancelled");
  run({ previewToken: preview.token, confirmedIdentity: typedIdentity });
}

function clearSecrets() {
  secretState.revealedCode = null;
  secretState.operatorSecret = null;
  document.querySelector("[data-revealed-code]").replaceChildren();
}
```

Operator secret input is masked, `autocomplete="off"`, memory-only and cleared on logout/pagehide. Neither
operator secret nor raw code may enter URL/history, local/session storage, DOM attributes, request logs or
error text. `ALREADY_REVEALED` opens an explicit replacement confirmation; success navigates to the new
reservation. `REPLACEMENT_LIMIT_REACHED` shows a safe request ID and operator-escalation action. Revoke first
renders aggregate identity, revision and affected count from preview; cancel sends no command.

- [ ] **Step 3: browser GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolFixturesTest' --tests '*VoucherPoolBrowserContractTest' --max-workers=1
```

Expected: deterministic replay, rollback isolation, operator/raw-code secret-free storage/history/DOM,
replacement and revoke-preview flows, semantic accessible names, keyboard-only action, post-action focus,
`aria-live` state changes, non-color status and polling fallback assertions PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/fixture commerce/pre-generated-voucher-pool/src/main/resources/static commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/fixture commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/web/VoucherPoolBrowserContractTest.kt
git commit -m "feat: make voucher recovery scenarios reproducible" -m "Constraint: Workshop failure paths need deterministic, accessible demonstrations.\nConfidence: high\nScope-risk: moderate\nTested: Fixture and browser contract tests.\nNot-tested: Packaged restore compatibility follows in Task 12."
```

### Task 12: Retention, migration compatibility, key backup and restore

**Complexity:** H
**Depends on:** Tasks 3-11
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `ecc-springboot-kotlin`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetention.kt`
- Create: `commerce/pre-generated-voucher-pool/src/compatibility/java/io/bluetape4k/workshop/commerce/voucherpool/compatibility/PreviousVoucherPoolBinaryMain.java`
- Create: `commerce/pre-generated-voucher-pool/src/test/resources/compatibility/V000__previous_voucher_pool_schema.sql`
- Create: `commerce/pre-generated-voucher-pool/src/test/resources/compatibility/previous-binary.sha256`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetentionIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolMigrationCompatibilityIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolBackupRestoreIntegrationTest.kt`
- Test: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolKeyRotationIntegrationTest.kt`

- [ ] **Step 1: retention/restore RED tests를 작성한다**

```kotlin
@Test
fun `restore retains command tombstone replay fence`() = restoreBackup {
    purgeFullDescriptor(COMMAND)
    retry(COMMAND).code shouldBeEqualTo VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED
    effectCount(COMMAND) shouldBeEqualTo 1
}

@ParameterizedTest
@CsvSource(
    "DESCRIPTOR,24,hours",
    "TERMINAL_INBOX_CLAIM,7,days",
    "TERMINAL_ENTRY_RESERVATION,30,days",
    "AUDIT,90,days",
)
fun `retention boundary uses database time`(kind: RetentionKind, amount: Long, unit: String) {
    assertRetainedImmediatelyBefore(kind, amount, unit)
    assertPurgedAtBoundary(kind, amount, unit)
}
```

- [ ] **Step 2: dependency-ordered retention과 key manifest를 구현한다**

```kotlin
data class BackupKeyManifest(
    val kekVersions: Set<String>,
    val verificationVersions: Set<String>,
    val stableDedupVersion: String,
    val commandTombstoneVersion: String,
)
```

Purge order is expired descriptors, inbox/claims, reservations/terminal entries, audit, then tenant deletion
only after backup retention. Defaults are descriptor 24 hours, terminal inbox/claim 7 days, terminal
entry/reservation 30 days and audit 90 days using PostgreSQL time. Command tombstone and stable dedup remain
tenant-lifetime. Legal hold/quarantine pauses purge. Live row, tombstone, dedup, audit and backup inventory
references block key retirement. Cursor restart, oldest-age/count metrics and concurrent replay/purge are
verified.

Restore preflight validates every referenced key before DB import, then runs ciphertext, counter, replay,
cursor, stale worker and one-time reveal smoke. The previous-binary fixture is an immutable JAR compiled from
the pinned `V000` API/schema source, checked against `previous-binary.sha256`, and executed by
`migrationCompatibilityTest` against the expanded current schema. Destructive down migration is forbidden:
rollback uses a compatible previous binary; if compatibility cannot be proven, restore the verified backup
plus key manifest and roll forward. Retention and key retirement remain held until rehearsal passes.

- [ ] **Step 3: compatibility/restore GREEN을 확인한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:migrationCompatibilityTest --rerun-tasks --max-workers=1
test -n "$(find commerce/pre-generated-voucher-pool/build/test-results/migrationCompatibilityTest -name '*.xml' -print -quit)"
./gradlew :commerce-pre-generated-voucher-pool:test --tests '*VoucherPoolRetentionIntegrationTest' --tests '*VoucherPoolBackupRestoreIntegrationTest' --tests '*VoucherPoolKeyRotationIntegrationTest' --max-workers=1
```

Expected: clean/warm/previous schema, checksum drift fail-closed, previous binary, backup/restore,
fixture-JAR SHA-256, post-purge `410`, exact table retention boundaries, legal-hold/quarantine pause,
referenced-key retirement guard, cursor restart/metrics and concurrent replay/purge PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/main/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config/VoucherPoolRetention.kt commerce/pre-generated-voucher-pool/src/compatibility commerce/pre-generated-voucher-pool/src/test/resources/compatibility commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/config
git commit -m "feat: restore voucher replay safety with data" -m "Constraint: Database backups are invalid without every referenced digest and encryption key.\nConfidence: high\nScope-risk: broad\nTested: Migration compatibility, retention, key rotation, and backup restore tests.\nNot-tested: Stress evidence follows in Task 13."
```

### Task 13: Two-run stress and performance evidence

**Complexity:** H
**Depends on:** Tasks 1-12
**Pattern skills:** `bluetape-kotlin-patterns`, `ecc-kotlin-testing`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/performance/VoucherPoolPerformanceProbe.kt`
- Create: `commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/VoucherPoolStressProfileTest.kt`

- [ ] **Step 1: hard-gate stress RED를 작성한다**

```kotlin
@Tag("stress")
@ParameterizedTest
@CsvSource("64,HEALTHY", "64,UNAVAILABLE", "128,HEALTHY", "128,UNAVAILABLE")
fun `virtual client matrix preserves hard resource bounds`(clients: Int, redis: RedisMode) {
    val evidence = runProfile(
        entries = 10_000,
        clients = clients,
        sameUserPercent = 50,
        redis = redis,
        workerLoad = true,
        sseSubscribers = 16,
        freshDatabase = true,
    )
    evidence.hikariActiveMax.shouldBeLessOrEqualTo(16)
    evidence.hikariAcquisitionWaitMaxMillis.shouldBeLessOrEqualTo(2_000)
    evidence.totalPermitHoldersMax.shouldBeLessOrEqualTo(16)
    evidence.foregroundWaitMaxMillis.shouldBeLessOrEqualTo(250)
    evidence.workerWaitMaxMillis.shouldBeLessOrEqualTo(1_000)
    evidence.sseWaitMaxMillis.shouldBeLessOrEqualTo(1_000)
    evidence.deadlineViolations shouldBeEqualTo 0
    evidence.hikariPendingDrainMillis.shouldBeLessOrEqualTo(12_000)
    evidence.workerCheckpointProgress shouldBeGreaterThan 0
    evidence.duplicateWinnerCount shouldBeEqualTo 0
    evidence.winners shouldBeEqualTo evidence.authoritativeAllocationCount
    evidence.successfulResponses shouldBeEqualTo evidence.authoritativeAllocationCount
    evidence.authoritativeReservationCount shouldBeEqualTo evidence.authoritativeAllocationCount
    evidence.stateCountSum shouldBeEqualTo evidence.entryCount
    evidence.acquisitionTimeoutTerminalDescriptors shouldBeEqualTo 0
    evidence.connectionLeaks shouldBeEqualTo 0
    evidence.permitLeaks shouldBeEqualTo 0
    evidence.counterDrift shouldBeEqualTo 0
}
```

- [ ] **Step 2: bounded evidence writer를 구현한다**

```kotlin
data class VoucherPoolStressEvidence(
    val runId: String,
    val clients: Int,
    val redisMode: RedisMode,
    val winners: Int,
    val successfulResponses: Int,
    val authoritativeReservationCount: Int,
    val authoritativeAllocationCount: Int,
    val duplicateWinnerCount: Int,
    val stateCountSum: Int,
    val entryCount: Int,
    val hikariActiveMax: Int,
    val hikariAcquisitionWaitMaxMillis: Long,
    val totalPermitHoldersMax: Int,
    val foregroundWaitMaxMillis: Long,
    val workerWaitMaxMillis: Long,
    val sseWaitMaxMillis: Long,
    val acquisitionTimeouts: Int,
    val acquisitionTimeoutTerminalDescriptors: Int,
    val deadlineViolations: Int,
    val hikariPendingMax: Int,
    val hikariPendingDrainMillis: Long,
    val workerCheckpointProgress: Long,
    val connectionLeaks: Int,
    val permitLeaks: Int,
    val counterDrift: Long,
)
```

Store one JSON and one JFR or thread-dump artifact per profile plus a run manifest. Every run starts from a
fresh database/Redis state and the manifest must contain exactly the four profiles with unique artifact
checksums. Latency/throughput are report-only; lane waits, acquisition/transaction/lock/chunk deadlines,
Hikari pending/drain, resource, progress and correctness fields are hard gates.
Every Hikari acquisition timeout must map to retryable `503 BACKEND_TIMEOUT`, release the idempotency owner and
write no terminal descriptor.

- [ ] **Step 3: two independent stress runs를 실행한다**

Run:

```bash
./gradlew :commerce-pre-generated-voucher-pool:stressTest -PvoucherPoolStressRun=final-1 --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:stressTest -PvoucherPoolStressRun=final-2 --rerun-tasks --max-workers=1
test -n "$(find commerce/pre-generated-voucher-pool/build/test-results/stressTest -name '*.xml' -print -quit)"
```

Expected: 64/128 clients × Redis healthy/unavailable profiles with concurrent worker/SSE load PASS twice;
each run's four-profile JSON/JFR-or-dump manifest is complete and checksum-valid, and
Hikari/permit/lane-wait/deadline/pending-drain/progress/leak/winner/count hard gates PASS.

- [ ] **Step 4: Lore commit을 만든다**

```bash
git add commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/performance commerce/pre-generated-voucher-pool/src/test/kotlin/io/bluetape4k/workshop/commerce/voucherpool/VoucherPoolStressProfileTest.kt
git commit -m "test: prove voucher pool contention bounds" -m "Constraint: Virtual client load must preserve fixed database capacity.\nConfidence: high\nScope-risk: moderate\nTested: Two complete stress matrices with persisted evidence.\nNot-tested: CI environment variance remains report-only for latency."
```

### Task 14: Bilingual docs, diagrams, validator and repository registration

**Complexity:** H
**Depends on:** Tasks 1-13
**Pattern skills:** `bluetape-writer`, `bluetape-diagram`, `bluetape-kotlin-patterns`

**Files:**
- Create: `commerce/pre-generated-voucher-pool/README.md`
- Create: `commerce/pre-generated-voucher-pool/README.ko.md`
- Create: `docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-architecture-01.svg`
- Create: `docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-architecture-01.png`
- Create: `docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-sequence-01.svg`
- Create: `docs/images/readme-diagrams/commerce-pre-generated-voucher-pool-readme-sequence-01.png`
- Create: `scripts/validate-voucher-pool-runbook.mjs`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `commerce/README.md`
- Modify: `commerce/README.ko.md`
- Modify: `AGENTS.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `.github/workflows/nightly.yml`
- Modify: `scripts/smoke-validate.sh`
- Modify if registration requires: `scripts/validate-readme-architecture-diagrams.mjs`
- Modify if registration requires: `scripts/validate-sequence-diagrams.mjs`

- [ ] **Step 1: missing registration RED를 관찰한다**

Run:

```bash
EXPECTED_GRADLE_PROJECTS=105 ./scripts/smoke-validate.sh stale-check
node scripts/validate-voucher-pool-runbook.mjs
```

Expected: project count becomes 105 after module creation, but missing README/validator/diagram references make
the new runbook validation FAIL until this task is implemented.

- [ ] **Step 2: README/runbook and diagram contract를 작성한다**

Both locales contain architecture, contention/recovery sequence, configuration, import/generation, customer
curl flow, lost reveal replacement, operator revoke/reconcile, outage behavior, stable error catalog,
retention/backup, unsupported scope and exact verification commands. SVG source and rendered PNG remain
visually equivalent; README image targets match by locale.

`scripts/validate-voucher-pool-runbook.mjs` owns an explicit bounded contract rather than reusing the #534
validator:

```javascript
const MODULE = "commerce/pre-generated-voucher-pool";
const REQUIRED_SECTIONS = [
  "Architecture",
  "Contention and recovery",
  "Import and generation",
  "Customer workflow",
  "Lost reveal replacement",
  "Error status retry and action catalog",
  "Revoke preview confirm and progress",
  "Reconciliation",
  "Redis outage",
  "Health and degraded state",
  "Alerts and diagnostics",
  "Retention and purge",
  "Migration and rollback",
  "Backup and restore",
];
const FORBIDDEN = [/raw.*code.*log/i, /GenericContainer/, /MockMvc/];
```

The validator requires every section and representative route/error/command in both locales, checks the
customer curl and error/status/retry/action matrix for parity, and requires every alert to name its threshold,
safe diagnostic, authoritative query, bounded operator action and recovery signal.

- [ ] **Step 3: repository registration을 갱신한다**

Add `:commerce-pre-generated-voucher-pool:test` and
`:commerce-pre-generated-voucher-pool:koverXmlReport` after the campaign module in the sequential container
job. Register exact Examples artifact paths:

```text
commerce/pre-generated-voucher-pool/build/test-results/test/*.xml
commerce/pre-generated-voucher-pool/build/reports/tests/test/
commerce/pre-generated-voucher-pool/build/reports/kover/report.xml
```

The workflow checks the JUnit glob is non-empty and `report.xml` is non-empty before upload. Nightly full
keeps root `test`, adds the module Kover XML task, and uploads the same XML path. No hard threshold or new
Codecov uploader is added because this repository has no existing uploader to preserve. Add
`scripts/smoke-validate.sh commerce`, root/commerce README rows and one `AGENTS.md` module-map row. Verify the
new project appears in the nightly root-test graph.

- [ ] **Step 4: docs/registration GREEN을 확인한다**

Run:

```bash
./gradlew projects --console=plain
EXPECTED_GRADLE_PROJECTS=105 ./scripts/smoke-validate.sh stale-check
node scripts/validate-voucher-pool-runbook.mjs
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
bash -n scripts/smoke-validate.sh
./gradlew :commerce-pre-generated-voucher-pool:test :commerce-pre-generated-voucher-pool:koverXmlReport --max-workers=1
test -n "$(find commerce/pre-generated-voucher-pool/build/test-results/test -name '*.xml' -print -quit)"
test -s commerce/pre-generated-voucher-pool/build/reports/kover/report.xml
```

Expected: 105 projects, locale/image/fence parity, diagram/sequence validators, actionlint, shell syntax,
non-empty JUnit/Kover artifact globs and module Kover XML PASS. Any pre-existing global language finding is
recorded with exact unrelated path and the new module must have zero finding.

- [ ] **Step 5: sequential Commerce lane를 실행한다**

Run:

```bash
./scripts/smoke-validate.sh commerce
```

Expected: four PostgreSQL-backed Commerce modules PASS sequentially with `--max-workers=1`.

- [ ] **Step 6: Lore commit을 만든다**

```bash
git add README.md README.ko.md commerce/README.md commerce/README.ko.md AGENTS.md .github/workflows/Examples.yml .github/workflows/nightly.yml scripts docs/images/readme-diagrams commerce/pre-generated-voucher-pool/README.md commerce/pre-generated-voucher-pool/README.ko.md
git commit -m "docs: register the pre-generated voucher pool" -m "Constraint: New workshop modules must remain discoverable and covered by container CI.\nConfidence: high\nScope-risk: moderate\nTested: Project graph, runbook/parity/diagram validators, actionlint, stale-check, and Commerce lane.\nNot-tested: Live GitHub CI follows after PR creation."
```

### Task 15: Full module verification, cleanup, review and durable evidence

**Complexity:** H
**Depends on:** Tasks 1-14
**Pattern skills:** `verification-before-completion`, `bluetape-kotlin-patterns`, `requesting-code-review`

**Files:**
- Create: `docs/review/2026-07-20-issue-537-pre-generated-voucher-pool-review.md`
- Create: `docs/lessons/2026-07-20-issue-537-pre-generated-voucher-pool.md`
- Modify only if review requires: files owned by Tasks 1-14

- [ ] **Step 1: complete targeted and module verification을 fresh 실행한다**

Run sequentially:

```bash
./gradlew :commerce-pre-generated-voucher-pool:test --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:migrationCompatibilityTest --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:koverXmlReport --rerun-tasks --max-workers=1
./gradlew :commerce-pre-generated-voucher-pool:detekt :commerce-pre-generated-voucher-pool:detektTest
./gradlew :commerce-pre-generated-voucher-pool:dependencies --configuration runtimeClasspath
test -s commerce/pre-generated-voucher-pool/build/reports/kover/report.xml
test -n "$(find commerce/pre-generated-voucher-pool/build/test-results/migrationCompatibilityTest -name '*.xml' -print -quit)"
test -n "$(find commerce/pre-generated-voucher-pool/build/test-results/stressTest -name '*.xml' -print -quit)"
```

Expected: all module tests, packaged migration compatibility and detekt PASS; runtime resolves JDK25 provider,
Exposed JDBC, Lettuce/Bucket4j/leader and has no JDK21 provider; report-only Kover XML exists and is non-empty.

- [ ] **Step 2: forbidden and repository-wide proportional checks를 실행한다**

Run:

```bash
rg -n "println\(|printStackTrace\(|SqlExpressionBuilder\.eq|!!|MockMvc|MockMvcWebTestClient|GenericContainer" commerce/pre-generated-voucher-pool
./gradlew build -x test --parallel --continue
git diff --check
```

Expected: forbidden scan has no production/test violation; root compile and diff check PASS.

- [ ] **Step 3: spec/plan verifier와 six-lens code review를 실행한다**

Review the exact branch diff using performance, stability, security, operator/Ops, developer/API and
user/caller lanes plus main integration. Every P0/P1 maps to an exact test-first repair and affected lanes are
rerun. Record final `P0=0, P1=0` and all command evidence in the review document.

- [ ] **Step 4: durable lesson을 작성한다**

The Korean lesson records context, design decision, RED/GREEN evidence, concurrency or restore surprise,
review misses, final commands and a future guard. It must be committed before PR creation.

- [ ] **Step 5: final Lore evidence commit을 만든다**

```bash
git add docs/review/2026-07-20-issue-537-pre-generated-voucher-pool-review.md docs/lessons/2026-07-20-issue-537-pre-generated-voucher-pool.md
git commit -m "docs: preserve voucher pool delivery evidence" -m "Constraint: Type A delivery requires tracked review and reusable learning before PR publication.\nConfidence: high\nScope-risk: narrow\nTested: Full module, migration, stress, detekt, repository compile, validators, and six-lens review.\nNot-tested: GitHub CI remains pending exact-head push."
```

**Step DoD:** implementation branch is clean; spec/plan/code evidence is tracked; all triggered repository and
Kotlin checks are PASS or concrete N/A; final P0/P1 is zero. PR creation then follows the approved delivery
scope, but merge still waits for a separate fresh approval after live CI and current review threads pass.

## Plan self-review checklist

- Every acceptance criterion maps to one or more numbered tasks and a concrete command.
- Every production task starts with a named RED test and ends with targeted GREEN evidence.
- No task consumes a type or table created by a later task.
- PostgreSQL/Testcontainers commands are sequential and H2 is never authority evidence.
- Campaign/batch shared lock, worker canonical relock and replay-tombstone restore risks have explicit forced
  tests and rollback points.
- README locales, diagrams, module map, Examples container job, artifacts, Commerce smoke and project count are
  assigned.
- Nightly full uses root `test`, so explicit task-list mutation is N/A but graph inclusion is verified.
- Module-local Kover XML and Examples/Nightly artifact visibility are assigned without a hard threshold or
  a repository-wide Codecov uploader.
- Public KDoc and GitHub-facing metadata remain English; plan/review/lesson prose remains Korean.
- The plan contains no `TODO`, `TBD`, deferred implementation marker or unnamed validation step.
