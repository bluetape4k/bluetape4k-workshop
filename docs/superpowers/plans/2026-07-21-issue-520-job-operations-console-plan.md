# Issue #520 Job Operations Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java 25로 한정된 core, Spring MVC, Ktor 모듈에서 동일한 Job Operations Console 계약을 제공하고, PostgreSQL-authoritative tenant FIFO·lease·checkpoint·idempotency·history와 advisory Redis cancellation, REST snapshot/SSE notification, 결정론적 장애 fixture를 구현한다.

**Architecture:** `operations-job-console-core`가 framework-neutral domain, Exposed JDBC persistence, worker engine, Redis signal port, projection, 공통 UI와 black-box fixture를 소유한다. `operations-job-console-spring`과 `operations-job-console-ktor`는 동일한 port와 wire DTO를 각각 `SseEmitter`/virtual thread와 Ktor SSE/coroutine lifecycle에 연결한다. PostgreSQL만 queue와 terminal state의 권위이며 Redis와 SSE는 유실 가능한 알림 채널이다.

**Tech Stack:** Kotlin 2.4.0 compiler with repository language/API convention, Java 25 module toolchains, Spring Boot 4.1.0 MVC, Ktor 3.5.0, JetBrains Exposed JDBC, PostgreSQL, Lettuce/Redis, Jackson 3, Micrometer, JUnit 5, Testcontainers, `bluetape4k-dependencies` BOM, Bluetape logging/assertions/JUnit5/Exposed/Lettuce/virtual-thread/testcontainers modules.

---

## 1. 실행 계약

- 설계 권위는 `docs/superpowers/specs/2026-07-21-issue-520-job-operations-console-design.md`다.
- 작업 이슈는 [#520](https://github.com/bluetape4k/bluetape4k-workshop/issues/520), branch는 `feature/issue-520-job-operations-console`, PR base는 `develop`이다.
- root Java 21과 기존 모듈 toolchain은 변경하지 않는다. 새 세 모듈만 `JavaLanguageVersion.of(25)`, `jvmToolchain(25)`, `JvmTarget.JVM_25`를 선언한다.
- version authority는 root의 `platform(libs.bluetape4k.dependencies)` 하나다. 개별 Bluetape BOM, 명시 Bluetape version, 신규 migration framework를 추가하지 않는다.
- PostgreSQL 권위 검증은 `PostgreSQLServer.Launcher.postgres`, Redis fixture는 `RedisServer.Launcher.redis`를 사용한다. H2는 권위 검증에 사용하지 않는다.
- container test와 live Spring/Ktor parity test는 `--max-workers=1`로 순차 실행한다. core의 순수 domain/projection test만 smoke lane 후보로 둔다.
- wall-clock sleep, 실제 외부 provider, raw tenant/submitter/idempotency key log를 사용하지 않는다. concurrency는 barrier/latch와 repository의 `MultithreadingTester`를 사용한다.
- public wire DTO와 problem DTO는 두 adapter가 core의 동일 type을 사용한다. adapter별 duplicate DTO를 만들지 않는다.
- Exposed DSL에서는 명시적 table qualifier와 import를 사용해 receiver shadowing을 피한다. `CancellationException`은 broad catch보다 먼저 재전파한다.
- 구현 중 범위가 generic queue framework, 공용 adapter SPI, Redis Streams, global FIFO, priority scheduling, production authentication, load target으로 확장되면 중단하고 #522 또는 별도 이슈로 분리한다.

### 고정 HTTP/SSE surface

| Method/path | Contract owner | Required proof |
|---|---|---|
| `POST /v1/jobs` | core command + adapter route | idempotent submit/replay/conflict |
| `GET /v1/jobs/{jobId}` | core snapshot + adapter route | scope, queue, ETA, terminal snapshot |
| `POST /v1/jobs/{jobId}/cancel` | DB-first cancel + adapter route | queued/running/race/Redis loss |
| `GET /v1/queues/me` | submitter queue projection | tenant isolation and cursor |
| `GET /v1/tenants/{tenantId}/queue` | operator projection | demo operator fail-closed and redaction |
| `GET /v1/events/jobs/{jobId}` | adapter SSE | notification-only, heartbeat, slow-client removal |
| `GET /healthz` | adapter lifecycle | process liveness only |
| `GET /readyz` | core dependencies + adapter health | PostgreSQL required, Redis degraded allowed |

## 2. 파일 구조와 책임

### Core production

- `operations/job-console-core/build.gradle.kts`: Java 25, `java-test-fixtures`, versionless dependencies, test task 분리
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/domain/JobModels.kt`: state, command, outcome, checkpoint, lease value
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/domain/JobTransitions.kt`: 허용 전이와 terminal invariants
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/api/JobApiModels.kt`: 공통 request/response/problem/SSE DTO
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobTables.kt`: request/job/checkpoint/attempt/outbox/history/duration table
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobMigrationRunner.kt`: ordered checksum migration과 startup gate
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobRepository.kt`: submit/replay/query/cancel/claim/lease/checkpoint/terminal transaction
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/persistence/JobOutboxRepository.kt`: committed notification claim/finalize
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/queue/QueueProjectionService.kt`: position, jobsAhead, queueVersion, cursor page
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/queue/EtaEstimator.kt`: bounded p50/p90 range와 confidence
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/worker/JobWorkerEngine.kt`: claim/chunk/checkpoint/retry/terminal loop
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/worker/DeterministicJobWorkload.kt`: credential-free workload와 failure mode
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/signal/CancelSignal.kt`: Redis-independent port와 no-op/degraded result
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/signal/LettuceCancelSignal.kt`: advisory publish/subscribe
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/application/JobConsoleService.kt`: API use-case facade
- `operations/job-console-core/src/main/kotlin/io/bluetape4k/workshop/operations/jobconsole/observability/JobConsoleObservability.kt`: low-cardinality metrics와 redacted log values
- `operations/job-console-core/src/main/resources/job-console-ui/{index.html,app.js,styles.css}`: adapter 공통 browser bytes

### Adapter production

- `operations/job-console-spring/build.gradle.kts`와 `.../JobConsoleSpringApplication.kt`: Java 25 Spring Boot application
- `operations/job-console-spring/src/main/kotlin/.../config/SpringJobConsoleConfiguration.kt`: datasource/core wiring, virtual-thread executor, startup/shutdown
- `operations/job-console-spring/src/main/kotlin/.../web/SpringJobRoutes.kt`: REST routes와 demo scope resolver
- `operations/job-console-spring/src/main/kotlin/.../web/SpringJobEventStream.kt`: bounded `SseEmitter` fan-out
- `operations/job-console-spring/src/main/kotlin/.../web/SpringProblemHandler.kt`: stable problem mapping
- `operations/job-console-spring/src/main/kotlin/.../web/SpringStaticUiController.kt`: core UI resource serving
- `operations/job-console-spring/src/main/kotlin/.../health/SpringJobHealth.kt`: liveness/readiness/degraded Redis
- `operations/job-console-spring/src/main/resources/application.yml`: JDBC, worker, SSE, demo profile bounds
- `operations/job-console-ktor/build.gradle.kts`와 `.../JobConsoleKtorApplication.kt`: Java 25 Ktor Netty application
- `operations/job-console-ktor/src/main/kotlin/.../KtorJobConsoleModule.kt`: plugin, datasource/core wiring
- `operations/job-console-ktor/src/main/kotlin/.../KtorJobRoutes.kt`: REST/SSE/static routes와 demo scope resolver
- `operations/job-console-ktor/src/main/kotlin/.../KtorJackson3Support.kt`: core DTO의 Jackson 3 request/response/SSE codec
- `operations/job-console-ktor/src/main/kotlin/.../KtorProblemPages.kt`: stable problem mapping
- `operations/job-console-ktor/src/main/kotlin/.../KtorWorkerLifecycle.kt`: owned coroutine scope, `Dispatchers.IO`, bounded shutdown
- `operations/job-console-ktor/src/main/resources/application.conf`: JDBC, worker, SSE, demo profile bounds

### Test fixtures, docs, repository integration

- core `src/testFixtures/kotlin/.../JobConsoleContract.kt`, `JobConsoleScenario.kt`, `JobConsoleFixtureClock.kt`, `JobConsoleBarrier.kt`, `JobConsoleContainerFixture.kt`
- core domain, migration, repository, queue, ETA, worker, Redis, observability tests
- Spring/Ktor live HTTP, SSE, trusted-scope, lifecycle, static UI, shared parity tests
- `operations/{README.md,README.ko.md}`와 세 module의 `README.md`, `README.ko.md`
- root `README.md`, `README.ko.md`, `AGENTS.md`, `settings.gradle.kts`
- `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`, `scripts/smoke-validate.sh`
- `docs/images/readme-diagrams/operations-job-console-readme-{architecture,sequence,state}-01.{svg,png}`
- `docs/lessons/2026-07-21-issue-520-job-operations-console.md`
- `docs/review/2026-07-21-issue-520-job-operations-console-review.md`

## 3. 단계별 구현

### Task 1: 모듈 등록과 Java 25 dependency 계약

**Files:**
- Modify: `settings.gradle.kts`
- Create: `operations/job-console-core/build.gradle.kts`
- Create: `operations/job-console-spring/build.gradle.kts`
- Create: `operations/job-console-ktor/build.gradle.kts`
- Create: 각 module의 `src/test/resources/junit-platform.properties`

**Write scope:** Gradle 설정과 test resource만. Domain production code는 작성하지 않는다.

- [ ] **Step 1: 등록 전 RED를 기록한다**

```bash
./gradlew :operations-job-console-core:tasks
```

Expected: project가 아직 등록되지 않아 FAIL.

- [ ] **Step 2: `operations` group과 세 Java 25 module을 등록한다**

`settings.gradle.kts`에 `includeModules("operations", false, true)`를 추가한다. core에는 `java-test-fixtures`와 Kotlin/JVM, Spring에는 Kotlin Spring/Spring Boot, Ktor에는 Kotlin serialization/application plugin을 적용한다. 세 build 모두 다음 경계를 포함한다.

```kotlin
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}
configurations.configureEach {
    exclude(group = "io.github.bluetape4k", module = "bluetape4k-virtualthread-jdk21")
}
```

core는 `exposed.jdbc`, `hikaricp`, PostgreSQL driver, Lettuce, Jackson 3, Micrometer를 versionless alias로 선언하고 Spring/Ktor는 `implementation(project(":operations-job-console-core"))`, `testImplementation(testFixtures(project(...)))`만으로 공통 계약을 가져온다.

core의 test fixture dependency는 consumer에 전파될 항목을 구분한다.

```kotlin
testFixturesImplementation(libs.bluetape4k.assertions)
testFixturesImplementation(libs.bluetape4k.testcontainers)
testFixturesImplementation(libs.testcontainers.postgresql)
```

container launcher는 `lazy`로 초기화해 순수 unit test classpath load가 Docker를 시작하지 않게 한다.

세 모듈은 일반 `test`에서 `integration` tag를 제외하고, 같은 compiled test source를 사용하는 `integrationTest` task에서 해당 tag만 실행한다. 따라서 core의 domain/projection test만 smoke에 들어가고 PostgreSQL, Redis, live server test는 full lane에만 들어간다.

```kotlin
tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs container-backed and live-server integration tests."
    group = "verification"
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    useJUnitPlatform { includeTags("integration") }
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
    shouldRunAfter(tasks.test)
}
```

`integrationTest`에는 기존 `test`와 같은 Java 25 JVM args, locale, Datadog dummy environment, test logging을 module-local `useWorkshopTestRuntime()` helper로 적용한다. 새 task를 단순 등록해 test mutex나 runtime 설정을 잃지 않는다.

- [ ] **Step 3: test runtime 직렬화와 Java 25를 검증한다**

```bash
./gradlew projects
./gradlew :operations-job-console-core:compileKotlin :operations-job-console-spring:compileKotlin :operations-job-console-ktor:compileKotlin
./gradlew :operations-job-console-core:dependencyInsight --dependency bluetape4k-virtualthread --configuration runtimeClasspath
```

Expected: 세 project가 보이고 target 25로 compile되며 JDK21 provider가 resolution에서 제외된다. root `JAVA_VERSION: '21'` workflow 값은 변하지 않는다.

- [ ] **Step 4: commit**

```bash
git add settings.gradle.kts operations
git commit -m "Establish an isolated Java 25 boundary for the job console example" \
  -m "Constraint: Preserve the repository Java 21 default and the central Bluetape dependency BOM." \
  -m "Rejected: Raising the root toolchain | Existing workshop modules must remain on their current runtime." \
  -m "Confidence: high" -m "Scope-risk: narrow" \
  -m "Tested: Gradle projects, compileKotlin, and runtime dependency insight." \
  -m "Not-tested: Domain behavior is introduced in later tasks."
```

**Rollback:** 세 directory와 `includeModules` 한 줄을 함께 되돌리면 기존 graph가 복원된다.

### Task 2: 상태 머신과 공통 wire contract

**Files:**
- Create: core `domain/JobModels.kt`, `domain/JobTransitions.kt`, `api/JobApiModels.kt`
- Create: core tests `domain/JobTransitionsTest.kt`, `api/JobApiModelsTest.kt`

**Depends on:** Task 1. **Complexity:** medium.

- [ ] **Step 1: 상태 전이 RED test를 작성한다**

```kotlin
@Test
fun `retry preserves enqueue identity and terminal states cannot move`() {
    JobTransitions.next(JobState.RUNNING, JobSignal.RETRYABLE_FAILURE) shouldBe JobState.QUEUED
    assertFailsWith<InvalidJobTransition> {
        JobTransitions.next(JobState.SUCCEEDED, JobSignal.CANCEL)
    }
}
```

queued cancel, running cancel request, checkpoint cancel, success, non-retryable failure, retry exhaustion, stale version을 parameterized test로 고정한다.

- [ ] **Step 2: RED를 실행한다**

```bash
./gradlew :operations-job-console-core:test --tests '*JobTransitionsTest' --tests '*JobApiModelsTest'
```

Expected: missing production symbols로 FAIL.

- [ ] **Step 3: 최소 domain과 DTO를 구현한다**

```kotlin
enum class JobState { QUEUED, RUNNING, CANCEL_REQUESTED, SUCCEEDED, FAILED, DEAD_LETTERED, CANCELLED }

data class JobSnapshot(
    val jobId: UUID,
    val state: JobState,
    val progress: Int,
    val checkpoint: Long?,
    val queue: QueueProjection?,
    val version: Long,
    val updatedAt: Instant,
)
```

public DTO에는 stable JSON field와 English KDoc을 두고 raw trusted header/idempotency key를 response에 포함하지 않는다.

- [ ] **Step 4: GREEN과 serialization shape를 검증한다**

```bash
./gradlew :operations-job-console-core:test --tests '*JobTransitionsTest' --tests '*JobApiModelsTest'
```

- [ ] **Step 5: Lore commit**

Intent: framework 비교 전에 상태와 wire 의미를 하나의 권위로 고정한다. Tested trailer에는 두 targeted test를 기록한다.

### Task 3: PostgreSQL schema, migration, submit idempotency

**Files:**
- Create: core `persistence/JobTables.kt`, `JobMigrationRunner.kt`, `JobRepository.kt`
- Create: core `src/main/resources/db/job-console/V001__job_console.sql`
- Create: core tests `persistence/JobMigrationRunnerTest.kt`, `JobSubmissionRepositoryTest.kt`, `JobIdempotencyConcurrencyTest.kt`

**Depends on:** Task 2. **Complexity:** high. **Hazards:** Exposed receiver shadow, transaction nesting, raw key leakage.

- [ ] **Step 1: PostgreSQL RED tests를 작성한다**

검증 행렬:

| Case | Expected |
|---|---|
| 첫 submit | job/request/outbox/history 각각 1개 |
| same key + same fingerprint | 동일 job ID와 snapshot replay, row 증가 없음 |
| same key + different fingerprint | `IDEMPOTENCY_KEY_REUSED` |
| concurrent same submit | owner 1개, job 1개 |
| checksum mismatch | migration 실패, readiness 미개방 |
| migration 재실행 | no-op |

Idempotency key는 scope와 함께 SHA-256 hash로만 저장하고 canonical closed DTO fingerprint를 사용한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-core:integrationTest \
  --tests '*JobMigrationRunnerTest' \
  --tests '*JobSubmissionRepositoryTest' \
  --tests '*JobIdempotencyConcurrencyTest' \
  --max-workers=1
```

- [ ] **Step 3: additive schema와 transaction을 구현한다**

핵심 constraint/index:

```sql
CREATE UNIQUE INDEX uq_job_active_per_tenant
ON jobs (tenant_id)
WHERE state IN ('RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX ix_job_tenant_queue
ON jobs (tenant_id, state, enqueue_sequence)
INCLUDE (job_id, progress, queue_version, updated_at);
```

enqueue sequence allocation, request ownership, job/outbox/history 생성은 한 transaction에서 처리한다. migration pattern은 `promotion-voucher-campaign`의 ordered checksum/startup lock을 예제 전용으로 축소해 재사용한다.

- [ ] **Step 4: GREEN과 log redaction을 확인한다**

```bash
./gradlew :operations-job-console-core:integrationTest --tests '*JobMigrationRunnerTest' --tests '*JobSubmissionRepositoryTest' --tests '*JobIdempotencyConcurrencyTest' --max-workers=1
rg -n 'idempotencyKey|tenantId|submitterId' operations/job-console-core/src/main/kotlin
```

Expected: tests PASS. 검색된 identifier는 DTO/column 명칭뿐이며 raw value interpolation log가 없다.

- [ ] **Step 5: Lore commit**

Directive trailer에 schema는 additive하게 진화시키고 destructive recreate를 정상 경로로 만들지 말라고 기록한다.

### Task 4: tenant FIFO claim, lease fencing, checkpoint와 terminal history

**Files:**
- Modify: core `persistence/JobRepository.kt`, `JobTables.kt`
- Create: core `worker/JobWorkerEngine.kt`, `worker/DeterministicJobWorkload.kt`
- Create: tests `persistence/JobClaimConcurrencyTest.kt`, `worker/JobWorkerEngineTest.kt`, `worker/JobLeaseRecoveryTest.kt`

**Depends on:** Task 3. **Complexity:** high. **Stop condition:** stale worker write가 한 건이라도 성공하면 다음 task로 진행하지 않는다.

- [ ] **Step 1: concurrency RED tests를 작성한다**

동일 tenant의 동시 claim은 enqueue sequence 순서와 active job 최대 1을, 다른 tenant는 독립 진행을 검증한다. lease expiry는 sleep 대신 fixture transaction으로 DB expiry를 과거로 이동한다. stale lease token의 checkpoint/terminal update는 update count 0이어야 한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-core:integrationTest \
  --tests '*JobClaimConcurrencyTest' \
  --tests '*JobWorkerEngineTest' \
  --tests '*JobLeaseRecoveryTest' \
  --max-workers=1
```

- [ ] **Step 3: PostgreSQL server-time claim과 fenced write를 구현한다**

```kotlin
data class JobLease(val jobId: UUID, val token: UUID, val attempt: Int, val expiresAt: Instant)

fun checkpoint(lease: JobLease, chunk: Long, progress: Int): Boolean = transaction {
    // current token + version 조건으로 checkpoint, job progress, outbox를 함께 갱신
}
```

claim은 tenant별 oldest eligible queued job만 `FOR UPDATE SKIP LOCKED`로 선택하고 lease 만료 판단은 PostgreSQL `CURRENT_TIMESTAMP`를 사용한다. retry는 기존 enqueue sequence를 보존한다.

- [ ] **Step 4: GREEN과 query plan을 검증한다**

```bash
./gradlew :operations-job-console-core:integrationTest --tests '*JobClaimConcurrencyTest' --tests '*JobWorkerEngineTest' --tests '*JobLeaseRecoveryTest' --max-workers=1
```

fixture의 `EXPLAIN` assertion은 tenant-local queue index가 후보이며 repository-wide sequential scan이 아님을 확인한다.

- [ ] **Step 5: Lore commit**

Constraint trailer에 DB server time과 lease token fencing을 기록한다.

### Task 5: durable cancellation과 advisory Redis signal

**Files:**
- Create: core `signal/CancelSignal.kt`, `signal/LettuceCancelSignal.kt`
- Modify: core `application/JobConsoleService.kt`, `worker/JobWorkerEngine.kt`, `persistence/JobRepository.kt`
- Create: tests `worker/JobCancellationRaceTest.kt`, `signal/LettuceCancelSignalIntegrationTest.kt`, `worker/RedisLossCancellationTest.kt`

**Depends on:** Task 4. **Complexity:** high.

- [ ] **Step 1: cancellation RED matrix를 작성한다**

queued cancel/claim race는 하나의 권위 결과만, running cancel은 `cancel_requested`를 durable commit한 뒤 다음 checkpoint에서 `cancelled`, Redis unavailable 또는 signal drop도 동일 terminal 결과를 보장한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-core:integrationTest \
  --tests '*JobCancellationRaceTest' \
  --tests '*LettuceCancelSignalIntegrationTest' \
  --tests '*RedisLossCancellationTest' \
  --max-workers=1
```

- [ ] **Step 3: DB-first cancellation을 구현한다**

```kotlin
fun cancel(scope: CallerScope, jobId: UUID): CancelOutcome {
    val outcome = repository.recordCancellation(scope, jobId)
    if (outcome is CancelOutcome.Requested) cancelSignal.publish(jobId).onFailure(observability::redisDegraded)
    return outcome
}
```

Redis publish 실패는 response를 되돌리지 않는다. subscriber는 worker wake-up만 수행하며 job state를 직접 변경하지 않는다.

- [ ] **Step 4: GREEN과 degraded readiness contract를 검증한다**

동일 targeted command를 재실행하고 Redis 중지 fixture에서 PostgreSQL submit/query/cancel이 계속 성공하는지 확인한다.

- [ ] **Step 5: Lore commit**

Rejected trailer에 Redis queue/authority 사용을 명시적으로 거절한다.

### Task 6: queue projection, ETA, cursor와 bounded query

**Files:**
- Create: core `queue/QueueProjectionService.kt`, `queue/EtaEstimator.kt`
- Modify: core `persistence/JobRepository.kt`
- Create: tests `queue/QueueProjectionServiceTest.kt`, `queue/EtaEstimatorTest.kt`, `queue/QueueQueryPlanTest.kt`

**Depends on:** Task 4. **Complexity:** medium.

- [ ] **Step 1: projection RED tests를 작성한다**

position/jobsAhead, cross-tenant identity 비노출, cursor next page, max page size, queueVersion, p50/p90 range, insufficient sample, bounded retention을 검증한다.

```kotlin
@Test
fun `insufficient samples never fabricate a precise ETA`() {
    estimator.estimate(emptyList(), jobsAhead = 3).confidence shouldBe EtaConfidence.INSUFFICIENT_DATA
}
```

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-core:test --tests '*QueueProjectionServiceTest' --tests '*EtaEstimatorTest'
./gradlew :operations-job-console-core:integrationTest --tests '*QueueQueryPlanTest' --max-workers=1
```

- [ ] **Step 3: bounded projection을 구현한다**

duration sample은 job type별 최대 수와 retention window로 자른다. operator queue는 opaque cursor와 default/max page size를 강제한다. exact `jobsAhead`의 backlog 비례 비용은 README에 #522 경계로 명시한다.

- [ ] **Step 4: GREEN 후 commit**

Targeted tests를 재실행하고 Lore commit의 Not-tested trailer에 처리량 수치가 #522 범위임을 기록한다.

### Task 7: outbox polling, bounded fan-out와 공통 black-box fixture

**Files:**
- Create: core `persistence/JobOutboxRepository.kt`, `application/JobConsoleService.kt`
- Create: core `src/testFixtures/kotlin/.../JobConsoleContract.kt`, `JobConsoleScenario.kt`, `JobConsoleFixtureClock.kt`, `JobConsoleBarrier.kt`, `JobConsoleContainerFixture.kt`
- Create: tests `persistence/JobOutboxRepositoryTest.kt`, `application/JobConsoleServiceTest.kt`

**Depends on:** Tasks 3-6. **Complexity:** high.

- [ ] **Step 1: outbox RED tests를 작성한다**

stable event ID, duplicate publication finalization, failed publish retry, bounded claim batch, oldest unpublished age, slow consumer 격리를 검증한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-core:test --tests '*JobConsoleServiceTest'
./gradlew :operations-job-console-core:integrationTest --tests '*JobOutboxRepositoryTest' --max-workers=1
```

- [ ] **Step 3: port와 shared contract를 구현한다**

```kotlin
interface JobConsoleHttpDriver {
    suspend fun submit(request: SubmitJobRequest, key: String, scope: DemoScope): JobSnapshot
    suspend fun snapshot(jobId: UUID, scope: DemoScope): JobSnapshot
    suspend fun cancel(jobId: UUID, scope: DemoScope): JobSnapshot
    suspend fun openEvents(jobId: UUID, scope: DemoScope): JobEventProbe
}
```

fixture는 server lifecycle을 소유하지 않고 전달받은 base URL/driver에 동일 scenario를 실행한다. `JobConsoleContainerFixture`는 singleton PostgreSQL/Redis 주소와 schema namespace만 제공한다.

- [ ] **Step 4: GREEN과 test-fixture publication을 검증한다**

```bash
./gradlew :operations-job-console-core:test :operations-job-console-core:testFixturesJar --max-workers=1
```

- [ ] **Step 5: Lore commit**

Directive trailer에 adapter-specific lifecycle을 core fixture로 끌어올리지 말라고 기록한다.

### Task 8: Spring MVC adapter, live REST/SSE와 virtual-thread lifecycle

**Files:**
- Create: Spring production files listed in section 2
- Create: Spring tests `SpringJobConsoleHttpTest.kt`, `SpringJobConsoleSseTest.kt`, `SpringDemoScopeTest.kt`, `SpringWorkerLifecycleTest.kt`, `SpringStaticUiTest.kt`, `SpringContractParityTest.kt`

**Depends on:** Task 7. **Complexity:** high.

- [ ] **Step 1: live server RED tests를 작성한다**

`RANDOM_PORT + WebTestClient.bindToServer(JdkClientHttpConnector)`로 exact endpoint/status/problem/JSON field를 검증한다. SSE는 최초 notification 뒤 REST snapshot refresh를 요구하며 replay를 주장하지 않는다. demo profile 미활성, operator scope 누락, path tenant mismatch는 fail closed다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-spring:integrationTest \
  --tests '*SpringJobConsoleHttpTest' \
  --tests '*SpringJobConsoleSseTest' \
  --tests '*SpringDemoScopeTest' \
  --max-workers=1
```

- [ ] **Step 3: Spring adapter를 구현한다**

`SseEmitter` write는 client별 virtual thread에서 수행하고 bounded queue 초과/timeout/disconnect 시 client를 제거한다. executor는 application이 소유하며 shutdown에서 신규 claim 중지, bounded drain, close 순서로 종료한다. static UI bytes는 core resource에서 읽는다.

- [ ] **Step 4: lifecycle와 shared parity GREEN을 검증한다**

```bash
./gradlew :operations-job-console-spring:integrationTest --max-workers=1
```

Expected: live HTTP/SSE, executor close, slow client removal, health/readiness, static UI, shared contract PASS.

- [ ] **Step 5: Lore commit**

Not-tested trailer에는 Ktor parity가 다음 task에서 검증된다고 기록한다.

### Task 9: Ktor adapter, live REST/SSE와 coroutine lifecycle

**Files:**
- Create: Ktor production files listed in section 2
- Create: Ktor tests `KtorJobConsoleHttpTest.kt`, `KtorJobConsoleSseTest.kt`, `KtorDemoScopeTest.kt`, `KtorWorkerLifecycleTest.kt`, `KtorStaticUiTest.kt`, `KtorContractParityTest.kt`

**Depends on:** Task 7. **Complexity:** high.

- [ ] **Step 1: Ktor live RED tests를 작성한다**

실제 Netty random port와 Ktor client SSE를 사용해 Spring과 동일한 contract matrix를 실행한다. `CancellationException` 전파, parent scope cancellation, owned dispatcher/resource close를 별도 test로 고정한다.

- [ ] **Step 2: RED 실행**

```bash
./gradlew :operations-job-console-ktor:integrationTest \
  --tests '*KtorJobConsoleHttpTest' \
  --tests '*KtorJobConsoleSseTest' \
  --tests '*KtorWorkerLifecycleTest' \
  --max-workers=1
```

- [ ] **Step 3: Ktor adapter를 구현한다**

Ktor SSE plugin heartbeat를 사용하고 core blocking call은 application-owned scope에서 `Dispatchers.IO`로 실행한다.
REST request/response와 SSE `data`는 `KtorJackson3Support`가 core DTO를 Jackson 3로 encode/decode한다. adapter DTO나 별도 kotlinx-serialization mirror를 만들지 않으며 parity test는 parsed field set과 stable problem code를 비교한다.

```kotlin
try {
    withContext(Dispatchers.IO) { workerEngine.runOnce() }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    observability.workerFailure(failure)
}
```

- [ ] **Step 4: GREEN과 cross-adapter parity를 검증한다**

```bash
./gradlew :operations-job-console-spring:integrationTest :operations-job-console-ktor:integrationTest --max-workers=1
```

동일 black-box fixture의 request/response/problem/SSE field 결과를 adapter별 evidence로 보존한다.

- [ ] **Step 5: Lore commit**

Rejected trailer에 단일 profile module로 Spring/Ktor classpath를 합치는 방식을 기록한다.

### Task 10: 결정론적 failure/restart/parity suite

**Files:**
- Create: core tests `worker/JobFailureMatrixIntegrationTest.kt`, `worker/JobRestartRecoveryTest.kt`, `worker/JobRetryExhaustionTest.kt`
- Create: adapter tests `SpringFailureMatrixTest.kt`, `KtorFailureMatrixTest.kt`
- Modify: core test fixtures as required

**Depends on:** Tasks 8-9. **Complexity:** high.

- [ ] **Step 1: 설계의 failure matrix를 executable test로 작성한다**

1. same submit replay
2. same-tenant concurrent FIFO
3. queued cancel/claim race
4. running cancel at checkpoint
5. Redis notification loss
6. worker restart/lease expiry/stale commit denial
7. SSE disconnect/reconnect then REST convergence
8. duplicate outbox publication
9. retry exhaustion with exactly one dead-letter history

- [ ] **Step 2: failure suite RED/GREEN을 반복한다**

```bash
./gradlew \
  :operations-job-console-core:integrationTest \
  :operations-job-console-spring:integrationTest \
  :operations-job-console-ktor:integrationTest \
  --tests '*FailureMatrix*' \
  --tests '*JobRestartRecoveryTest' \
  --tests '*JobRetryExhaustionTest' \
  --max-workers=1
```

Expected: 동일 seed와 barrier에서 반복 PASS. sleep, 외부 network, test ordering 의존 없음.

- [ ] **Step 3: stability scan을 실행한다**

```bash
for run in 1 2 3; do ./gradlew :operations-job-console-core:integrationTest :operations-job-console-spring:integrationTest :operations-job-console-ktor:integrationTest --tests '*FailureMatrix*' --max-workers=1 || exit 1; done
```

- [ ] **Step 4: Lore commit**

Tested trailer에 3회 반복과 seed를 기록한다.

### Task 11: browser UI와 관측성/보안 경계

**Files:**
- Create: core UI assets
- Create/Modify: core `observability/JobConsoleObservability.kt`
- Modify: Spring/Ktor health/routes
- Create: tests `JobConsoleObservabilityTest.kt`, adapter `StaticUiContractTest.kt`, `ReadinessContractTest.kt`

**Depends on:** Tasks 8-10. **Complexity:** medium.

- [ ] **Step 1: UI/observability RED tests를 작성한다**

UI에는 jobsAhead, ETA range/confidence/sampleSize, progress/checkpoint, cancel acknowledgement, last snapshot update를 포함한다. operator view는 redacted backlog, oldest wait, lease count, retry/dead-letter, PostgreSQL/Redis readiness만 보여 준다. metric tag와 log에 tenant/job/raw payload가 없는지 검증한다.

- [ ] **Step 2: RED 실행 후 최소 UI와 metrics를 구현한다**

```bash
./gradlew :operations-job-console-core:test --tests '*ObservabilityTest' \
  :operations-job-console-spring:integrationTest --tests '*StaticUi*' --tests '*Readiness*' \
  :operations-job-console-ktor:integrationTest --tests '*StaticUi*' --tests '*Readiness*' \
  --max-workers=1
```

- [ ] **Step 3: degraded/failure health를 검증한다**

PostgreSQL unavailable은 `/readyz` non-ready, Redis unavailable은 ready+degraded, `/healthz`는 process liveness만 반영해야 한다.

- [ ] **Step 4: Lore commit**

Directive trailer에 identity를 metric label이나 browser payload에 추가하지 말라고 기록한다.

### Task 12: README와 세 다이어그램을 one-asset loop로 제작

**Files:**
- Create: `operations/README.md`, `operations/README.ko.md`
- Create: 세 module의 bilingual README
- Modify: root bilingual README
- Create: architecture/sequence/state SVG와 PNG 6개

**Depends on:** implementation source complete. **Complexity:** high. **Required skill:** `bluetape-diagram`을 asset마다 다시 적용한다.

- [ ] **Step 1: source 기반 README outline과 parity test를 작성한다**

영문/한글 문서에 실행법, API, failure fixture, Java 25 요구사항, DB/Redis 권위, ETA 비-SLA, demo header 비-production 경계, rollback 순서를 동일 heading/image target으로 둔다.

- [ ] **Step 2: architecture asset을 단독 제작·검증한다**

```bash
xmllint --noout docs/images/readme-diagrams/operations-job-console-readme-architecture-01.svg
cairosvg docs/images/readme-diagrams/operations-job-console-readme-architecture-01.svg \
  -o docs/images/readme-diagrams/operations-job-console-readme-architecture-01.png -s 2
```

core/Spring/Ktor/PostgreSQL/Redis의 authority와 notification 방향을 표현한 뒤 geometry/connector/endpoint/text audit와 full-size PNG 검사를 마쳐야 sequence로 넘어간다.

- [ ] **Step 3: sequence asset을 단독 제작·검증한다**

submit -> transaction -> claim -> checkpoint/outbox -> SSE notification -> REST snapshot refresh를 표현한다. Redis cancel signal 유실 뒤 DB checkpoint 수렴을 alt branch로 표시한다. sequence style validator와 동일 SVG/PNG 검증을 통과한다.

- [ ] **Step 4: state diagram을 단독 제작·검증한다**

`queued`, `running`, `cancel_requested`, `succeeded`, `failed`, `dead_lettered`, `cancelled`와 retry/cancel/checkpoint branch를 큰 글자와 분리된 connector로 표현한다. terminal state의 재전이 금지와 retry 시 enqueue identity 보존을 note로 표시한다.

- [ ] **Step 5: README에 동일 asset을 연결하고 검증한다**

```bash
node scripts/validate-readme-language.mjs
node scripts/validate-readme-parity.mjs
./scripts/smoke-validate.sh diagram-qa
```

두 README validator는 repo-wide walker다. 현재 baseline은 범위 밖인 `image-processing/profile-image-moderation/README.md`의 language switch와 한글 marker 때문에 각각 1건 실패한다. 신규 `operations` README pair는 별도 bounded parity check로 통과시키고, repo-wide 결과는 기존 baseline보다 악화되지 않았음을 review 문서에 기록한다.

- [ ] **Step 6: Lore commit**

Tested trailer에 XML, CairoSVG scale 2, diagram audits, full-size inspection, README parity를 기록한다.

### Task 13: repository registration, CI/nightly, stale/coverage surface

**Files:**
- Modify: `AGENTS.md`, root `README.md`, `README.ko.md`
- Modify: `.github/workflows/Examples.yml`, `.github/workflows/nightly.yml`
- Modify: `scripts/smoke-validate.sh`
- Inspect/modify only if path enumeration requires it: `.github/scripts/aggregate-kover-coverage.py`

**Depends on:** Tasks 1-12. **Complexity:** medium. **Hazard:** smoke와 Testcontainers lane 혼합.

- [ ] **Step 1: registration RED를 기록한다**

```bash
./scripts/smoke-validate.sh stale-check
rg -n 'operations-job-console' .github/workflows/Examples.yml .github/workflows/nightly.yml scripts/smoke-validate.sh AGENTS.md README.md README.ko.md
```

Expected: module graph에는 보이지만 docs/workflow matrix가 아직 완전하지 않다.

- [ ] **Step 2: 모든 registration surface를 갱신한다**

- `AGENTS.md` module map과 root project tree에 `operations/`를 추가한다.
- core의 container-free tests만 `all-smoke`와 `Examples.yml` smoke lane에 추가한다.
- 세 module full tests는 `operations` full group과 `Examples.yml` container lane에 `--max-workers=1`로 추가한다.
- `Examples.yml` path filters, result artifact paths, summary `needs`를 확인한다.
- nightly full의 root `test`가 세 module을 포함하는지 확인하고, 별도 job을 만들면 summary `needs`도 함께 갱신한다.
- Kover/Codecov aggregator가 glob 기반이면 코드 수정 없이 세 output을 발견하는 검증 evidence를 남긴다. explicit path이면 세 module path를 추가한다.
- stale-check의 active module count가 환경 변수로 pin되지 않은 현재 구조를 유지하되 root/module README broken links를 검사한다.

- [ ] **Step 3: workflow와 validation을 검증한다**

```bash
./gradlew projects
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh operations
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml
python3 .github/scripts/aggregate-kover-coverage.py .
```

Coverage aggregator의 실제 CLI가 directory contract와 다르면 `--help`를 먼저 읽고 같은 목적의 bounded command로 교체해 review에 기록한다. coverage는 report-only이며 threshold를 새로 만들지 않는다.

- [ ] **Step 4: Lore commit**

Constraint trailer에 core smoke와 container-backed full lane 분리를 기록한다.

### Task 14: 최종 검증, lesson/review, exact-head PR

**Files:**
- Create: `docs/lessons/2026-07-21-issue-520-job-operations-console.md`
- Create: `docs/review/2026-07-21-issue-520-job-operations-console-review.md`
- Modify: implementation only for verified defects

**Depends on:** Tasks 1-13. **Complexity:** high. **Stop condition:** P0/P1 0, 검증 통과, local/remote/PR head 일치. Merge는 수행하지 않는다.

- [ ] **Step 1: targeted-to-broad verification을 순서대로 실행한다**

```bash
./gradlew :operations-job-console-core:test
./gradlew :operations-job-console-core:integrationTest --max-workers=1
./gradlew :operations-job-console-spring:test
./gradlew :operations-job-console-spring:integrationTest --max-workers=1
./gradlew :operations-job-console-ktor:test
./gradlew :operations-job-console-ktor:integrationTest --max-workers=1
./scripts/smoke-validate.sh operations
./gradlew :operations-job-console-core:detekt :operations-job-console-spring:detekt :operations-job-console-ktor:detekt
./gradlew build -x test --parallel --continue
./scripts/smoke-validate.sh all-smoke
./scripts/smoke-validate.sh stale-check
./scripts/smoke-validate.sh diagram-qa
git diff --check origin/develop...HEAD
```

container test가 local Docker/Colima 환경 때문에 실행 불가하면 단순 통과로 간주하지 않고 exact error, next-best compile/unit evidence, CI-required status를 review 문서에 기록한다.

- [ ] **Step 2: 7-tier review와 수정 loop를 수행한다**

review 문서는 다음 독립 관점을 구분해 기록한다.

1. user/caller contract와 browser usability
2. domain/state/data consistency
3. security/privacy/trusted demo scope
4. failure/recovery/idempotency/lease
5. concurrency/coroutine/lifecycle/resource cleanup
6. performance/backpressure/query plan
7. repository integration/docs/operations

각 finding에 priority, file:line, evidence, disposition을 기록하고 P0/P1을 모두 수정한 뒤 affected targeted test와 broad gate를 다시 실행한다.

- [ ] **Step 3: lesson과 최종 commit을 만든다**

lesson은 PostgreSQL authority, Redis/SSE advisory 경계, framework lifecycle 차이, deterministic fixture, 상태 다이어그램이 설명한 핵심을 한국어로 기록한다. Lore commit의 Tested/Not-tested trailer는 실제 결과만 적는다.

- [ ] **Step 4: PR을 생성하고 exact head를 검증한다**

```bash
git push -u origin feature/issue-520-job-operations-console
gh pr create --base develop --head feature/issue-520-job-operations-console \
  --title "feat: add Job Operations Console reference implementations" \
  --body-file .omx/issue-520-pr-body.md
git rev-parse HEAD
git rev-parse origin/feature/issue-520-job-operations-console
gh pr view --json number,url,headRefOid,baseRefName,mergeStateStatus,statusCheckRollup,reviews
```

PR body는 영어로 issue link, architecture, contract/failure evidence, Java 25 boundary, test results, known gaps를 포함한다. `.omx/issue-520-pr-body.md`는 git에 추가하지 않는다.

- [ ] **Step 5: merge-ready gate에서 중단한다**

CI, review/thread, local head, remote head, PR head가 일치하면 exact SHA와 PR URL을 보고하고 새 merge 승인을 기다린다. 자동 merge, tag, release, branch/worktree 삭제는 수행하지 않는다.

## 4. Acceptance criteria 추적

| Acceptance criterion | Primary implementation | Proof |
|---|---|---|
| 동일 Spring/Ktor HTTP/SSE contract | Tasks 7-9 | shared parity tests, live server tests |
| PostgreSQL queue/cancel/checkpoint/history authority | Tasks 3-5 | repository concurrency/recovery tests |
| tenant FIFO와 active 최대 1 | Task 4 | claim concurrency + partial unique constraint |
| duplicate submit | Task 3 | same/same, same/different, concurrent owner tests |
| cancellation race와 Redis loss | Task 5 | queued/running/lost-signal fixtures |
| worker restart와 stale fencing | Tasks 4, 10 | lease recovery and stale write tests |
| outbox duplicate와 SSE reconnect | Tasks 7, 10 | stable event ID and REST convergence tests |
| retry exhaustion | Task 10 | exactly-one dead-letter history test |
| queue position와 honest ETA | Task 6 | projection/sample/query-plan tests |
| demo scope fail closed와 redaction | Tasks 8, 9, 11 | adapter scope/security tests |
| bounded SSE/resource lifecycle | Tasks 7-11 | slow client, shutdown, cancellation tests |
| 새 모듈만 Java 25 | Tasks 1, 13 | Gradle compile/toolchain/workflow evidence |
| bilingual README와 state diagram | Task 12 | parity, diagram QA, full-size inspection |
| repository workflow/stale/coverage 등록 | Task 13 | smoke/full/actionlint/stale/coverage evidence |

## 5. 계획 리뷰

| Priority | Lens | Finding | Resolution in plan |
|---|---|---|---|
| P1 | requirements | 공통 fixture가 adapter lifecycle까지 소유하면 설계 경계를 위반할 수 있음 | Task 7에서 driver/base URL만 받고 server lifecycle은 adapter test가 소유하도록 고정 |
| P1 | data consistency | application clock lease는 restart와 skew에서 권위가 약함 | Task 4에서 PostgreSQL server time과 fixture expiry transaction을 필수화 |
| P1 | failure | Redis publish 실패가 HTTP cancel을 실패시킬 위험 | Task 5에서 DB commit 후 best-effort signal과 degraded readiness를 고정 |
| P1 | concurrency | slow SSE client가 outbox와 다른 client를 막을 수 있음 | Tasks 7-9에서 bounded client queue와 제거/close test를 필수화 |
| P1 | security | trusted header가 기본 profile에서 활성화될 위험 | Tasks 8-9에서 explicit demo profile과 path/scope fail-closed test를 필수화 |
| P1 | repository | core container test를 smoke lane에 넣을 위험 | Tasks 1, 13에서 순수 core test와 container/full test task를 분리 |
| P1 | test topology | 동일 `test` task에 unit/container/live test를 혼합하면 smoke가 Docker를 요구함 | Task 1에서 `integration` tag와 세 module의 `integrationTest` task를 명시하고 이후 명령을 분리 |
| P1 | test runtime | custom `integrationTest`가 root `test`의 mutex/JVM/locale 설정을 자동 상속하지 않음 | Task 1에서 module-local runtime helper와 `test-mutex` 사용을 두 task에 적용 |
| P1 | wire parity | Ktor kotlinx serialization mirror를 만들면 Spring Jackson 3 DTO와 field drift가 생김 | Task 9에서 core DTO를 직접 읽고 쓰는 Ktor Jackson 3 codec과 parity test를 고정 |
| P2 | dependency drift | repo guide와 root README의 Spring 표기가 catalog보다 뒤처져 있음 | 실제 `gradle/libs.versions.toml`의 Kotlin 2.4.0, Spring Boot 4.1.0, Ktor 3.5.0을 계획 권위로 사용 |
| P2 | validation baseline | repo-wide README validator는 기존 범위 밖 오류 1건을 포함함 | Task 12에서 신규 pair bounded 검증과 baseline 비악화를 함께 기록 |
| P2 | performance | exact jobsAhead가 backlog에 비례함 | Task 6에서 tenant index plan과 bounded page를 검증하고 #522 경계를 문서화 |
| P2 | lifecycle | Ktor broad catch가 cancellation을 삼킬 수 있음 | Task 9에서 `CancellationException` 우선 재전파 test와 implementation shape 고정 |
| P2 | docs | state diagram이 구현과 분리되어 낡을 수 있음 | Task 12를 source complete 이후로 두고 상태 전이 test와 동일 label을 사용 |
| P2 | coverage | Kover aggregator가 glob인지 explicit인지 사전 확정되지 않음 | Task 13에서 inspection 후 필요한 경우에만 path 변경, report-only 검증 |

리뷰 결론: 구현 시작을 막는 미해결 P0/P1은 0건이다. 모든 설계 요구사항은 Task 1-14와 acceptance 추적표에 매핑되어 있고, 구현은 계획 승인 후에만 시작한다.

## 6. 완료 조건

- 모든 checkbox가 실제 evidence와 함께 완료됨
- state, idempotency, FIFO, lease, cancellation, Redis loss, outbox, retry, restart test 통과
- Spring/Ktor shared black-box parity와 live HTTP/SSE test 통과
- Java 25 세 module compile/test, root Java 21 workflow 유지 확인
- smoke/full 분리, actionlint, stale check, README language/parity, diagram QA, `git diff --check` 통과
- architecture/sequence/state SVG canonical source와 CairoSVG scale 2 PNG, full-size inspection 완료
- 7-tier review P0/P1 0
- exact local/remote/PR head와 CI/review 상태를 보고하고 merge 승인 gate에서 정지
