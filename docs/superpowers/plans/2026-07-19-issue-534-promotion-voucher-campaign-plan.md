# Issue #534 Promotion and Voucher Campaign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 25 Spring Boot MVC application에서 PostgreSQL-authoritative promotion campaign,
opaque voucher allocation, review, redemption, release/revoke/expiry와 application-owned HTTP
idempotency를 구현하고 Redis/Lettuce admission, leader reconciliation, browser SSE를 장애와
경합 fixture로 검증한다.

**Architecture:** 모든 업무 상태와 capacity 회계는 Exposed JDBC repository가 PostgreSQL
transaction 안에서 campaign -> claim -> review 순서로 잠그고 CAS한다. Redis/Bucket4j/Bloom과
leader election은 admission·risk·worker 선택만 보조하며, 항상 적용되는 12 foreground + 1
worker-reserved + 3 SSE-maintenance JDBC permit와 Hikari 16이 virtual-thread 폭주를 제한한다.
Spring Modulith publication, delayed inbox, audit cursor와 application-owned idempotency row가
restart/replay 경계를 보존한다.

**Tech Stack:** Kotlin plugin/compiler 2.4.0 with repository language/API level 2.3, Java 25,
Spring Boot 4.1.0 MVC/Tomcat, JetBrains
Exposed 1.3.x managed by `bluetape4k-dependencies:1.3.1`, PostgreSQL, HikariCP 16,
Redis/Lettuce, Bucket4j, Spring Modulith, Micrometer, JUnit 5, live WebTestClient,
`bluetape4k-exposed-jdbc`, `bluetape4k-exposed-jdbc-tests`, `bluetape4k-logging`,
`bluetape4k-virtualthread-api`, runtime `bluetape4k-virtualthread-jdk25`,
`bluetape4k-testcontainers`, Bluetape leader and Lettuce modules.

---

## 구현 기준

- 설계 권위는
  `docs/superpowers/specs/2026-07-19-issue-534-promotion-voucher-campaign-design.md`다.
- module은 `commerce/promotion-voucher-campaign`, Gradle project는
  `:commerce-promotion-voucher-campaign`, package는
  `io.bluetape4k.workshop.commerce.voucher`다.
- root의 `platform(libs.bluetape4k.dependencies)`만 version authority로 사용한다. 개별 Bluetape
  BOM이나 명시 버전을 추가하지 않는다.
- production operational class는 `KLogging`을 사용한다. DTO, enum, Exposed table 선언처럼 실행
  경로가 없는 선언형 타입은 logger 대상에서 제외한다.
- 권위 DB 검증은 `PostgreSQLServer.Launcher.postgres`만 사용한다. H2와 embedded DB는 사용하지
  않는다.
- HTTP 검증은 `RANDOM_PORT + WebTestClient.bindToServer(JdkClientHttpConnector)`만 사용한다.
  MockMvc와 `MockMvcWebTestClient`는 사용하지 않는다.
- Testcontainers-backed task는 `--max-workers=1`로 순차 실행한다.
- 구현 test는 repository 관례의 `bluetape4k-assertions`/Kluent assertion을 기본으로 사용하고,
  JUnit assertion은 `assertTimeoutPreemptively`처럼 JUnit lifecycle API가 필요한 경우로 제한한다.
- public request/response/error/configuration DTO는 repository serialization convention에 따라
  `Serializable`과 explicit `serialVersionUID`를 제공하고 English KDoc으로 field/secret/retry
  의미를 설명한다. internal persistence record도 기존 Exposed module 관례를 따른다.
- pre-generated voucher pool과 event sourcing abstraction은 각각 #537, #538 범위이므로
  이 module에 미완료 표식이나 추상화 흔적을 남기지 않는다.

## 파일 구조와 책임

### Production

- `commerce/promotion-voucher-campaign/build.gradle.kts`: Java 25와 versionless dependency 계약
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherCampaignApplication.kt`: entrypoint와 lifecycle log
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherConfiguration.kt`: `Clock`, virtual-thread executor, transaction manager,
  foreground/background DB permits
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherRedisConfiguration.kt`: optional Lettuce, Bucket4j, Bloom, leader bean lifecycle
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherPublicationConfiguration.kt`: Exposed Spring Modulith publication repository
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMigrationRunner.kt`: versioned migration checksum/lock/startup gate
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/CampaignModels.kt`: campaign state, policy, command/outcome value
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/ClaimModels.kt`: claim/review state와 capacity contribution
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/VoucherPolicies.kt`: transition과 policy validation
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/security/VoucherCodeService.kt`: code 생성, checksum, verifier HMAC, key version
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherTables.kt`: campaign, claim, review, audit, inbox table
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherRecords.kt`: repository entity
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/CampaignRepository.kt`: auditable CRUD와 conditional capacity CAS
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/ClaimRepository.kt`: tenant-scoped claim lock/CAS와 code verifier lookup
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/ReviewRepository.kt`: review open/decision CAS
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/AuditRepository.kt`: append-only audit/cursor query
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/EventInboxRepository.kt`: delayed event claim/finalize/backoff
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/IdempotencyFingerprint.kt`: semantic-header/closed-DTO canonical fingerprint
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/HttpIdempotencyRepository.kt`: acquire/replay/takeover/finalize/cleanup
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/AllocationService.kt`: allocation transaction와 review 생성
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ClaimCommandService.kt`: redeem/release/revoke/expiry
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ReviewCommandService.kt`: allocation/redemption review decision
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/IdempotentVoucherCommandService.kt`: HTTP owner와 business transaction 조정
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/DatabasePermitGate.kt`: 12 foreground + 1 worker-reserved + 3 SSE-maintenance always-on permit
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/VoucherAdmissionGate.kt`: Bucket4j/Lettuce와 degraded hysteresis
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/RiskSignalService.kt`: Bloom advisory signal과 deterministic fixture
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationService.kt`: SKIP LOCKED bounded batch
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationWorker.kt`: leader-selected scheduled trigger
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/query/VoucherQueryService.kt`: tenant/owner/operator snapshot과 backlog projection
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/CustomerVoucherController.kt`: customer command/query
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorVoucherController.kt`: campaign/review/reconcile/fixture command
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherEventStream.kt`: snapshot-first bounded SSE
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorAccessFilter.kt`: loopback/demo secret/Host/Origin guard
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/RequestLoggingFilter.kt`: requestId와 redacted boundary log
- `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/ApiExceptionHandler.kt`: stable error matrix와 `Retry-After`
- `commerce/promotion-voucher-campaign/src/main/resources/db/migration/V001__voucher_campaign.sql`: initial schema/index/check
- `commerce/promotion-voucher-campaign/src/main/resources/application.yml`: Tomcat/Hikari/transaction/admission/worker/SSE 설정
- `commerce/promotion-voucher-campaign/src/main/resources/static/{index.html,app.js,styles.css}`: dependency-free browser console

### Tests and repository integration

- `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/AbstractVoucherIntegrationTest.kt`: PostgreSQLServer, RedisServer, live WebTestClient
- domain/security/repository/idempotency/admission/reconciliation/web/logging/lifecycle tests
- `commerce/promotion-voucher-campaign/{README.md,README.ko.md}`
- `commerce/{README.md,README.ko.md}`, root `{README.md,README.ko.md}`, `AGENTS.md`
- `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`
- `docs/lessons/2026-07-19-issue-534-promotion-voucher-campaign.md`
- `docs/review/2026-07-19-issue-534-promotion-voucher-campaign-review.md`
- `docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-{architecture,sequence}-01.svg`

### Task 1: Module, dependency, runtime, migration contract

**Files:**
- Create: `commerce/promotion-voucher-campaign/build.gradle.kts`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherCampaignApplication.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherConfiguration.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMigrationRunner.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/DatabasePermitGate.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherJdbcExecutor.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/resources/application.yml`
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/RuntimeContractTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherStartupValidationTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/DatabasePermitGateTest.kt`

- [ ] **Step 1: module registration failure를 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:tasks
```

Expected: FAIL because the auto-registered module directory does not exist yet.

- [ ] **Step 2: Java 25와 versionless dependency skeleton을 작성한다**

`build.gradle.kts`에는 다음 계약을 그대로 둔다.

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}
springBoot {
    mainClass.set("io.bluetape4k.workshop.commerce.voucher.VoucherCampaignApplicationKt")
}
configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }
configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
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
    implementation(libs.exposed.jackson3)
    implementation(libs.exposed.spring.boot.jdbc)
    implementation(libs.exposed.spring.modulith)
    implementation(libs.jetbrains.exposed.java.time)
    implementation(libs.jetbrains.exposed.spring7.transaction)
    testImplementation(libs.exposed.jdbc.tests) {
        exclude(group = "org.jetbrains.exposed", module = "exposed-spring-boot4-starter")
    }
    implementation(libs.bluetape4k.bucket4j)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.lettuce)
    implementation(libs.bluetape4k.lettuce)
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.micrometer)
    implementation(libs.bluetape4k.leader.redis.lettuce)
    implementation(libs.hikaricp)
    runtimeOnly(libs.postgresql.driver)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.events.jackson)
    implementation(libs.spring.modulith.actuator)
    implementation(libs.spring.modulith.observability)
    testImplementation(libs.spring.modulith.starter.test)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc.lib)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.webmvc.lib)
    testImplementation(libs.spring.boot.starter.webflux.test)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.mockk)
}

val compatibility by sourceSets.creating {
    java.srcDir("src/compatibility/java")
    compileClasspath = files()
    runtimeClasspath = output
}

val previousBinaryJar by tasks.registering(Jar::class) {
    archiveClassifier.set("previous-binary")
    from(compatibility.output)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

fun Test.useWorkshopTestRuntime() {
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    jvmArgs(
        "-Xshare:off", "-Xms2G", "-Xmx4G", "-XX:+UseZGC",
        "-XX:+UnlockExperimentalVMOptions", "-XX:+EnableDynamicAgentLoading",
        "--enable-preview", "-Didea.io.use.nio2=true",
    )
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("management.datadog.metrics.export.enabled", "false")
    environment("DD_API_KEY", providers.environmentVariable("DD_API_KEY").orElse("test-api-key").get())
    environment(
        "DD_APPLICATION_KEY",
        providers.environmentVariable("DD_APPLICATION_KEY").orElse("test-application-key").get(),
    )
    testLogging { events("failed"); showExceptions = true; showCauses = true; showStackTraces = true }
}

tasks.test {
    useWorkshopTestRuntime()
    useJUnitPlatform { excludeTags("stress", "migration-compatibility") }
}

val stressRun = providers.gradleProperty("voucherStressRun")
val stressReportDirectory = layout.buildDirectory.dir(stressRun.map { "reports/voucher-stress/$it" })
val stressTest by tasks.registering(Test::class) {
    description = "Runs voucher stress evidence profiles."
    group = "verification"
    useWorkshopTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("stress") }
    outputs.dir(stressReportDirectory)
    doFirst {
        val run = stressRun.orNull ?: error("-PvoucherStressRun=<unique-run-id> is required")
        systemProperty("voucher.stress.run", run)
        systemProperty("voucher.stress.report-directory", stressReportDirectory.get().asFile.absolutePath)
    }
    shouldRunAfter(tasks.test)
}

val migrationCompatibilityTest by tasks.registering(Test::class) {
    description = "Runs packaged voucher migration compatibility processes."
    group = "verification"
    useWorkshopTestRuntime()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("migration-compatibility") }
    dependsOn(tasks.bootJar, previousBinaryJar)
    shouldRunAfter(tasks.test)
}
```

- [ ] **Step 3: runtime contract test를 먼저 작성한다**

```kotlin
internal class RuntimeContractTest {
    @Test
    fun `Java 25 virtual threads and bounded JDBC settings are pinned`() {
        val sources = MutablePropertySources().apply {
            YamlPropertySourceLoader().load("voucher", ClassPathResource("application.yml"))
                .forEach(::addLast)
        }
        val properties = PropertySourcesPropertyResolver(sources)
        assertTrue(Runtime.version().feature() >= 25)
        assertEquals("jdk25", VirtualThreads.runtimeName())
        VirtualThreads.executorService().use { executor ->
            assertTrue(executor.submit<Boolean> { Thread.currentThread().isVirtual }.get())
        }
        assertEquals("true", properties.getProperty("spring.threads.virtual.enabled"))
        assertEquals("16", properties.getProperty("spring.datasource.hikari.maximum-pool-size"))
        assertEquals("60s", properties.getProperty("spring.transaction.default-timeout"))
        assertEquals("8000", properties.getProperty("server.tomcat.threads.max"))
        assertEquals("8000", properties.getProperty("server.tomcat.max-connections"))
    }
}

@ParameterizedTest
@MethodSource("invalidProductionConfigurations")
fun `unsafe production configuration fails with a sanitized code`(case: InvalidConfiguration) {
    val failure = startContext(case.properties)
    assertEquals(case.expectedCode, sanitizedStartupCode(failure))
    assertNoSecretOrRawPropertyValue(failure)
}
```

`invalidProductionConfigurations`는 unknown property, invalid range, missing/weak/default/known-test
key, current/read key-ring 불일치, persisted referenced key 누락, demo/test profile + public bind를
각각 `UNKNOWN_PROPERTY`, `INVALID_RANGE`, `MISSING_KEY`, `WEAK_KEY`, `TEST_KEY_FORBIDDEN`,
`INVALID_KEY_RING`, `DOMAIN_KEY_REUSE`, `REFERENCED_KEY_MISSING`, `PUBLIC_DEMO_BIND`로 고정한다.
generation key를 verifier/identity/risk/Redis slot에 잘못 연결한 configuration도
`DOMAIN_KEY_REUSE`로 거부한다.

- [ ] **Step 4: runtime/startup/permit tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*RuntimeContractTest' --tests '*DatabasePermitGateTest' \
  --tests '*VoucherStartupValidationTest' --max-workers=1
```

Expected: FAIL because application configuration, permit gate, JDBC executor, and startup validator
are absent.

- [ ] **Step 5: application/configuration과 runtime 값을 구현한다**

```kotlin
@SpringBootApplication
@EnableScheduling
class VoucherCampaignApplication {
    companion object : KLogging()
}

fun main(args: Array<String>) {
    runApplication<VoucherCampaignApplication>(*args)
}
```

`application.yml`은 `spring.threads.virtual.enabled=true`, Tomcat max threads/connections
8000, accept-count 1000, connection/keep-alive timeout 60초, Hikari max 16/min idle 4/
connection timeout 60초, transaction timeout 60초, PostgreSQL lock timeout 5초, DB permit timeout
250ms, Redis command timeout 500ms를 pin한다. application과 management는 서로 다른 loopback
port/address에 bind하고 management endpoint는 health/readiness/liveness/Prometheus만 allowlist한다.
production profile은 unknown property, permit 합
16 초과, missing/weak/known-test/default key, public bind + demo/test profile 조합을 startup에서
거부한다.
`VoucherConfiguration`은 `SpringTransactionManager`, injected `Clock`, application-owned
virtual-thread executor와 12 foreground/1 worker/3 SSE-maintenance lane을 가진
`DatabasePermitGate`를 bean으로 제공한다. invalid permit 합이나 range는 context binding 단계에서
거부한다. canonical namespace는 approved spec의
`workshop.voucher.db.foreground-permits=12`, `background-permits=4`, `permit-timeout=250ms`,
`lock-timeout=5s`이며 background 4에서 fixed worker-reserved 1과 SSE-maintenance 3을 파생한다.

- [ ] **Step 6: JDBC permit와 transaction-local lock 경계를 먼저 구현한다**

```kotlin
enum class DatabaseLane { FOREGROUND, WORKER, SSE_MAINTENANCE }

class DatabasePermitRejected(val retryAfter: Duration) : RuntimeException("database permit unavailable")

class DatabasePermitGate(
    foregroundPermits: Int = 12,
    workerPermits: Int = 1,
    sseMaintenancePermits: Int = 3,
    private val acquireTimeout: Duration = Duration.ofMillis(250),
) {
    private val semaphores = mapOf(
        DatabaseLane.FOREGROUND to Semaphore(foregroundPermits, true),
        DatabaseLane.WORKER to Semaphore(workerPermits, true),
        DatabaseLane.SSE_MAINTENANCE to Semaphore(sseMaintenancePermits, true),
    )
    private val heldLane = ThreadLocal<DatabaseLane?>()

    fun <T> withPermit(lane: DatabaseLane, block: () -> T): T {
        check(heldLane.get() == null) { "nested database permit acquisition is forbidden" }
        val semaphore = semaphores.getValue(lane)
        val acquired = try {
            semaphore.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DatabasePermitRejected(Duration.ofSeconds(1))
        }
        if (!acquired) throw DatabasePermitRejected(Duration.ofSeconds(1))
        heldLane.set(lane)
        return try { block() } finally { heldLane.remove(); semaphore.release() }
    }

    fun requireHeld() = check(heldLane.get() != null) { "JDBC access requires a database permit" }
}

class VoucherJdbcExecutor(
    private val gate: DatabasePermitGate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun <T> foregroundTransaction(block: () -> T): T =
        gate.withPermit(DatabaseLane.FOREGROUND) {
            transactions.execute {
                TransactionManager.current().exec("SET LOCAL lock_timeout = '5s'")
                block()
            } ?: error("foreground transaction returned null")
        }
}
```

`VoucherJdbcExecutor`는 permit를 먼저 획득한 뒤에만 connection/transaction을 열고, foreground
business transaction 시작 직후 `SET LOCAL lock_timeout='5s'`를 실행한다. repository public
method는 `gate.requireHeld()`로 무permit 접근을 fail fast한다. Task 3~5는 처음부터 이 executor를
필수 의존성으로 사용하며 뒤늦은 retrofit을 허용하지 않는다. permit timeout/interruption은
interrupt status를 보존한 `DatabasePermitRejected(retryAfter)`만 발생시키고
`ApiExceptionHandler`가 이를 `503 DATABASE_BULKHEAD_REJECTED`와 `Retry-After`로 매핑한다.
application facade는 transaction entrypoint에 일반 `@Transactional`을 붙이지 않고 이 executor의
Spring `TransactionTemplate`을 호출한다. 따라서 permit interceptor/order에 의존하지 않으며
Exposed repository, idempotency finalize, audit와 Modulith publication이 동일 Spring transaction에
참여한다. datasource probe와 rollback test는 permit 전 connection 0, transaction 안 connection 1,
rollback 뒤 mutation/finalize/audit/publication 0을 함께 검증한다.

- [ ] **Step 7: migration runner failure modes를 고정한다**

`VoucherMigrationRunner`는 PostgreSQL advisory lock을 얻고
`db/migration/V001__voucher_campaign.sql`의 SHA-256을 `voucher_schema_history`와 비교한다.
checksum drift, lock timeout, partial statement failure는 readiness와 startup을 실패시킨다.
clean DB와 existing history replay가 같은 checksum으로 idempotent한 테스트를 Task 3에 둔다.

- [ ] **Step 8: module과 dependency resolution을 검증한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:compileKotlin \
  :commerce-promotion-voucher-campaign:dependencyInsight \
  --dependency org.jetbrains.exposed:exposed-core \
  --configuration runtimeClasspath
./gradlew :commerce-promotion-voucher-campaign:dependencyInsight \
  --dependency bluetape4k-virtualthread-jdk25 \
  --configuration runtimeClasspath
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*RuntimeContractTest' --tests '*DatabasePermitGateTest' \
  --tests '*VoucherStartupValidationTest' --max-workers=1
```

Expected: compile PASS; Exposed resolves from the dependencies catalog; JDK25 provider is present
and JDK21 provider is absent; runtime/permit contracts PASS; every unsafe production configuration
fails with the expected sanitized root-cause code.

- [ ] **Step 9: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "build: establish the bounded Java 25 voucher runtime" \
  -m $'Constraint: The workshop consumes only the bluetape4k-dependencies BOM.\nConfidence: high\nScope-risk: moderate\nTested: Module compile, permit contract, and dependency insight.'
```

### Task 2: Domain policy and opaque voucher code TDD

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/CampaignModels.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/ClaimModels.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/VoucherPolicies.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/security/VoucherCodeService.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/domain/VoucherPoliciesTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/security/VoucherCodeServiceTest.kt`

- [ ] **Step 1: transition과 capacity contribution 실패 테스트를 작성한다**

```kotlin
@Test
fun `redemption review rejection returns to allocated without changing capacity`() {
    val claim = claim(state = REVIEW_REQUIRED, reviewKind = REDEMPTION, capacityReserved = true)
    val outcome = VoucherPolicies.rejectReview(claim, expectedRevision = claim.revision)
    assertEquals(ALLOCATED, outcome.claim.state)
    assertTrue(outcome.claim.capacityReserved)
    assertEquals(0, outcome.capacityDelta)
}

@Test
fun `allocation review approval contributes capacity once`() {
    val claim = claim(state = REVIEW_REQUIRED, reviewKind = ALLOCATION, capacityReserved = false)
    val outcome = VoucherPolicies.approveReview(claim, expectedRevision = claim.revision)
    assertEquals(ALLOCATED, outcome.claim.state)
    assertEquals(1, outcome.capacityDelta)
}
```

- [ ] **Step 2: code format와 verifier 분리 실패 테스트를 작성한다**

```kotlin
@Test
fun `generated code has version payload checksum and a separate storage verifier`() {
    val service = VoucherCodeService(fixedKeyRing())
    val issued = service.issue(fixedGenerationInput())
    assertTrue(issued.code.matches(Regex("V7-[1-9A-HJ-NP-Za-km-z]{22}[1-9A-HJ-NP-Za-km-z]{2}")))
    assertTrue(service.verify(issued.code, issued.verifier, verificationKeyVersion = 7))
    assertFalse(issued.verifier.contentEquals(issued.code.toByteArray()))
}

@ParameterizedTest
@ValueSource(strings = ["", "V7-한글", "V7-000000000000000000000000", "V999-abc", "V7-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"])
fun `invalid code forms fail with one redacted result`(candidate: String) {
    assertEquals(VerificationResult.INVALID_CODE, service.verifyExternal(candidate))
    assertNoRawCandidateInCapturedLogs(candidate)
}

@Test
fun `fixed domain labels separate every digest even with the same secret material`() {
    val key = fixedSecretMaterial()
    val input = fixedGenerationInput()
    val digests = listOf("voucher-generation", "voucher-verifier", "identity", "risk", "redis-slot")
        .map { domain -> hmac(key, domain, input.canonicalBytes()) }
    assertEquals(digests.size, digests.map { it.contentHashCode() }.toSet().size)
    assertNotEquals(service.issue(input).code, service.issue(input.copy(tenantId = "tenant-b")).code)
    assertNotEquals(service.issue(input).verifier, service.issue(input.copy(campaignId = uuidB)).verifier)
}
```

- [ ] **Step 3: tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherPoliciesTest' --tests '*VoucherCodeServiceTest'
```

Expected: FAIL because the domain and code services do not exist.

- [ ] **Step 4: minimal immutable domain과 policy를 구현한다**

```kotlin
enum class CampaignState { DRAFT, ACTIVE, PAUSED, ENDED }
enum class ClaimState { ELIGIBLE, REVIEW_REQUIRED, ALLOCATED, REDEEMED, RELEASED, EXPIRED, REVOKED, REJECTED }
enum class ReviewKind { ALLOCATION, REDEMPTION }

data class ClaimSnapshot(
    val tenantId: String,
    val campaignId: UUID,
    val claimId: UUID,
    val state: ClaimState,
    val reviewKind: ReviewKind?,
    val capacityReserved: Boolean,
    val revision: Long,
)

data class TransitionOutcome(val claim: ClaimSnapshot, val capacityDelta: Int)

object VoucherPolicies : KLogging() {
    fun approveReview(claim: ClaimSnapshot, expectedRevision: Long): TransitionOutcome =
        requireRevision(claim, expectedRevision).let {
            when (claim.reviewKind) {
                ReviewKind.ALLOCATION -> TransitionOutcome(claim.copy(state = ClaimState.ALLOCATED, capacityReserved = true, revision = claim.revision + 1), 1)
                ReviewKind.REDEMPTION -> TransitionOutcome(claim.copy(state = ClaimState.REDEEMED, capacityReserved = true, revision = claim.revision + 1), 0)
                null -> error("claim is not under review")
            }
        }

    fun rejectReview(claim: ClaimSnapshot, expectedRevision: Long): TransitionOutcome =
        requireRevision(claim, expectedRevision).let {
            when (claim.reviewKind) {
                ReviewKind.ALLOCATION -> TransitionOutcome(claim.copy(state = ClaimState.REJECTED, revision = claim.revision + 1), 0)
                ReviewKind.REDEMPTION -> TransitionOutcome(claim.copy(state = ClaimState.ALLOCATED, reviewKind = null, revision = claim.revision + 1), 0)
                null -> error("claim is not under review")
            }
        }
}
```

`VoucherCodeService`는 generation HMAC domain과 verifier HMAC domain을 분리하고,
`V{verificationKeyVersion}-{22 Base58}{2 checksum}`을 만든다. raw code는 반환 직후 외에는
저장/logging하지 않고 constant-time verifier 비교만 제공한다. Unicode/control/oversized/
unknown-version/checksum failure는 timing과 error detail을 구분하지 않는 bounded redacted 결과로
거부한다. generation, verifier, identity/risk/Redis namespace key는 독립 key 또는 고정 domain
separation을 사용하고 active key-ring reference가 누락되면 startup/readiness를 fail closed한다.

- [ ] **Step 5: domain/security tests를 GREEN으로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherPoliciesTest' --tests '*VoucherCodeServiceTest'
```

Expected: PASS including stale revision, invalid checksum, wrong key version, expiry/revoke and
redemption-review rejection, domain-separation golden vectors, and tenant/campaign/allocation input
separation cases.

- [ ] **Step 6: commit한다**

```bash
git add commerce/promotion-voucher-campaign/src/{main,test}/kotlin
git commit -m "feat: define voucher transitions before persistence" \
  -m $'Rejected: Pre-generated voucher inventory | Reserved for issue #537\nConfidence: high\nScope-risk: moderate\nTested: Domain policy and voucher code tests.'
```

### Task 3: Exposed tables, repositories, migrations, and PostgreSQL invariants

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherTables.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherRecords.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/CampaignRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/ClaimRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/ReviewRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/AuditRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/EventInboxRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/resources/db/migration/V001__voucher_campaign.sql`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/persistence/VoucherRepositoryTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMigrationRunnerTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/PreviousSchemaCompatibilityTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMigrationCompatibilityIntegrationTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/compatibility/java/io/bluetape4k/workshop/commerce/voucher/compatibility/PreviousVoucherBinaryMain.java`
- Create: `commerce/promotion-voucher-campaign/src/test/resources/compatibility/V000__previous_voucher_schema.sql`
- Create: `commerce/promotion-voucher-campaign/src/test/resources/compatibility/previous-binary.sha256`

- [ ] **Step 1: PostgreSQL repository와 migration compatibility RED tests를 작성한다**

```kotlin
@Test
fun `capacity CAS never exceeds campaign capacity`() {
    withTables(TestDB.POSTGRESQL, *voucherTables) {
        jdbc.foregroundTransaction {
            val campaigns = CampaignRepository(gate)
            val campaign = campaigns.create(activeCampaign(capacity = 1))
            assertTrue(campaigns.tryReserve(campaign.tenantId, campaign.id, expectedRevision = 0))
            assertFalse(campaigns.tryReserve(campaign.tenantId, campaign.id, expectedRevision = 0))
            assertEquals(1, campaigns.findById(campaign.id).allocatedCount)
        }
    }
}

@Test
fun `cross tenant claim lookup is indistinguishable from missing`() {
    withTables(TestDB.POSTGRESQL, *voucherTables) {
        val claims = ClaimRepository(gate)
        jdbc.foregroundTransaction {
            val claim = claims.insert(allocatedClaim(tenantId = "tenant-a"))
            assertNull(claims.findPublic("tenant-b", claim.claimId))
        }
    }
}

@Test
fun `claim storage contains only verifier and key versions`() {
    withTables(TestDB.POSTGRESQL, *voucherTables) {
        val issued = codeService.issue(fixedGenerationInput())
        jdbc.foregroundTransaction { claims.insert(allocatedClaim(issued)) }
        val columns = databaseColumns(ClaimTable.tableName)
        assertTrue(columns.containsAll(setOf("code_verifier", "generation_key_version", "verification_key_version")))
        assertFalse(columns.any { it in setOf("code", "token_material", "generation_digest") })
        assertDatabaseBytesDoNotContain(issued.code.toByteArray())
    }
}

@Test
fun `partial migration rollback leaves no history and next startup recovers`() {
    injectFailureAfterMigrationStatement(2)
    assertStartupFailsWithCode("MIGRATION_PARTIAL_DDL")
    assertEquals(0, schemaHistoryRows())
    clearInjectedFailure()
    assertCurrentBootJarStartsAndMatchesChecksum()
}

@Test
@Tag("migration-compatibility")
fun `previous current previous process sequence remains read write compatible`() {
    assertPinnedPreviousBinaryChecksum()
    runPreviousBinary("write")
    runCurrentBootJar("migrate-read-write")
    runPreviousBinary("read-write")
    assertCompatibilityRowsAndExitCodes()
}
```

- [ ] **Step 2: repository tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherRepositoryTest' --tests '*VoucherMigrationRunnerTest' \
  --tests '*PreviousSchemaCompatibilityTest' --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:migrationCompatibilityTest \
  --rerun-tasks --max-workers=1
```

Expected: FAIL because tables, repositories, migration runner, bootJar behavior, and compatibility
process fixtures do not exist.

- [ ] **Step 3: auditable tables와 repository base를 구현한다**

```kotlin
internal object CampaignTable : AuditableLongIdTable("voucher_campaigns") {
    val tenantId = varchar("tenant_id", 64)
    val campaignId = uuid("campaign_id")
    val state = enumerationByName<CampaignState>("state", 24)
    val capacity = integer("capacity")
    val allocatedCount = integer("allocated_count").default(0)
    val revision = long("revision").default(0)
    init {
        uniqueIndex(tenantId, campaignId)
        check("voucher_campaign_capacity") { allocatedCount.between(0, capacity) }
    }
}

@Repository
internal class CampaignRepository(private val gate: DatabasePermitGate) :
    LongAuditableJdbcRepository<CampaignRecord, CampaignTable> {
    override val table = CampaignTable
    override fun extractId(entity: CampaignRecord): Long = entity.id

    override fun ResultRow.toEntity() = CampaignRecord(
        id = this[table.id].value,
        tenantId = this[table.tenantId],
        campaignId = this[table.campaignId],
        state = this[table.state],
        capacity = this[table.capacity],
        allocatedCount = this[table.allocatedCount],
        revision = this[table.revision],
        createdBy = this[table.createdBy],
        createdAt = this[table.createdAt],
        updatedBy = this[table.updatedBy],
        updatedAt = this[table.updatedAt],
    )

    fun tryReserve(tenantId: String, id: Long, expectedRevision: Long): Boolean {
        gate.requireHeld()
        return auditedUpdateAll({
            (table.tenantId eq tenantId) and (table.id eq id) and
                (table.state eq CampaignState.ACTIVE) and
                (table.revision eq expectedRevision) and
                (table.allocatedCount less table.capacity)
        }) {
            it[allocatedCount] = allocatedCount + 1
            it[revision] = expectedRevision + 1
        } == 1
    }

    companion object : KLogging()
}
```

모든 PK/FK/unique/index와 repository predicate에 `tenant_id`를 포함한다. campaign lock은 항상
claim/review보다 먼저 획득하고, `code_verifier`, redemption reference, active user claim에
PostgreSQL unique/check constraint를 둔다. `voucher_audits`는
`(tenant_id, aggregate_type, aggregate_id, revision)` append-only unique다.
모든 repository method는 Task 1의 `gate.requireHeld()`를 호출하고 test/application/query/worker는
`VoucherJdbcExecutor` lane을 통해 진입한다.

- [ ] **Step 4: versioned migration과 upgrade contract를 구현한다**

`V001__voucher_campaign.sql`은 schema history, campaign, claim, review, audit, inbox,
idempotency table과 query-plan용 composite index를 한 version으로 만든다. 이전 schema fixture와
expand/contract compatibility window를 함께 보존한다. migration test는 clean apply, same-checksum
no-op, altered checksum startup failure, advisory-lock contention instance가 winner 완료 뒤 checksum을
재검증하는 경로, partial DDL rollback 뒤 history 미기록/next-start recovery, previous-schema upgrade와
upgrade 뒤 previous-binary read/write compatibility를 검증한다. production startup에서
`SchemaUtils.create`를 호출하지 않는다.

이 예제는 checksum/advisory-lock/packaged rollback 계약을 직접 보여 주므로 사용하지 않는
`exposed-migration-jdbc` dependency는 선언하지 않는다. Exposed는 table/DSL/repository와 Spring
transaction integration에 사용한다.

packaged `bootJar` smoke는 clean start, warm restart, previous-schema upgrade, checksum failure,
rollback-compatible `PreviousVoucherBinaryMain`을 별도 JVM process로 실행한다. compatibility source
set은 production/main/test output에 compile/runtime dependency를 갖지 않고 JDK `java.sql` API만
사용한다. reproducible fixture JAR SHA-256은 checked-in `previous-binary.sha256`과 일치해야 하며
변경은 compatibility contract 변경으로 별도 review한다. tagged test는 Gradle이 만든 bootJar,
isolated previous-binary JAR, PostgreSQL driver 경로를 받아 `ProcessBuilder`로 같은
PostgreSQLServer에서 `previous write -> current bootJar migrate/read/write -> previous read/write`
순서를 실행한다. 이 smoke는 Task 12 final matrix에 포함하고 각 process exit code, pinned JAR
checksum, schema checksum, before/after row를 review artifact에 기록한다.

- [ ] **Step 5: PostgreSQL tests를 GREEN으로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherRepositoryTest' --tests '*VoucherMigrationRunnerTest' \
  --tests '*PreviousSchemaCompatibilityTest' \
  --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:migrationCompatibilityTest \
  --rerun-tasks --max-workers=1
```

Expected: PASS; capacity/check/unique/CAS/migration failures are PostgreSQL-authoritative.

- [ ] **Step 6: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: make PostgreSQL the voucher campaign authority" \
  -m $'Constraint: Every public lookup and invariant is tenant scoped.\nConfidence: high\nScope-risk: broad\nTested: PostgreSQL repository, upgrade, rollback, and migration contracts.'
```

### Task 4: Application-owned HTTP idempotency

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/IdempotencyFingerprint.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/HttpIdempotencyRepository.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/IdempotentVoucherCommandService.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/IdempotencyFingerprintTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/idempotency/HttpIdempotencyRepositoryTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/IdempotentVoucherCommandServiceTest.kt`

- [ ] **Step 1: canonical fingerprint RED tests를 작성한다**

```kotlin
@Test
fun `header case field order and schema default normalize to one fingerprint`() {
    val first = fingerprint(headers("Content-Type" to "application/json"), "{\"userRef\":\"u1\"}")
    val second = fingerprint(headers("content-type" to "application/json"), "{\"userRef\":\"u1\",\"forceReview\":false}")
    assertEquals(first, second)
}

@Test
fun `same key with different semantic payload conflicts`() {
    val repository = HttpIdempotencyRepository(gate)
    val owner = jdbc.foregroundTransaction {
        repository.acquire(scope("key-1"), fingerprint("u1"), fixedNow)
    }
    val conflict = jdbc.foregroundTransaction {
        repository.acquire(scope("key-1"), fingerprint("u2"), fixedNow)
    }
    assertIs<IdempotencyAcquireResult.FingerprintConflict>(conflict)
    assertIs<IdempotencyAcquireResult.Owner>(owner)
}
```

- [ ] **Step 2: crash-window repository RED tests를 작성한다**

```kotlin
@Test
fun `expired lease takeover rejects stale finalize`() {
    withTables(TestDB.POSTGRESQL, HttpIdempotencyTable) {
        val repository = HttpIdempotencyRepository(gate)
        val first = jdbc.foregroundTransaction {
            repository.acquire(scope("key"), fingerprint("u"), instant("10:00:00")) as Owner
        }
        val second = jdbc.foregroundTransaction {
            repository.acquire(scope("key"), fingerprint("u"), instant("10:01:31")) as Owner
        }
        jdbc.foregroundTransaction {
            assertFalse(repository.finalize(first.ownerToken, terminalResponse()))
            assertTrue(repository.finalize(second.ownerToken, terminalResponse()))
        }
    }
}
```

- [ ] **Step 3: targeted tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*IdempotencyFingerprintTest' \
  --tests '*HttpIdempotencyRepositoryTest' \
  --tests '*IdempotentVoucherCommandServiceTest' --max-workers=1
```

Expected: FAIL because fingerprint/acquire/finalize orchestration is absent.

- [ ] **Step 4: fingerprint와 lease/CAS state machine을 구현한다**

```kotlin
sealed interface IdempotencyAcquireResult {
    data class Owner(val ownerToken: OwnerToken, val leaseUntil: Instant) : IdempotencyAcquireResult
    data class Replay(val response: StoredHttpResponse) : IdempotencyAcquireResult
    data class InProgress(val retryAfter: Duration) : IdempotencyAcquireResult
    data object FingerprintConflict : IdempotencyAcquireResult
}

@JvmInline
value class Digest private constructor(val base64Url: String) {
    companion object { fun of(bytes: ByteArray): Digest = Digest(base64UrlNoPadding(bytes.copyOf())) }
}

@JvmInline
value class OwnerToken private constructor(val base64Url: String) {
    companion object { fun random(random: SecureRandom): OwnerToken = OwnerToken(randomToken(random)) }
}

data class StoredHttpResponse(
    val responseKind: VoucherResponseKind,
    val status: Int,
    val headers: Map<String, String>,
    val aggregateId: UUID,
    val allocationId: UUID?,
    val aggregateRevision: Long,
    val generationKeyVersion: Int?,
    val verificationKeyVersion: Int?,
)

data class IdempotencyScope(
    val tenantId: String,
    val principalDigest: Digest,
    val operation: String,
    val resourceId: String,
    val keyDigest: Digest,
)
```

digest/token value class는 입력 byte array를 복사한 뒤 canonical Base64URL-no-padding으로
검증·봉인하므로 mutable array/reference equality가 persistence key나 CAS 의미에 들어오지 않는다.

fingerprint는 normalized HTTP method/path/resource id와 `Content-Type`, `X-Workshop-Tenant`,
`X-Workshop-Principal` semantic header allowlist를 포함한다. closed DTO default 적용, UTF-8 key
order, canonical decimal, omitted/null rule을 golden fixture로 고정한다. repository는 owner token
digest, lease, command deadline, fingerprint와 closed response kind/status/bounded header/aggregate
id/revision/key-version reconstruction descriptor만 저장한다. arbitrary JSON과 plaintext code는
저장하지 않는다. replay는 descriptor로 closed DTO를 재구성하고 code가 필요한 allocation/
acknowledgement만 allocation id와 generation key로 다시 생성해 DB verifier와 constant-time 비교한다.

- [ ] **Step 5: service transaction 순서와 failure injection을 구현한다**

`IdempotentVoucherCommandService.execute` 순서는 `foreground permit -> terminal replay lookup ->
permit 반환 -> Redis/risk -> foreground permit -> acquire short transaction -> permit 반환 ->
foreground permit -> business transaction(campaign -> claim -> review -> idempotency owner/lease
recheck + terminal finalize -> audit/publication) -> commit -> permit 반환 -> response`다. 어느 JDBC
connection/transaction도 permit보다 먼저 열지 않는다. business mutation과 terminal finalize는
같은 transaction이다. commit 직전 crash는 모두 rollback하고 commit 직후 response 전 crash는
같은 key replay가 동일 status/header/body/code를 재구성한다. allocation과 redemption 각각에서
effect/audit/event가 하나뿐임을 injectable cut-point로 검증한다. acquire 후 crash, lease takeover,
stale finalize도 별도 fixture로 둔다. generation key가 누락된 terminal replay는 새 effect 없이
`IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE`로 fail closed한다. response-loss test는 raw code bytes가
claim table뿐 아니라 `http_idempotency` row에도 없음을 검증한다.

| Outcome category | Idempotency row disposition | Same-key proof |
|---|---|---|
| `RATE_LIMITED`, `DATABASE_BULKHEAD_REJECTED`, `AUTHORITATIVE_BACKEND_UNAVAILABLE`, `CAMPAIGN_PAUSED` | owner를 terminal finalize하지 않고 release/delete하여 recovery 뒤 재획득 허용 | rate window/DB/Redis/campaign recovery 뒤 same key가 effect 최대 한 번 성공 |
| `COMMAND_IN_PROGRESS` | active owner/lease 유지 | lease 안 retry는 bounded 409, takeover 뒤 한 owner만 진행 |
| `IDEMPOTENCY_REPLAY_KEY_UNAVAILABLE` | committed reconstruction descriptor 유지, 새 owner/effect 금지 | key 복구 전 503, 복구 뒤 original response replay |
| terminal state/policy conflict와 successful response | closed descriptor로 terminal finalize | same key는 status/header/closed DTO를 그대로 replay |
| `STALE_REVISION` | 412 descriptor terminal finalize | caller가 fresh snapshot과 새 key를 사용하고 old key는 412 replay |

test는 paused -> resume -> same key, backend/bulkhead/rate recovery, terminal conflict replay,
missing replay key -> key restore를 allocation과 redemption에 모두 적용한다.

- [ ] **Step 6: tests를 GREEN으로 확인한다**

Run: Step 3과 동일.

Expected: PASS for same/same replay, same/different conflict, in-progress, takeover, stale finalize,
cleanup, retryable-owner release/reacquire, paused/backend recovery, terminal conflict, missing replay
key preservation/recovery, raw code absence, and raw-key redaction.

- [ ] **Step 7: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: make voucher commands retry safe" \
  -m $'Constraint: Unpublished common idempotency modules cannot block the example.\nRejected: A generic shared store | Application evidence must come first\nConfidence: high\nScope-risk: broad\nTested: Atomic business-finalize and PostgreSQL crash-window tests.'
```

### Task 5: Allocation, review, redemption, and terminal commands

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/AllocationService.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ClaimCommandService.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ReviewCommandService.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/AllocationServiceTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ClaimCommandServiceTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/ReviewCommandServiceTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/VoucherConcurrencyIntegrationTest.kt`

- [ ] **Step 1: allocation/review RED tests를 작성한다**

```kotlin
@Test
fun `immediate allocation reserves one capacity and returns one opaque code`() {
    val result = service.allocate(command(userRef = "user-1"), fixedContext())
    assertEquals(ClaimState.ALLOCATED, result.state)
    assertNotNull(result.oneTimeCode)
    assertEquals(1, campaigns.snapshot(result.campaignId).allocatedCount)
}

@Test
fun `Bloom positive opens allocation review without reserving capacity`() {
    risk.stub(RiskSignal.REVIEW)
    val result = service.allocate(command(userRef = "user-1"), fixedContext())
    assertEquals(ClaimState.REVIEW_REQUIRED, result.state)
    assertEquals(0, campaigns.snapshot(result.campaignId).allocatedCount)
}
```

- [ ] **Step 2: redemption/release/revoke race RED tests를 작성한다**

```kotlin
@Test
fun `redeem and revoke race has exactly one authoritative winner`() {
    val claim = allocatedClaimFixture()
    val outcomes = ConcurrentLinkedQueue<Result<ClaimSnapshot>>()
    MultithreadingTester()
        .workers(2)
        .rounds(1)
        .add { outcomes += runCatching { commands.redeem(redeem(claim)) } }
        .add { outcomes += runCatching { commands.revoke(revoke(claim)) } }
        .run()
    assertEquals(1, outcomes.count { it.isSuccess })
    assertEquals(1, audits.countForRevision(claim.claimId, claim.revision + 1))
    assertCapacityInvariant()
}

@Test
fun `lost allocation and redemption responses replay the committed terminal response`() {
    val allocation = loseResponseAfterCommit { allocate(key = "allocate-key") }
    assertEquals(allocation.committedBody, allocate(key = "allocate-key").body)
    val redemption = loseResponseAfterCommit { redeem(allocation.code, key = "redeem-key") }
    assertEquals(redemption.committedBody, redeem(allocation.code, key = "redeem-key").body)
    assertEquals(1, allocationEffectCount(allocation.claimId))
    assertEquals(1, redemptionEffectCount(allocation.claimId))
}

@ParameterizedTest
@MethodSource("canonicalRaces")
fun `canonical lock races complete after barrier release without retry`(race: CommandRace) {
    val outcomes = race.runWithBarrierRelease()
    assertTrue(outcomes.all { it.isAuthoritativeSuccessOrConflict })
    assertEquals(0, outcomes.count { it.isLockTimeout })
    assertCapacityInvariant()
}

@Test
fun `blocked lock convoy times out and returns both connections and permits`() {
    holdCampaignLockOnFirstConnection()
    val failure = runSecondConnectionWithLockTimeout(Duration.ofSeconds(5))
    assertIs<PostgresLockTimeout>(failure)
    releaseFirstConnection()
    assertEquals(0, openTestConnections())
    assertEquals(12, permits.available(DatabaseLane.FOREGROUND))
}
```

- [ ] **Step 3: targeted tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*AllocationServiceTest' --tests '*ClaimCommandServiceTest' \
  --tests '*ReviewCommandServiceTest' --tests '*VoucherConcurrencyIntegrationTest' \
  --max-workers=1
```

Expected: FAIL because command services are absent.

- [ ] **Step 4: allocation transaction을 구현한다**

`AllocationService`는 한 transaction에서 campaign을 `FOR UPDATE`하고 period/state/policy,
per-user active claim, remaining capacity를 재검사한다. deterministic/Bloom signal이 review면
`REVIEW_REQUIRED(ALLOCATION, capacity_reserved=false)`와 audit만 기록한다. immediate path는
conditional campaign `allocated_count + 1`, claim/code verifier/audit를 함께 기록한다. code
generation은 transaction 밖 사전 계산하되 저장되는 값은 verifier와 key version뿐이다. 모든
foreground command는 `VoucherJdbcExecutor.foregroundTransaction`으로 DB permit을 먼저 획득하고
그 뒤 connection/transaction을 열며 transaction 시작 즉시 `SET LOCAL lock_timeout = '5s'`를
적용한다.

- [ ] **Step 5: review와 claim command transaction을 구현한다**

```kotlin
fun approve(command: ReviewDecisionCommand): ClaimSnapshot = jdbc.foregroundTransaction {
    val campaign = campaigns.findByPublicIdForUpdate(command.tenantId, command.campaignId)
    val claim = claims.findByPublicIdForUpdate(command.tenantId, command.claimId)
    val review = reviews.findOpenForUpdate(command.tenantId, command.reviewId)
    require(review.revision == command.expectedReviewRevision)
    applyReviewDecision(campaign, claim, review, approved = true)
}
```

redeem은 code verifier unique lookup 후 campaign -> claim lock, expiry/revoke/state/reference를
검사한다. release/expire/revoke는 `capacity_reserved=true`일 때만 counter를 정확히 한 번
감소시킨다. redemption review reject는 `ALLOCATED`로 복귀하고 counter를 바꾸지 않는다.
canonical race test는 latch/barrier로 pause/allocation, pause/redemption, redeem/revoke,
review/expiry cut-point를 결정적으로 교차시킨 뒤 barrier를 풀어 양쪽이 retry/lock-timeout 없이
authoritative success/conflict로 종료하는지 검증한다. 별도 convoy test는 두 PostgreSQL
connection 중 첫 번째가 campaign lock을 계속 보유하고 두 번째 transaction이 5초 안에 lock
timeout으로 실패한 뒤 두 connection과 foreground permit이 모두 반환되는지만 검증한다.

- [ ] **Step 6: concurrency tests를 GREEN으로 확인한다**

Run: Step 3과 동일.

Expected: PASS for capacity N storm, same-user limit, pause/allocation, redeem/revoke,
release/expiry, allocation/redemption review and canonical lock order. PostgreSQL lock timeout is
5 seconds, response-loss replay duplicates no effect, and no test retries are used to hide deadlocks.

- [ ] **Step 7: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: preserve capacity across voucher command races" \
  -m $'Directive: Lock campaign before claim and review in every capacity-changing transaction.\nConfidence: high\nScope-risk: broad\nTested: PostgreSQL command, response-loss, and deterministic concurrency tests.'
```

### Task 6: Always-on JDBC permits, Redis admission, and Bloom risk

**Files:**
- Modify: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/DatabasePermitGate.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/VoucherAdmissionGate.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/RiskSignalService.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherRedisConfiguration.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/DatabasePermitGateTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/VoucherAdmissionGateTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/admission/LettuceVoucherAdmissionIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/RedisUnavailableBootIntegrationTest.kt`

- [ ] **Step 1: permit leak와 Redis authority RED tests를 작성한다**

```kotlin
@Test
fun `cancelled foreground work returns only the local JDBC permit`() {
    val gate = DatabasePermitGate(acquireTimeout = Duration.ofMillis(250))
    assertFailsWith<CancellationException> {
        gate.withPermit(DatabaseLane.FOREGROUND) { throw CancellationException() }
    }
    assertEquals(12, gate.availableForegroundPermits())
}

@Test
fun `three SSE maintenance queries cannot starve the reserved worker lane`() {
    occupyAllSseMaintenancePermits()
    val started = AtomicBoolean(false)
    val worker = thread {
        gate.withPermit(DatabaseLane.WORKER) { started.set(true) }
    }
    await().atMost(Duration.ofSeconds(2)).untilTrue(started)
    worker.join()
}

@Test
fun `JDBC connection opens only after permit and nested acquisition fails fast`() {
    assertEquals(0, dataSourceProbe.openCount)
    jdbc.foregroundQuery { assertEquals(1, dataSourceProbe.openCount) }
    assertFailsWith<IllegalStateException> {
        gate.withPermit(DatabaseLane.FOREGROUND) {
            gate.withPermit(DatabaseLane.FOREGROUND) { Unit }
        }
    }
    assertEquals(12, gate.availableForegroundPermits())
}

@Test
fun `Redis flush never changes a committed PostgreSQL allocation`() {
    val first = allocateThroughRedis("same-user")
    redis.commands().flushall()
    val replay = allocateThroughRedis("same-user")
    assertEquals(first.claimId, replay.claimId)
    assertCapacityInvariant()
}
```

- [ ] **Step 2: targeted tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*DatabasePermitGateTest' --tests '*VoucherAdmissionGateTest' \
  --tests '*LettuceVoucherAdmissionIntegrationTest' \
  --tests '*RedisUnavailableBootIntegrationTest' --max-workers=1
```

Expected: FAIL because distributed admission and the permit progress/connection-order proofs are absent.

- [ ] **Step 3: always-on permit와 admission outcome을 구현한다**

```kotlin
enum class AdmissionState { HEALTHY, DEGRADED, RECOVERING }
sealed interface AdmissionDecision {
    data object Proceed : AdmissionDecision
    data class RateLimited(val retryAfter: Duration) : AdmissionDecision
    data class DatabaseBusy(val retryAfter: Duration) : AdmissionDecision
}

data class AdmissionRecoveryPolicy(
    val failureThreshold: Int = 3,
    val recoverySuccessThreshold: Int = 3,
    val probeInterval: Duration = Duration.ofSeconds(1),
    val maxInFlightProbes: Int = 1,
)
```

Task 1의 three-lane gate를 유지한다. 모든 repository query와 transaction은 replay lookup을 포함해
permit 안에서만 실행한다. permit은 query/transaction 동안만 보유하며 Redis I/O, SSE write,
backoff 중에는 보유하지 않는다. connection acquire 전 permit, connection close 뒤 정확히 한 번
release, cancellation/exception/nested acquisition을 test probe로 검증한다.

- [ ] **Step 4: Bluetape Lettuce/Bucket4j와 Bloom advisory path를 구현한다**

`LettuceClients`로 optional client/connection lifecycle을 만들고
`bluetape4k-bucket4j`의 `lettuceBasedProxyManagerOf`, `BucketProxyProvider`,
`DistributedRateLimiter`로 versioned HMAC digest key quota를 적용한다. `bluetape4k-lettuce`의
`LettuceBloomFilter`와 `BloomFilterOptions`로 risk digest filter를 구성한다. 소비된 token은
반환하지 않는다. Bloom positive는 review signal일 뿐 거절/발급
권위가 아니다. timeout/unknown/corrupt는 `UNKNOWN`으로 기록하고 local permit 아래 PostgreSQL로
진행한다. runtime property는 failure threshold 3, recovery success 3, probe interval 1초,
max in-flight probe 1을 기본값으로 두고 범위를 startup에서 검증한다. fixed `Clock`으로
HEALTHY -> DEGRADED -> RECOVERING -> HEALTHY, RECOVERING 중 failure의 DEGRADED 복귀, flapping
single-flight와 모든 전이 중 DB permit 적용을 검증한다.
하나의 `RedisClient` 아래 admission/Bucket4j, Bloom, leader용
`StatefulRedisConnection`을 각각 dedicated ownership으로 만든다. component는 다른 component의
connection을 닫지 않는다. shutdown은 admission probe 중지 -> leader lease release -> leader
connection close -> Bloom connection close -> admission connection close -> RedisClient close 순서이며 connection
identity/close-once test로 고정한다.

- [ ] **Step 5: RedisServer integration을 GREEN으로 확인한다**

Run: Step 2와 동일.

Expected: PASS for rate quota, Bloom false positive, timeout, corrupt data, flush, outage,
hysteresis recovery, permit cancellation and client/connection shutdown.

- [ ] **Step 6: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: bound Redis assisted voucher admission" \
  -m $'Constraint: Redis may reduce load but cannot authorize voucher state.\nRejected: Returning consumed rate tokens | Quota consumption is final\nConfidence: high\nScope-risk: broad\nTested: Three-lane permit and RedisServer failure tests.'
```

### Task 7: Durable publication, delayed inbox, reconciliation, and leader trigger

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherPublicationConfiguration.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationService.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationWorker.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationServiceTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/reconciliation/VoucherReconciliationWorkerTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherContextRestartIntegrationTest.kt`

- [ ] **Step 1: duplicate/out-of-order/restart RED tests를 작성한다**

```kotlin
@Test
fun `duplicate delayed event is applied once and reported as ignored on replay`() {
    val first = reconciliation.accept(delayedEvent(eventId = "event-1", sequence = 3))
    val second = reconciliation.accept(delayedEvent(eventId = "event-1", sequence = 3))
    assertEquals(InboxOutcome.APPLIED, first.outcome)
    assertEquals(InboxOutcome.IGNORED, second.outcome)
    assertCapacityInvariant()
}

@Test
fun `worker overlap claims each inbox row once`() {
    seedPendingInbox(75)
    MultithreadingTester().workers(2).rounds(1)
        .add { reconciliation.runBatch(50, Duration.ofSeconds(10)) }
        .add { reconciliation.runBatch(50, Duration.ofSeconds(10)) }
        .run()
    assertEquals(75, terminalInboxCount())
    assertEquals(75, distinctAppliedEventCount())
}

@Test
fun `poison row backs off without starving the next row and becomes failed after five attempts`() {
    val clock = MutableClock(fixedInstant)
    seedInbox(poison(eventId = "poison"), valid(eventId = "next"))
    repeat(5) { attempt ->
        reconciliation.runBatch(batchSize = 50, deadline = Duration.ofSeconds(10))
        assertEquals(InboxState.APPLIED, inboxState("next"))
        assertEquals(attempt + 1, inboxAttempt("poison"))
        if (attempt < 4) {
            assertEquals(clock.instant() + expectedBackoff(attempt + 1), nextAttemptAt("poison"))
            assertEquals(InboxState.PENDING, inboxState("poison"))
        }
        clock.advance(expectedBackoff(attempt + 1))
    }
    assertEquals(InboxState.FAILED, inboxState("poison"))
}

@Test
fun `zero deadline claims no inbox row`() {
    seedPendingInbox(3)
    assertTrue(reconciliation.runBatch(50, Duration.ZERO).deadlineReached)
    assertEquals(0, claimedAfterDeadlineCount())
    assertEquals(3, pendingInboxCount())
}

@Test
fun `effect rollback is reprocessed exactly once by a fresh context`() {
    seedPendingInbox(1)
    injectFailureAfterEffectBeforeInboxFinalize()
    assertFailsWith<InjectedWorkerFailure> {
        reconciliation.runBatch(50, Duration.ofSeconds(10))
    }
    assertEquals(0, persistedEffectCount())
    assertEquals(InboxState.PENDING, inboxStateForSeed())
    restartApplicationContext()
    assertEquals(1, reconciliation.runBatch(50, Duration.ofSeconds(10)).processed)
    assertEquals(1, persistedEffectCount())
    assertEquals(1, distinctAppliedEventCount())
}

@Test
fun `row transaction cannot commit after a positive run deadline`() {
    seedPendingInbox(1)
    val blocked = blockEffectTransaction()
    val run = async { reconciliation.runBatch(50, Duration.ofMillis(200)) }
    blocked.awaitStarted()
    clock.advance(Duration.ofMillis(201))
    blocked.release()
    assertTrue(run.await().deadlineReached)
    assertEquals(0, persistedEffectCount())
    assertEquals(InboxState.PENDING, inboxStateForSeed())
    restartApplicationContext()
    assertEquals(1, reconciliation.runBatch(50, Duration.ofSeconds(10)).processed)
}
```

- [ ] **Step 2: targeted tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherReconciliationServiceTest' \
  --tests '*VoucherReconciliationWorkerTest' \
  --tests '*VoucherContextRestartIntegrationTest' --max-workers=1
```

Expected: FAIL because publication/inbox/worker components are absent.

- [ ] **Step 3: Exposed Modulith publication repository를 wire한다**

`commerce/order-lifecycle-fulfillment/config/SpringModulithPublicationConfiguration.kt`의
`SpringTransactionManager`, `ExposedEventPublicationRepository`, completion mode UPDATE 패턴을
재사용한다. application service가 Spring transaction 안에서 stable event id/aggregate revision을
publish하고 listener가 duplicate revision을 무효화한다.

- [ ] **Step 4: bounded reconciliation을 구현한다**

```kotlin
data class ReconciliationResult(
    val processed: Int,
    val skipped: Int,
    val failed: Int,
    val lastCursor: String?,
    val deadlineReached: Boolean,
)

fun runBatch(batchSize: Int = 50, deadline: Duration = Duration.ofSeconds(10)): ReconciliationResult
```

query는 `FOR UPDATE SKIP LOCKED ORDER BY next_attempt_at, id LIMIT 50`이다. 각 row의 claim/effect/
inbox result를 한 transaction에 기록하고 최대 5회 exponential backoff 뒤 `FAILED`로 남긴다.
각 row claim 전에 남은 wall-clock budget을 계산하고 0 이하이면 더 claim하지 않는다. transaction
timeout은 `min(remainingBudget, configuredTransactionTimeout)`으로 설정해 deadline 뒤 commit을
막는다. poison row rollback은 다음 row를 막지 않고 fixed `Clock`으로 backoff를 검증한다.
operator 호출은 synchronous `200 + counts/cursor/deadlineReached`다.

- [ ] **Step 5: leader-selected worker를 구현한다**

`LettuceLeaderElector`와 `LeaderSlot("voucher-reconciliation", instanceId)`를 사용한다.
leader election 실패는 correctness 실패가 아니며 operator/manual run이 같은 service를 재사용한다.
local single-flight가 scheduler/operator overlap을 막고 background permit 1개만 worker에 예약한다.

- [ ] **Step 6: restart/worker tests를 GREEN으로 확인한다**

Run: Step 2와 동일.

Expected: PASS for failed publication replay, delayed/duplicate/out-of-order inbox, poison item
non-starvation, exact five-attempt backoff/FAILED transition, batch 50, deadline 10 seconds with no
post-deadline claim, leader elected/skipped/backend failure, rollback reprocessing, and a fresh
application context applying every durable event exactly once.

- [ ] **Step 7: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: reconcile voucher events without changing authority" \
  -m $'Constraint: Leader election chooses workers; PostgreSQL CAS decides effects.\nConfidence: high\nScope-risk: broad\nTested: Publication, deadline, poison, leader, rollback, and restart tests.'
```

### Task 8: Live customer/operator HTTP contracts and redacted logging

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/AbstractVoucherIntegrationTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/query/VoucherQueryService.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/CustomerVoucherController.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorVoucherController.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorAccessFilter.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/RequestLoggingFilter.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/ApiExceptionHandler.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherHttpProperties.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/CustomerVoucherWebIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorVoucherWebIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherScopeIsolationIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/OperatorAccessFilterIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherInputBoundaryIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherRuntimeIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/application/VoucherOperationalLoggingTest.kt`

- [ ] **Step 1: PostgreSQLServer와 live WebTestClient base를 작성한다**

```kotlin
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Execution(ExecutionMode.SAME_THREAD)
internal abstract class AbstractVoucherIntegrationTest {
    @LocalServerPort protected var port: Int = 0

    protected val webTestClient: WebTestClient by lazy {
        val connector = JdkClientHttpConnector().apply { setReadTimeout(Duration.ofSeconds(60)) }
        WebTestClient.bindToServer(connector)
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(60))
            .build()
    }

    companion object {
        val postgres: PostgreSQLServer = PostgreSQLServer.Launcher.postgres
        @JvmStatic @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username ?: PostgreSQLServer.USERNAME }
            registry.add("spring.datasource.password") { postgres.password ?: PostgreSQLServer.PASSWORD }
        }
    }
}
```

- [ ] **Step 2: safe GET와 acknowledgement RED tests를 작성한다**

```kotlin
@Test
fun `claim GET is side effect free and code acknowledgement replays after response loss`() {
    val claim = approveAllocationReview()
    repeat(2) { getClaim(claim.claimId).expectStatus().isOk }
    assertEquals(0, acknowledgementAuditCount(claim.claimId))
    val first = acknowledgeCode(claim.claimId, key = "ack-key")
        .expectStatus().isOk.expectBody(CodeResponse::class.java).returnResult().responseBody!!
    val replay = acknowledgeCode(claim.claimId, key = "ack-key")
        .expectStatus().isOk.expectBody(CodeResponse::class.java).returnResult().responseBody!!
    assertEquals(first.code, replay.code)
    assertEquals(1, acknowledgementAuditCount(claim.claimId))
}

@Test
fun `code acknowledgement discloses only to the original key while claim remains active`() {
    val claim = approveAllocationReview()
    val original = acknowledgeCode(claim.claimId, key = "ack-original")
        .expectStatus().isOk.expectBody(CodeResponse::class.java).returnResult().responseBody!!
    acknowledgeCode(claim.claimId, key = "ack-original")
        .expectStatus().isOk.expectBody().jsonPath("$.code").isEqualTo(original.code)
    acknowledgeCode(claim.claimId, key = "ack-new")
        .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("CODE_ALREADY_ACKNOWLEDGED")
    expireClaim(claim.claimId)
    acknowledgeCode(claim.claimId, key = "ack-after-expiry").expectStatus().isEqualTo(409)
    revokeAnotherApprovedClaimAndAssertNoCodeDisclosure()
    assertEquals(1, acknowledgementAuditCount(claim.claimId))
}
```

- [ ] **Step 3: operator trust-boundary RED tests를 작성한다**

```kotlin
@Test
fun `operator create uses If-None-Match while review commands require expected revision`() {
    operatorPost("/operator/api/v1/campaigns", createBody(), headers = mapOf("If-None-Match" to "*"))
        .expectStatus().isCreated
    operatorPost("/operator/api/v1/reviews/$reviewId/approve", "{}")
        .expectStatus().isBadRequest
    operatorPost("/operator/api/v1/reviews/$reviewId/approve", approveBody(expectedRevision = 0))
        .expectStatus().isOk
}

@Test
fun `live request runs on Java 25 virtual thread with the configured Hikari bounds`() {
    webTestClient.get().uri("/internal/runtime-thread")
        .exchange().expectStatus().isOk
        .expectBody().jsonPath("$.virtual").isEqualTo(true)
    assertEquals(16, hikari.maximumPoolSize)
    assertEquals(4, hikari.minimumIdle)
    assertEquals(Duration.ofSeconds(60).toMillis(), hikari.connectionTimeout)
}

@Test
fun `invalid Hikari and permit configuration fails application startup`() {
    assertStartupFailsWith("spring.datasource.hikari.maximum-pool-size=17", "maximum-pool-size must be 16")
    assertStartupFailsWith("workshop.voucher.db.foreground-permits=13", "permit sum exceeds Hikari 16")
    assertStartupFailsWith("workshop.voucher.db.background-permits=3", "background must split as worker 1 plus SSE 3")
}

@Test
fun `browser and operator boundaries deny ambient credentials and unsafe content`() {
    webTestClient.get().uri("/").exchange().expectStatus().isOk
        .expectHeader().value("Content-Security-Policy") { csp ->
            assertTrue(csp.contains("default-src 'self'"))
            assertTrue(csp.contains("script-src 'self'"))
            assertTrue(csp.contains("object-src 'none'"))
            assertTrue(csp.contains("frame-ancestors 'none'"))
            assertFalse(csp.contains("'unsafe-inline'"))
            assertFalse(csp.contains("'unsafe-eval'"))
        }
        .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
        .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
        .expectHeader().valueMatches("Cache-Control", ".*no-store.*")
        .expectCookie().doesNotExist("JSESSIONID")
    operatorPostWithOrigin("https://untrusted.example").expectStatus().isForbidden
    oversizedOrUnknownPropertyRequest().expectStatus().isBadRequest
}

@Test
fun `operator reconciliation is idempotent single flight and synchronously bounded`() {
    val first = operatorPost(
        "/operator/api/v1/reconciliation/run",
        "{}",
        headers = mapOf("Idempotency-Key" to "reconcile-1"),
    ).expectStatus().isOk.expectBody(ReconciliationResponse::class.java).returnResult().responseBody!!
    val replay = operatorReconcileConcurrentlyWithScheduler("reconcile-1")
    assertEquals(first, replay.response)
    assertEquals(1, replay.effectfulRuns)
    assertTrue(first.processed + first.skipped + first.failed <= 50)
    assertNotNull(first.cursor)
    assertTrue(first.elapsed <= Duration.ofSeconds(10))
}

@ParameterizedTest
@MethodSource("scopeIsolationCases")
fun `tenant principal resource and idempotency scopes never cross`(case: ScopeIsolationCase) {
    case.seedTenantAAndB()
    val result = case.callThroughLiveTomcat()
    assertEquals(case.expectedStatus, result.status)
    assertEquals(case.expectedVisibleRows, result.visibleRows)
    assertEquals(0, result.crossScopeEffects)
    assertSameRawKeyCreatesIndependentOwnerRowsAcrossScopes(case)
}

@ParameterizedTest
@MethodSource("operatorGuardFailures")
fun `operator and fixture guards reject before controller effects`(case: OperatorGuardFailure) {
    val result = case.callThroughLiveTomcat()
    assertEquals(case.expectedStatus, result.status)
    assertEquals(0, operatorControllerInvocationCount())
    assertEquals(0, repositoryMutationCount())
}

@ParameterizedTest
@MethodSource("httpBoundaries")
fun `HTTP limits accept N and reject N plus one before backend access`(boundary: HttpBoundary) {
    boundary.atLimit().expectStatus().is2xxSuccessful
    boundary.aboveLimit().expectStatus().isBadRequest
    assertEquals("INVALID_REQUEST", boundary.aboveLimitErrorCode())
    assertEquals(0, backendInvocationCountForRejectedRequest())
}

@ParameterizedTest
@MethodSource("backendFailuresWithSecrets")
fun `backend failure responses and logs contain only sanitized categories`(failure: BackendFailure) {
    val forbidden = failure.injectCredentialBearingException()
    val response = failure.callThroughLiveTomcat()
    assertSanitizedErrorCode(response, failure.expectedCategory)
    assertCapturedLogsExclude(forbidden + failure.sqlParameters + failure.stackTraceSecrets)
}

@ParameterizedTest
@MethodSource("sameKeyRecoveryCases")
fun `retryable HTTP outcomes recover with exact replay and retry headers`(case: SameKeyRecoveryCase) {
    val first = case.firstCall()
    assertEquals("false", first.header("Idempotency-Replayed"))
    assertEquals(case.initialStatus, first.status)
    assertEquals(case.retryAfterSeconds, first.header("Retry-After")?.toLong())
    case.recoverBackendOrCampaign()
    val recovered = case.sameKeyCall()
    assertEquals(case.recoveredStatus, recovered.status)
    val replay = case.sameKeyCall()
    assertEquals("true", replay.header("Idempotency-Replayed"))
    assertEquals(recovered.closedBody, replay.closedBody)
    assertEquals(1, case.effectCount())
}

@ParameterizedTest
@MethodSource("stableErrorCatalog")
fun `every stable error is an executable live HTTP contract`(error: StableErrorCase) {
    val response = error.callThroughLiveTomcat()
    assertEquals(error.httpStatus, response.status)
    assertEquals(error.code, response.body.code)
    assertEquals(error.retryAfterSeconds, response.body.retryAfterSeconds)
    assertEquals(error.retryAfterHeader, response.header("Retry-After"))
    assertEquals(error.sameKeyRule, error.observedSameKeyRule())
    assertClosedSecretFreeErrorBody(response.body)
}
```

`sameKeyRecoveryCases`는 paused/resume, Redis rate-window recovery, DB permit recovery,
PostgreSQL/backend recovery, active-owner lease/takeover, missing replay key/key restore를 포함한다.
`stableErrorCatalog`는 approved spec의 모든 stable code를 status/retry/header/same-key/caller-action
tuple로 열거해 누락된 error mapping이 test discovery 단계에서 실패하도록 한다.

- [ ] **Step 4: live HTTP tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*CustomerVoucherWebIntegrationTest' \
  --tests '*OperatorVoucherWebIntegrationTest' \
  --tests '*VoucherScopeIsolationIntegrationTest' \
  --tests '*OperatorAccessFilterIntegrationTest' \
  --tests '*VoucherInputBoundaryIntegrationTest' \
  --tests '*VoucherRuntimeIntegrationTest' \
  --tests '*VoucherOperationalLoggingTest' --max-workers=1
```

Expected: FAIL because controllers, filters, errors, and query services are absent.

- [ ] **Step 5: customer routes와 stable response/error contract를 구현한다**

customer command는 `Idempotency-Key`, `X-Workshop-Tenant`, `X-Workshop-Principal`을 요구한다.
allocation은 `201` 또는 review `202`, redeem은 `200` 또는 review `202`, release와 code
acknowledgement는 `200`이다. GET은 pure snapshot이다. error body는 다음 closed DTO만 사용한다.

```kotlin
data class ApiError(
    val code: String,
    val reason: String,
    val requestId: String,
    val retryAfterSeconds: Long? = null,
)
```

`ApiExceptionHandler`는 malformed 400, tenant-scoped missing 404, state/idempotency 409, stale
revision 412, quota 429, DB/backend/SSE 503을 stable code와 `Retry-After`로 매핑한다.
`VoucherRuntimeIntegrationTest`는 실제 `HikariDataSource` bean의 max/min/connection timeout과
permit 합계를 읽고, live Tomcat controller/filter가 관찰한 `Thread.currentThread().isVirtual`이
true인지 검증한다. configuration validator는 Hikari 16, foreground 12, worker 1,
SSE-maintenance 3의 불일치나 0 이하 값을 application startup failure로 만든다.
`VoucherHttpProperties`는 request body 64KiB, scalar string 256 UTF-8 bytes, collection/page 100,
JSON nesting 8, tenant/principal/idempotency header 64 ASCII, canonical voucher code 28 ASCII,
opaque cursor 512 ASCII bytes를 고정한다. 각 N/N+1 live test는 unknown property, Unicode/control,
oversized header/body/code/cursor도 backend 진입 전 동일 sanitized 400으로 거부한다. scope matrix는
customer GET/command, operator list/mutation, SSE cursor/reconnect, idempotency replay를 tenant A/B ×
principal A/B × resource A/B로 실행하고 mismatch를 404/empty list/effect 0으로 고정한다.

- [ ] **Step 6: operator route별 precondition과 filter를 구현한다**

create는 `If-None-Match: *`, campaign/review/revoke는 expected revision, reconciliation/fixture는
idempotency key와 single-run/demo guard를 사용한다. 모든 operator route는 explicit tenant,
loopback, high-entropy configured secret digest, `X-Workshop-Guard`, strict Host/Origin allowlist,
JSON content type와 credential rate limit을 통과해야 한다. secret은 constant-time 비교하고
URL/body/log/metric에 남기지 않는다. browser/static response는 strict CSP, `nosniff`,
`Referrer-Policy: no-referrer`, `Cache-Control: no-store`, no-cookie 계약을 적용한다. CORS는
deny-by-default이고 Jackson default typing 없이 closed DTO, unknown-property rejection, bounded
header/body/string/collection/nesting limit를 사용한다.
`OperatorAccessFilterIntegrationTest`는 missing/wrong secret, missing guard, invalid Host,
absent/cross-site Origin, denied CORS preflight, wrong content type, credential flood, non-loopback,
non-demo/test fixture access를 parameterize하고 controller/repository effect 0을 검증한다. production
profile에서는 fixture bean 자체가 없고 customer DTO risk override를 거부하며 fixture reset은
선택한 demo tenant 외 row를 보존한다.

- [ ] **Step 7: bluetape4k-logging contract를 구현한다**

controller/filter, application service, repository, idempotency, Redis, worker, configuration/
lifecycle class는 `KLogging`과 lazy log extension을 사용한다. success, rejection, stale CAS,
duplicate, degraded transition, leader skip, retry/final failure를 bounded event name으로 기록한다.
test는 raw code, idempotency key, owner token, user/device/IP, operator secret이 captured output에
없음을 forbidden-value scan으로 검증한다.

- [ ] **Step 8: live HTTP/logging tests를 GREEN으로 확인한다**

Run: Step 4와 동일.

Expected: PASS through real Tomcat/Jackson/filter chain; no MockMvc class appears in test runtime.

- [ ] **Step 9: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: expose retry safe voucher HTTP contracts" \
  -m $'Constraint: GET remains safe and all operator mutations have route-specific preconditions.\nConfidence: high\nScope-risk: broad\nTested: Live WebTestClient, virtual-thread, Hikari, startup-validation, and logging-redaction tests.'
```

### Task 9: Snapshot-first SSE and accessible browser console

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherEventStream.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/resources/static/index.html`
- Create: `commerce/promotion-voucher-campaign/src/main/resources/static/app.js`
- Create: `commerce/promotion-voucher-campaign/src/main/resources/static/styles.css`
- Create: `docs/review/assets/issue-534-promotion-voucher-campaign/browser-smoke.json`
- Create: `docs/review/assets/issue-534-promotion-voucher-campaign/browser-smoke.png`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherEventStreamIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/web/VoucherBrowserContractTest.kt`

- [ ] **Step 1: SSE cursor/resource RED tests를 작성한다**

```kotlin
@Test
fun `retention gap resets from an authoritative snapshot`() {
    val events = openStream(lastEventId = staleCursor)
    assertEquals("snapshot", events.next().event())
    assertEquals("reset", events.next().event())
    assertEquals(currentRevision, events.last().id().substringBefore(':').toLong())
}

@Test
fun `last subscriber disconnect cancels the shared poller and returns permit`() {
    openStream().close()
    await().untilAsserted {
        assertEquals(0, stream.activePollers())
        assertEquals(3, permits.available(DatabaseLane.SSE_MAINTENANCE))
        assertEquals(1, permits.available(DatabaseLane.WORKER))
    }
}


@Test
fun `overflow prioritizes authoritative reset before close`() {
    val session = openSlowStream(queueCapacity = 1, prefill = auditEvent())
    session.overflow()
    assertEquals("reset", session.lastDeliveredEvent().event())
    assertEquals(session.lastAuthoritativeCursor(), session.lastDeliveredEvent().id())
    assertTrue(session.closedWithin(Duration.ofSeconds(5)))
}

@ParameterizedTest
@MethodSource("nonOverflowCleanupTriggers")
fun `terminal trigger uses one cleanup path and returns resources`(trigger: StreamTrigger) {
    val session = openSlowStream(queueCapacity = 32)
    trigger.fire(session)
    assertEquals(1, session.cleanupInvocationCount())
    assertEquals(0, session.referenceCount())
    assertEquals(0, session.queueDepth())
    assertEquals(0, stream.activePollersFor(session.campaignId))
    assertTrue(session.closedWithin(Duration.ofSeconds(5)))
    assertEquals(3, permits.available(DatabaseLane.SSE_MAINTENANCE))
}

@Test
fun `thirty third campaign is rejected while thirty two slow campaigns stay bounded`() {
    val streams = (1..32).map { openStream(campaignId = "campaign-$it", slowConsumer = true) }
    openStreamExchange(campaignId = "campaign-33")
        .expectStatus().isEqualTo(503)
        .expectHeader().valueEquals("Retry-After", "2")
        .expectHeader().valueEquals(
            "Link",
            "</api/v1/campaigns/campaign-33>; rel=\"alternate\"; type=\"application/json\"",
        )
        .expectBody().jsonPath("$.code").isEqualTo("SSE_CAPACITY_REJECTED")
    pollCampaignSnapshot("/api/v1/campaigns/campaign-33").expectStatus().isOk
    retryStreamAfterCapacityReturns(campaignId = "campaign-33").expectStatus().isOk
    assertTrue(streams.all { it.maxQueueDepth() <= 32 })
    assertTrue(stream.maxConcurrentDatabaseQueries() <= 3)
}
```

- [ ] **Step 2: browser accessibility RED contract를 작성한다**

```kotlin
@Test
fun `static console contains keyboard and live status contracts`() {
    val html = loadStatic("index.html")
    assertTrue(html.contains("aria-live=\"polite\""))
    assertTrue(html.contains("aria-label="))
    val js = loadStatic("app.js")
    assertFalse(js.contains("innerHTML"))
    assertTrue(js.contains("textContent"))
    assertTrue(js.contains("restoreFocus"))
    assertFalse(js.contains("localStorage"))
    assertFalse(js.contains("document.cookie"))
    assertTrue(js.contains("clearOperatorSecret"))
    assertSecretClearedOnRefreshAndNavigationWithoutDomOrHistoryCopy(js)
}
```

- [ ] **Step 3: SSE/browser tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherEventStreamIntegrationTest' \
  --tests '*VoucherBrowserContractTest' --max-workers=1
```

Expected: FAIL because the event stream and static console are absent.

- [ ] **Step 4: bounded shared-poller SSE를 구현한다**

event type은 `snapshot`, `audit`, `heartbeat`, `reset`, `error`; ID는 `revision:id`다.
`Last-Event-ID`, future/cross-tenant cursor rejection, retention reset, restart resume를 구현한다.
campaign poller max 32, interval 500ms, rows 200/256KiB, emitter queue 32, write deadline 5초,
SSE-maintenance permit 3을 적용하고 worker permit 1은 공유하지 않는다. 33번째 distinct campaign은
`SSE_CAPACITY_REJECTED` 503, `Retry-After: 2`, same-origin campaign GET `Link` alternate로 거부한다.
UI는 Link URL의 authoritative snapshot을 poll한 뒤 capacity가 반환되면 stream을 재시도한다.
idle poller는 consecutive empty read 뒤 최대 2초까지 backoff하고
activity가 생기면 500ms로 즉시 복귀한다. 한 query는 row 200과 encoded payload 256KiB 중 먼저
도달한 한계에서 멈추며 DB permit은 query/row materialization 동안만 보유하고 emitter queue write
중에는 반환한다. full queue에서는 authoritative last cursor를 가진 reset을 기존 audit/heartbeat보다
우선 전달하고 종료하며 heartbeat timeout, blocked write, disconnect, overflow, application shutdown이
하나의 idempotent cleanup 경로를 사용한다. blocked write는 cancel/interrupt 후 5초 안에 정리한다.

- [ ] **Step 5: accessible dependency-free UI를 구현한다**

UI는 campaign/capacity/policy, claim/review timeline, reconciliation backlog, Redis advisory와
PostgreSQL authority를 구분해 표시한다. same-origin header-capable `fetch` stream을 사용하고
tenant/principal/secret을 query string/localStorage/history에 넣지 않는다. operator secret은
masked password input에서 session memory로만 읽어 request header에 넣고 DOM text/body/cookie에
복제하지 않으며 refresh/navigation에서 폐기한다. keyboard navigation, focus restoration,
`aria-live`, accessible name, text/icon/state label을 제공하고 모든 untrusted 값은
`textContent`로만 렌더링한다. destructive operator action은 confirmation dialog를 거치고 stale
412는 최신 snapshot으로 expected revision/form을 갱신하며 불가능한 action은 disabled reason을
표시한다. SSE 503/연속 reconnect 실패는 `Retry-After`를 존중하는 bounded polling fallback으로
전환하고 authoritative snapshot을 읽은 뒤 stream을 재시도한다.

- [ ] **Step 6: SSE/browser tests를 GREEN으로 확인한다**

Run: Step 3과 동일.

Expected: PASS for snapshot/cursor/reconnect/reset priority, 33rd-campaign 503, idle backoff capped at
2 seconds, 200-row/256KiB query bounds, query-only permit ownership, 32-campaign slow consumers,
parameterized heartbeat/write/disconnect/overflow/shutdown cleanup, and static accessibility/security
contracts.

- [ ] **Step 7: 실제 browser smoke를 수행한다**

Run the application on loopback with the deterministic demo profile:

```bash
./gradlew :commerce-promotion-voucher-campaign:bootRun \
  --args='--spring.profiles.active=demo --server.address=127.0.0.1 --server.port=18080'
```

별도 terminal/session에서 `$playwright` skill로 `http://127.0.0.1:18080`을 실제 browser에서 연다.
mouse 없이 Tab/Shift+Tab/Enter/Escape로 customer/operator flow를 실행하고 tab order, accessible
name, confirmation cancel/accept, stale 412 snapshot refresh와 revision replacement, disabled action
reason, modal focus restoration, SSE snapshot/reset/error `aria-live` announcement, 33번째 campaign
503의 Link polling fallback과 stream retry를 검증한다. raw operator secret이 DOM/storage/history/
cookie에 없는지 browser evaluation으로 확인한다. exact steps/result/browser version을
`browser-smoke.json`, final accessible state screenshot을 `browser-smoke.png`에 기록한다.
같은 browser session에서 fixture catalog를 parameterize해 happy allocation/redemption,
same-key response loss, capacity race, allocation/redemption review, pause/allocation,
redeem/revoke, policy change, Redis outage, Bloom false positive, delayed/duplicate/out-of-order event를
각각 run/reset하고 documented expected state/audit/SSE summary와 일치하는지 확인한다.

Expected: 모든 interaction과 accessibility/security assertion PASS; 실패하면 static contract만으로
대체하지 않고 UI를 수정한 뒤 실제 browser smoke를 다시 수행한다.

- [ ] **Step 8: commit한다**

```bash
git add commerce/promotion-voucher-campaign docs/review/assets/issue-534-promotion-voucher-campaign
git commit -m "feat: make voucher recovery visible in the browser" \
  -m $'Constraint: SSE is bounded by its three DB permits and must recover from an authoritative snapshot.\nConfidence: high\nScope-risk: broad\nTested: Live SSE lifecycle, slow-consumer cleanup, capacity, and browser contract tests.'
```

### Task 10: Health, metrics, shutdown, key rotation, and deterministic fixtures

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherHealthIndicators.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherLifecycle.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMetrics.kt`
- Create: `commerce/promotion-voucher-campaign/src/main/kotlin/io/bluetape4k/workshop/commerce/voucher/fixture/VoucherScenarioFixtures.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherHealthIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherLifecycleIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherMetricsContractTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherKeyRotationIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherRetentionIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/config/VoucherBackupRestoreIntegrationTest.kt`
- Test: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/fixture/VoucherScenarioFixturesTest.kt`

- [ ] **Step 1: readiness/degraded/lifecycle RED tests를 작성한다**

```kotlin
@Test
fun `Redis outage is degraded while PostgreSQL outage removes readiness`() {
    stopRedis()
    health().expectStatus().isOk.expectBody().jsonPath("$.components.redis.status").isEqualTo("DEGRADED")
    assertLeaderAndBloomFailureRemainReadyAndDegraded()
    stopPostgresProxy()
    readiness().expectStatus().is5xxServerError
    liveness().expectStatus().isOk
}

@Test
fun `management surface is loopback only and exposes the explicit allowlist`() {
    assertEquals("127.0.0.1", managementAddress())
    assertNotEquals(applicationPort(), managementPort())
    applicationGet("/actuator/health").expectStatus().isNotFound
    managementGet("/actuator/health").expectStatus().isOk
    managementGet("/actuator/prometheus").expectStatus().isOk
    assertEquals(
        setOf("health", "readiness", "liveness", "prometheus"),
        managementActuatorRootLinks(),
    )
    listOf("env", "configprops", "heapdump", "threaddump").forEach {
        managementGet("/actuator/$it").expectStatus().isNotFound
    }
}

@Test
fun `shutdown releases leader before closing Redis and rejects new commands`() {
    val inFlight = startBlockedDatabaseCommand()
    val blockedSse = startBlockedSseWrite()
    val closeThread = Thread.ofPlatform()
        .name("voucher-context-close-test")
        .start { applicationContext.close() }
    lifecycle.awaitEvent("await-db", Duration.ofSeconds(5))
    readiness().expectStatus().is5xxServerError
    assertNewCommandReturns503()
    assertNewSseReturns503()
    releaseBlockedDatabaseCommand()
    closeThread.join(Duration.ofSeconds(30))
    assertFalse(closeThread.isAlive)
    assertEquals(
        listOf(
            "readiness-down",
            "reject-new",
            "stop-worker-trigger",
            "await-db",
            "stop-sse",
            "release-leader",
            "close-leader-redis",
            "close-bloom-redis",
            "close-admission-redis",
            "close-redis-client",
            "close-executor",
            "close-datasource",
        ),
        lifecycle.events(),
    )
    assertTrue(inFlight.completedOrCancelledWithin(Duration.ofSeconds(30)))
    assertTrue(blockedSse.cancelledWithin(Duration.ofSeconds(5)))
    assertEquals(0, permits.inUse())
    assertTrue(lifecycle.applicationVirtualExecutorTerminated())
    assertEquals(0, lifecycle.snapshotBeforeDatasourceClose().activeConnections)
    assertNoSensitiveValueInShutdownReason()
}

@Test
fun `forced shutdown uses injected deadline and redacted bounded reason`() {
    startBlockedDatabaseCommand()
    lifecycle.setGraceDeadlineForTest(Duration.ofMillis(100))
    assertTimeoutPreemptively(Duration.ofSeconds(5)) { applicationContext.close() }
    assertEquals(ShutdownReason.DB_DRAIN_DEADLINE, lifecycle.forcedReason())
    assertNoSensitiveValueInShutdownReason()
}

@Test
fun `metric contract uses fixed names types units and bounded tags`() {
    exerciseCommandRedisWorkerBacklogSseAndLeaderPaths()
    assertMetricContract(
        "voucher.command.duration", "voucher.db.bulkhead.rejected", "voucher.redis.degraded",
        "voucher.review.open", "voucher.backlog.oldest.age", "voucher.worker.last.success",
        "voucher.worker.attempts", "voucher.sse.active", "voucher.sse.rejected",
        "hikaricp.connections.active", "hikaricp.connections.pending", "voucher.leader.state",
    )
    assertPrometheusScrapeContainsAllVoucherMetrics()
    assertNoMetricTag("tenant", "campaign", "user", "digest", "revision", "secret", "code")
}

@Test
fun `rotation retention purge and backup restore preserve replay contracts`() {
    assertOldKeyReplayAfterCurrentKeySwitchAndRollback()
    assertReferencedKeyRetirementRejectedAndMissingKeyFailsClosed()
    assertRetentionCutoffs(auditDays = 90, terminalExtraDays = 7, appliedDays = 30)
    assertBoundedPurgeIsIdempotentAndRecordsCountAndOldestAge()
    restorePostgresClone()
    assertAuditCursorInboxPublicationTerminalReplayAndGenerationKeyRestored()
}
```

- [ ] **Step 2: targeted tests를 RED로 확인한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test \
  --tests '*VoucherHealthIntegrationTest' \
  --tests '*VoucherLifecycleIntegrationTest' \
  --tests '*VoucherMetricsContractTest' \
  --tests '*VoucherKeyRotationIntegrationTest' \
  --tests '*VoucherRetentionIntegrationTest' \
  --tests '*VoucherBackupRestoreIntegrationTest' \
  --tests '*VoucherScenarioFixturesTest' --max-workers=1
```

Expected: FAIL because operational components and fixtures are absent.

- [ ] **Step 3: health/metrics/lifecycle를 구현한다**

process liveness와 PostgreSQL-authoritative readiness를 분리한다. Redis/leader는 DEGRADED
component로 표시한다. `VoucherMetrics`는 command duration(timer), DB rejection(counter), Redis
degradation(gauge/counter), open review(gauge), backlog oldest age(gauge seconds), worker last success
(gauge epoch seconds)/attempts(counter), SSE active(gauge)/rejected(counter), Hikari active/pending와
leader state를 등록한다. tag allowlist는 operation/outcome/bounded reason/backend/admission state만
허용하고 tenant/campaign/user/digest/revision/secret/code를 거부한다. contract test는 실제 path로
값을 변화시키고 Prometheus scrape name/type/unit/tag set을 고정한다. 실제 Spring `ApplicationContext.close()`가
readiness를 먼저 DOWN으로 내린 뒤 새 command/SSE를 503으로 거부하고 worker trigger를 멈춘다.
in-flight DB transaction을 bounded wait하고, blocked SSE write를 interrupt/cancel한 뒤 leader lease,
dedicated Bloom/leader/admission Redis connections와 shared Redis client, virtual-thread executor,
datasource 순으로 닫는다. leader lease는 leader connection보다 먼저 반환한다. test의 event sequence를 그대로
지키고 각 단계와 전체 grace deadline을 30초 안에서 제한하며 forced deadline reason은 bounded
enum만 log/metric에 남긴다. 정상 drain test는 context close를 별도 thread에서 실행해 drain window
중 503을 관찰한다. context가 종료할 application virtual executor가 아닌 독립 platform test
thread에서 close를 실행하고 executor termination을 명시적으로 검증한다. forced path는 injected
short deadline을 쓰는 별도 deterministic test로 둔다.

- [ ] **Step 4: key rotation과 retention을 구현한다**

generation/verification key version을 claim/idempotency terminal row와 함께 보존한다. active read
key set, current write key, terminal replay TTL, audit/inbox retention watermarks를 management
projection에 노출한다. audit 90일, terminal idempotency max voucher TTL + 7일, applied inbox와
publication 30일을 기본값으로 고정하고 참조 key/audit보다 먼저 삭제하지 않는다. purge는 bounded
batch count와 oldest age를 기록한다. replay row가 남은 key의 retire를 거부하고 missing key는 fail
closed한다. backup/restore smoke는 audit cursor, inbox/publication, terminal replay와 generation key
복구를 같은 PostgreSQLServer clone에서 검증한다.

- [ ] **Step 5: deterministic scenario cookbook fixture를 구현한다**

fixture route는 loopback + demo/test profile + operator guard에서만 bean으로 등록한다. fixed
Clock과 demo tenant로 happy path, same-key replay, capacity race, allocation/redemption review,
pause/revoke race, policy change, Redis outage, Bloom false positive, delayed/duplicate/out-of-order
event를 seed/input/expected state/audit/SSE result와 함께 제공한다. customer DTO는 risk override를
받지 않는다.

- [ ] **Step 6: operational tests를 GREEN으로 확인한다**

Run: Step 2와 동일.

Expected: PASS for readiness/liveness, degraded recovery thresholds, actual context-close ordering,
new-work rejection, in-flight DB/blocked-SSE bounded drain, 30-second forced-deadline redaction, key
rotation, retention guard and every deterministic fixture.

- [ ] **Step 7: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "feat: make voucher degradation and recovery operable" \
  -m $'Directive: Mark readiness down before draining work, and release the leader lease before closing Redis.\nConfidence: high\nScope-risk: moderate\nTested: Health, real context lifecycle, key, and fixture tests.'
```

### Task 11: Adversarial integration and performance evidence

**Files:**
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherEndToEndIntegrationTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/VoucherStressProfileTest.kt`
- Create: `commerce/promotion-voucher-campaign/src/test/kotlin/io/bluetape4k/workshop/commerce/voucher/performance/VoucherPerformanceProbe.kt`
- Create: `commerce/promotion-voucher-campaign/src/test/resources/junit-platform.properties`
- Create: `commerce/promotion-voucher-campaign/src/test/resources/logback-test.xml`

- [ ] **Step 1: end-to-end invariant matrix를 작성한다**

```kotlin
@ParameterizedTest
@MethodSource("failureScenarios")
fun `every failure scenario preserves PostgreSQL capacity and idempotency`(scenario: FailureScenario) {
    scenario.arrange(fixture)
    scenario.execute(client)
    assertCapacityInvariant()
    assertNoDuplicateEffect()
    assertNoSensitiveLogValue()
    assertDatabasePermits(foreground = 12, background = 4)
}
```

scenario는 Redis timeout/flapping, DB connection exhaustion, lock timeout, response-loss replay,
context restart, slow SSE consumer, worker poison/starvation, key rotation, cancellation과 shutdown을
포함한다.

- [ ] **Step 2: stress profile을 작성한다**

```kotlin
@ParameterizedTest
@MethodSource("stressProfiles")
@Tag("stress")
fun `capacity hotspot records bounded evidence without a wall clock CI gate`(profile: StressProfile) {
    val result = stress.run(
        capacity = 100,
        allocations = 500,
        redemptions = 500,
        concurrency = profile.concurrency,
        redisMode = profile.redisMode,
        hotspot = true,
    )
    assertTrue(result.hikariActiveMax <= 16)
    assertTrue(result.databasePermitMax <= 16)
    assertTrue(result.deterministicLockTimeoutContractPassed)
    assertEquals(0, result.resourceLeaks)
    assertCapacityInvariant()
    result.writeJsonAndJfr(reportDirectory)
}

companion object {
    @JvmStatic
    fun stressProfiles() = listOf(
        StressProfile(64, RedisMode.HEALTHY),
        StressProfile(64, RedisMode.TIMEOUT),
        StressProfile(128, RedisMode.HEALTHY),
        StressProfile(128, RedisMode.TIMEOUT),
    )
}
```

`@MethodSource`는 concurrency 64/128 × Redis healthy/timeout × single-campaign hotspot의 네
profile을 생성하며 run manifest는 expected/executed profile set이 정확히 네 개인지 검증한다.
`VoucherPerformanceProbe`는 `HikariPoolMXBean` active/idle/pending,
`DatabasePermitGate` lane별 in-use/wait, repository interceptor의 PostgreSQL query/transaction
round-trip, instrumented Lettuce command count/latency, `pg_stat_activity` wait state, JVM allocation/
GC pause를 고정 interval로 sampling한다. JDK `Recording`은 allocation, monitor/park, GC event를
활성화한다. JSON은 `schemaVersion`, git SHA, Java/OS/CPU, container image, seed, profile,
operation counts, status counts, p50/p95/p99, throughput, max gauges, round trips, bytes/op, GC와
artifact filenames를 필수 field로 갖고, 각 probe가 실제 source를 metadata에 기록한다.
`pg_stat_activity`는 10ms sampling timestamp와 consecutive wait-state 구간만 evidence로 기록하며
정확한 duration hard gate로 해석하지 않는다. lock wait <=5초 구조적 gate는 Task 5의 별도 두
connection deterministic `SET LOCAL lock_timeout` test elapsed time으로 판정한다.

- [ ] **Step 3: integration/stress를 실행한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:stressTest \
  -PvoucherStressRun=run-1 --rerun-tasks --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:stressTest \
  -PvoucherStressRun=run-2 --rerun-tasks --max-workers=1
```

Expected: all correctness gates PASS. `build/reports/voucher-stress/run-{1,2}/<profile>/`에 서로
덮어쓰지 않는 JSON/JFR을 남기고 JSON schema validation도 PASS한다. 각 run은 64/128 × Redis
healthy/timeout hotspot 전체 matrix를 수행하며 p95/p99, throughput, expected 409/429/503,
Hikari active/pending, lane별 permit wait, PostgreSQL/Redis round trips, lock wait, allocation
bytes/op, GC pause와 probe source를 기록한다. wall-clock 수치는 CI hard gate가 아니라 동일 환경
regression evidence다.

- [ ] **Step 4: dependency/runtime/logging scan을 실행한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:dependencies --configuration runtimeClasspath
./gradlew :commerce-promotion-voucher-campaign:detekt \
  :commerce-promotion-voucher-campaign:detektTest
rg -n 'MockMvc|virtualthread-jdk21|println\(|printStackTrace' commerce/promotion-voucher-campaign
```

Expected: detekt PASS; forbidden scan has no production/test usage; runtime includes JDK25 provider,
Exposed JDBC/Modulith, Bucket4j/Lettuce/leader/logging and no JDK21 provider.

- [ ] **Step 5: commit한다**

```bash
git add commerce/promotion-voucher-campaign
git commit -m "test: prove voucher safety under contention and failure" \
  -m $'Constraint: Performance numbers are comparable evidence, not portable CI latency gates.\nConfidence: high\nScope-risk: moderate\nTested: Full module suite and two complete stress-matrix runs with JSON/JFR schema validation.'
```

### Task 12: README diagrams, repository matrices, lessons, and final review

**Files:**
- Create: `commerce/promotion-voucher-campaign/README.md`
- Create: `commerce/promotion-voucher-campaign/README.ko.md`
- Create: `docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-architecture-01.svg`
- Create: `docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-architecture-01.png`
- Create: `docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-sequence-01.svg`
- Create: `docs/images/readme-diagrams/commerce-promotion-voucher-campaign-readme-sequence-01.png`
- Create: `docs/lessons/2026-07-19-issue-534-promotion-voucher-campaign.md`
- Create: `docs/review/2026-07-19-issue-534-promotion-voucher-campaign-review.md`
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `commerce/README.md`
- Modify: `commerce/README.ko.md`
- Modify: `docs/lessons/README.md`
- Modify: `docs/superpowers/specs/2026-05-22-issue-91-validation-matrix.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`
- Create: `scripts/validate-voucher-runbook.mjs`
- Modify: `AGENTS.md` only if the module table/rules require an explicit new entry

- [x] **Step 1: bilingual README와 scenario/runbook을 작성한다**

두 README는 language switch, heading, code fence, link와 image target을 parity로 유지한다.
scenario는 happy allocation/redemption, same-key replay, capacity race, allocation/redemption review,
Redis outage/Bloom false positive, delayed event, pause/revoke race를 curl/browser 절차와 authoritative
state/audit/SSE 결과로 설명한다. runbook은 PostgreSQL/Redis/leader/SSE/key/worker별 signal,
판단, 조치, 복구 확인을 포함한다. Redis degraded 5분, backlog oldest age 10분, Hikari pending
지속, worker last success 2주기 초과, SSE rejection/cleanup leak를 workshop warning threshold로
고정한다. backup/restore와 key-retention 복구 절차도 포함한다. loopback workshop이며 production
IAM/CSRF/OAuth가 없음을 명확히 경고한다.
README section contract는 prerequisites, startup/configuration table, seed/reset, customer/operator
curl, browser walkthrough, lost-response idempotent retry, allocation/redemption review와 code
acknowledgement, reconciliation, Redis/PostgreSQL outage, SSE reconnect/polling fallback, complete
stable error/retry catalog, unsupported reversal/#537/#538, troubleshooting, scenario cookbook을
모두 포함한다.

- [x] **Step 2: Architecture와 Sequence Diagram을 생성·검증한다**

Architecture는 Browser/live WebTestClient -> Tomcat MVC -> idempotency/application service ->
Exposed repository -> PostgreSQL authority를 중심에 두고 Redis admission/Bloom, leader worker,
Spring Modulith publication을 advisory/durable side path로 구분한다. Sequence는 allocation retry,
review acknowledgement, redemption, Redis degraded, response loss replay와 reconciliation alt 흐름을
표현한다. SVG를 source로 두고 matching PNG를 생성한다.

- [x] **Step 3: repository registration surfaces를 갱신한다**

`settings.gradle.kts`는 `includeModules("commerce", false, true)`가 module을 자동 등록하므로
수정하지 않는다. `scripts/smoke-validate.sh commerce`와 Examples container lane에
`:commerce-promotion-voucher-campaign:test`를 추가하고 `--max-workers=1`을 유지한다. Examples
artifact upload에 module test XML/report 경로를 추가한다. `all-smoke`와 nightly smoke에는
Testcontainers module을 넣지 않는다. validation matrix의 stale hard-coded count를 증가시키지
말고 현재 103 -> 104 project graph와 T3 Full/Commerce 명령을 정확히 기록한다.

- [x] **Step 4: lesson과 review evidence를 작성한다**

lesson은 Context/Decision/Outcome/Verification/Future Guidance 순서로 PostgreSQL authority,
Hikari 16/permit, safe GET acknowledgement 분리, Redis advisory, live WebTestClient와 logging
redaction을 기록한다. review는 resolved dependency versions, exact commands, test counts,
stress artifacts, six-lens P0/P1, known non-blocking limits를 기록한다.

- [x] **Step 5: documentation/workflow validators를 실행한다**

Run:

```bash
EXPECTED_GRADLE_PROJECTS=104 ./scripts/smoke-validate.sh stale-check
bash -n scripts/smoke-validate.sh
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
node scripts/validate-readme-architecture-diagrams.mjs
node scripts/validate-sequence-diagrams.mjs
node scripts/validate-voucher-runbook.mjs
./scripts/smoke-validate.sh diagram-qa
```

Expected: all validators PASS; global pre-existing failure가 있으면 exact path와 scoped parity result를
review에 분리해 기록하고 변경 범위 failure는 모두 수정한다. runbook validator는 PostgreSQL,
Redis, leader, SSE, key, worker 각 subsystem에 signal/query-or-command/threshold/decision/action/
recovery-check가 있고 5분/10분/2-cycle/Hikari-pending warning window가 정확한지 검증한다. 같은
validator는 English/Korean README의 prerequisites, startup/config, seed/reset, curl, browser,
idempotency/review/reconciliation, outage, SSE fallback, full stable-error catalog, unsupported,
troubleshooting, cookbook section parity와 approved stable code entry 전부를 검사한다.

- [x] **Step 6: final build matrix를 순차 실행한다**

Run:

```bash
./gradlew :commerce-promotion-voucher-campaign:test --rerun-tasks --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:migrationCompatibilityTest \
  --rerun-tasks --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:stressTest \
  -PvoucherStressRun=final-1 --rerun-tasks --max-workers=1
./gradlew :commerce-promotion-voucher-campaign:stressTest \
  -PvoucherStressRun=final-2 --rerun-tasks --max-workers=1
./scripts/smoke-validate.sh commerce
if rg -n 'MockMvc|virtualthread-jdk21|println\(|printStackTrace\(' \
  commerce/promotion-voucher-campaign/src/main \
  commerce/promotion-voucher-campaign/src/test; then exit 1; fi
./gradlew build -x test --parallel --continue
git diff --check
```

Expected: module tests; packaged bootJar clean/warm/previous-schema/checksum/partial-DDL/previous-binary
compatibility process; 두 complete stress matrix; sequential Commerce full lane; detekt; repository
compile/build; diff check가 PASS한다. Testcontainers tests는 다른 worktree/review lane과 병렬 실행하지
않는다.

- [x] **Step 7: independent implementation review를 수행한다**

Performance, stability, security, operator/operations, developer/API, user/caller 여섯 관점이 같은
branch head를 독립 검토한다. P0/P1은 모두 수정 후 affected-lens rerun으로 0을 만든다. P2는
수정하거나 현재 issue의 evidence/명시적 follow-up으로 disposition을 기록한다.

- [x] **Step 8: documentation/integration commit을 만든다**

```bash
git add README.md README.ko.md AGENTS.md commerce docs .github/workflows/Examples.yml scripts/smoke-validate.sh
git commit -m "docs: make voucher campaign failures reproducible" \
  -m $'Constraint: The module belongs only to sequential container-backed validation.\nConfidence: high\nScope-risk: broad\nTested: README, diagram, workflow, migration compatibility, stress, stale-check, module, and Commerce validators.'
```

## Six-lens 계획 리뷰 기록

동일한 approved spec과 현재 plan artifact를 기준으로 독립 검토하고, P0/P1뿐 아니라 발견된
P2도 현재 issue의 test/evidence로 흡수한 뒤 affected lens를 재검토했다.

| Lens | Initial counts | Final counts | Integrated resolution |
|---|---|---|---|
| Performance | P0=0, P1=5, P2=4, P3=0 | P0=0, P1=0, P2=0, P3=0 | permit-before-connection, 12/1/3 lane, lock timeout, common Test runtime, exact stress matrix/probes/SSE bounds |
| Stability | P0=0, P1=3, P2=5, P3=0 | P0=0, P1=0, P2=0, P3=0 | atomic finalize, isolated previous binary, rollback/restart, poison schedule, overflow cleanup, real drain shutdown |
| Security | P0=0, P1=4, P2=2, P3=0 | P0=0, P1=0, P2=0, P3=0 | scope matrix, operator/fixture guards, exact input bounds, key domains/storage, CSP/secret lifecycle, failure redaction |
| Operator/Ops | P0=0, P1=4, P2=3, P3=0 | P0=0, P1=0, P2=0, P3=0 | startup fail-closed codes, metrics, management isolation, retention/purge/restore, health/reconciliation/runbook contracts |
| Developer/API | P0=0, P1=8, P2=6, P3=0 | P0=0, P1=0, P2=0, P3=0 | compile-ready Bluetape APIs, tenant repository scope, permit transaction boundary, closed replay descriptor, RED/GREEN order |
| User/caller | P0=0, P1=5, P2=2, P3=0 | P0=0, P1=0, P2=0, P3=0 | acknowledgement/replay/error wire contract, SSE Link fallback, real accessible browser/cookbook smoke, bilingual validator |

최종 open finding과 deferred P2는 없다. #537 voucher pool, #538 event sourcing만 approved non-scope로
유지하며 현재 #534 module에는 미완료 표식이나 speculative abstraction을 남기지 않는다.

## Spec coverage matrix

| Spec contract | Implementing tasks | Required proof |
|---|---|---|
| Java 25, VT, Tomcat 8000, Hikari 16, 60s | 1, 10, 11 | RuntimeContractTest, health/lifecycle, dependency scan |
| campaign/claim/review state and capacity invariant | 2, 3, 5 | domain, repository, PostgreSQL race tests |
| opaque code, verifier/key rotation | 2, 4, 10 | code contract, replay, rotation/retention tests |
| tenant scope and lock order | 3, 5, 8 | cross-tenant 404, CAS/deadlock tests, live HTTP |
| application-owned idempotency | 4, 5, 8 | replay/conflict/takeover/stale-finalize/response-loss |
| Redis advisory admission/Bloom | 6 | RedisServer outage/flush/false-positive/hysteresis |
| durable publication/inbox/reconciliation | 7 | SKIP LOCKED, poison/backoff, leader/restart tests |
| migration/deployment compatibility | 1, 3, 12 | clean/warm bootJar, previous-schema upgrade, checksum/partial-DDL rollback, previous-binary process |
| customer/operator API and safe GET | 8 | live WebTestClient and route-precondition matrix |
| SSE/browser/accessibility | 9 | cursor/resource lifecycle and static contract tests |
| health/logging/metrics/shutdown/fixtures | 8, 10 | redaction, low-cardinality, lifecycle and cookbook tests |
| performance and failure evidence | 11 | two stress runs, JSON/JFR, invariant gates |
| bilingual docs/diagrams/repo guards | 12 | parity/diagram/actionlint/stale/Commerce validations |

## Self-review result

- Spec coverage: 각 설계 section이 위 matrix의 task와 proof에 연결됐다. 누락된 public route,
  persistence table, runtime value, failure mode는 없다.
- Placeholder scan: 미정 값, 구현 연기 문구, 내용 없는 error/test 지시를 사용하지 않았다.
  #537/#538은 명시적 non-scope이며 현재 module에 미완료 표식을 남기지 않는다.
- Type consistency: `CampaignState`, `ClaimState`, `ReviewKind`, `ClaimSnapshot`,
  `TransitionOutcome`, `IdempotencyScope`, `IdempotencyAcquireResult`, `AdmissionDecision`,
  `ReconciliationResult` 이름을 이후 task에서도 동일하게 사용한다.
- File ownership: production code는 module 안에 한정하고 root-level 변경은 registration,
  validation, bilingual index, lessons/review로 제한한다.
- Documentation quality: public API와 configuration property에는 English KDoc을 작성하고,
  campaign -> claim -> review lock order, consumed-token non-return, replay key retention처럼
  코드만으로 이유가 드러나지 않는 invariant에는 짧은 rationale comment를 둔다. 동작을 그대로
  번역하는 주석은 추가하지 않는다.

## Stop condition

구현 완료는 targeted RED/GREEN, PostgreSQL/Redis integration, restart/leader/SSE/live HTTP,
module full test, packaged bootJar clean/warm/upgrade/checksum/partial-DDL/previous-binary compatibility,
두 complete stress matrix evidence run, detekt, Commerce sequential lane, repository compile,
actionlint, stale-check, README/diagram validators, `git diff --check`, independent six-lens review에서
P0/P1=0을 모두 만족할 때다. 그 뒤 exact branch head로 PR을 생성할 수 있지만 merge는 별도 사용자
승인 전에는 수행하지 않는다.
