# Leader Job Safety Lab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 25 Spring Boot workshop module that demonstrates why leader election alone is insufficient and proves fencing, PostgreSQL authority, rollout guards, and external-effect recovery across six production failure scenarios.

**Architecture:** `bluetape4k-leader-redis-lettuce` narrows leader candidates while a local `FencingLeasePort` adapter uses `bluetape4k-lettuce` Lua execution to mint resource-scoped monotonic tokens. An Exposed JDBC transaction accepts only current tenant/region/version metadata and a newer fence, then commits the protected mutation, checkpoint, execution result, and outbox atomically. External effects use stable operation IDs, idempotency, and reconciliation.

**Tech Stack:** Kotlin 2.4, Java 25 toolchain, Spring Boot 4 MVC/Security/Actuator, `bluetape4k-leader-redis-lettuce`, `bluetape4k-lettuce`, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL, Redis, Testcontainers, JUnit 5, `bluetape4k-assertions`, virtual threads.

**Execution decision:** Inline execution in the current feature worktree, as explicitly requested by the user. No subagent implementation or review dispatch.

---

## 1. File map

### Module and configuration

- `leader/job-safety-lab/build.gradle.kts`: Java 25, Spring Boot, Bluetape/Exposed/Redis dependencies, default and opt-in test tasks.
- `leader/job-safety-lab/src/main/resources/application.yml`: validated Redis/PostgreSQL/job-safety defaults.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyApplication.kt`: Spring Boot entry point.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyProperties.kt`: configuration properties and semantic validation.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyConfiguration.kt`: virtual-thread executor and adapter wiring.

### Domain and coordination

- `.../domain/JobSafetyTypes.kt`: typed owner, fence, conflict, membership, region, version, operation identifiers.
- `.../domain/JobExecution.kt`: request, state, rejection reason, timeline, snapshot models.
- `.../coordination/FencingLeasePort.kt`: acquire/renew/release sealed contracts.
- `.../coordination/LeaderElectionPort.kt`: leader acquire/use boundary around Bluetape leader.
- `.../coordination/JobRunCoordinator.kt`: acquisition order, release lifecycle, execution orchestration.
- `.../coordination/redis/JobFencingScripts.kt`: Lua sources and result decoding contract.
- `.../coordination/redis/RedisJobFencingLeaseAdapter.kt`: `RedisScriptRunner` implementation.
- `.../coordination/redis/RedisLeaderElectionAdapter.kt`: `bluetape4k-leader-redis-lettuce` adapter.

### PostgreSQL authority

- `.../persistence/JobSafetyTables.kt`: Exposed tables and constraints.
- `.../persistence/JobSafetyEntities.kt`: Exposed DAO entities.
- `.../persistence/JobSafetyExposedJdbcRepository.kt`: mandatory `ExposedJdbcRepository` delegation base.
- `.../persistence/JobSafetyRepositories.kt`: assignment, rollout, resource, execution, checkpoint, outbox, receipt repositories.
- `.../persistence/JobSafetyJdbcExecutor.kt`: bounded Exposed transaction boundary.
- `.../execution/FencedJobExecutionService.kt`: precondition validation and atomic fenced mutation.

### Scenarios and effects

- `.../scenario/JobSafetyScenario.kt`: six scenario names and unsafe/safe mode model.
- `.../scenario/JobSafetyScenarioService.kt`: deterministic setup, execution, and snapshot API.
- `.../scenario/UnsafeScenarioAdapter.kt`: profile-gated educational baseline only.
- `.../effect/ExternalEffectPort.kt`: stable operation lookup/execute contract.
- `.../effect/DeterministicExternalEffectAdapter.kt`: scripted idempotent fake provider.
- `.../effect/OutboxEffectWorker.kt`: claim, delivery, ambiguous result, reconciliation.

### Web and tests

- `.../web/JobSafetyController.kt`: safe run/reset/query/reconcile endpoints.
- `.../web/UnsafeJobSafetyController.kt`: double-gated unsafe endpoint.
- `.../web/JobSafetySecurityConfiguration.kt`: operator and authenticated access rules.
- `.../web/JobSafetyApiModels.kt`: validated request/response DTOs.
- `leader/job-safety-lab/src/test/kotlin/...`: unit, architecture, security, PostgreSQL/Redis integration, end-to-end, README contract tests.
- `leader/job-safety-lab/src/test/resources/junit-platform.properties`: deterministic JUnit settings.
- `leader/job-safety-lab/src/test/resources/logback-test.xml`: bounded test logging.

### Documentation and repository registration

- `leader/job-safety-lab/README.md`, `README.ko.md`: executable guide and production boundaries.
- `docs/images/readme-diagrams/leader-job-safety-lab-readme-{architecture,state,lease-overrun,microservices}-01.{svg,png}`: four diagram pairs.
- `scripts/generate-job-safety-lab-diagrams.mjs`: deterministic SVG generation.
- `scripts/validate-job-safety-lab-readme.mjs`: locale/command/state/link validation.
- `README.md`, `README.ko.md`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`: registration chain.
- `docs/lessons/2026-07-22-issue-548-job-safety-lab.md`: durable implementation lesson.

## 2. Ordered implementation tasks

### Task 1: Scaffold the Java 25 Spring Boot module

**Complexity:** Small
**Depends on:** Approved spec and plan
**Pattern guidance:** `bluetape-kotlin-patterns` module setup, `ecc-springboot-kotlin`

**Files:**
- Create: `leader/job-safety-lab/build.gradle.kts`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyApplication.kt`
- Create: `leader/job-safety-lab/src/main/resources/application.yml`
- Create: `leader/job-safety-lab/src/test/resources/junit-platform.properties`
- Create: `leader/job-safety-lab/src/test/resources/logback-test.xml`
- Test: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyRuntimeContractTest.kt`

- [ ] **Step 1: Write the failing runtime contract test**

```kotlin
class JobSafetyRuntimeContractTest {
    @Test
    fun `runtime uses Java 25 without preview`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"
    }
}
```

- [ ] **Step 2: Verify RED because the project is not registered**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRuntimeContractTest'`
Expected: FAIL with `project 'leader-job-safety-lab' not found`.

- [ ] **Step 3: Add the module build and Boot entry point**

```kotlin
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_25)
}
springBoot {
    mainClass.set("io.bluetape4k.workshop.leader.jobsafety.JobSafetyApplicationKt")
}
```

Use only aliases already present in `gradle/libs.versions.toml`: Bluetape core/logging/assertions/JUnit5/Testcontainers, virtual-thread API/JDK25 runtime, leader core/Redis Lettuce, Lettuce, Exposed core/DAO/JDBC/Spring Boot JDBC, Spring Boot webmvc/security/validation/actuator/JDBC, PostgreSQL driver, and PostgreSQL Testcontainers. Do not add a version or an individual Bluetape BOM.

- [ ] **Step 4: Verify GREEN and project discovery**

Run: `./gradlew projects | rg 'leader-job-safety-lab'`
Expected: one registered project line.

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRuntimeContractTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit intent: `Run the job safety lab on the workshop Java baseline`
Tested trailer: runtime contract and `./gradlew projects`.

### Task 2: Define misuse-resistant domain contracts

**Complexity:** Medium
**Depends on:** Task 1
**Pattern guidance:** `bluetape-kotlin-patterns`, `ecc-kotlin-patterns`

**Files:**
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobSafetyTypes.kt`
- Create: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobExecution.kt`
- Test: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobSafetyTypesTest.kt`

- [ ] **Step 1: Write RED tests for typed IDs and semantic constraints**

```kotlin
@Test
fun `fencing tokens are positive and orderable`() {
    invoking { FencingToken(0) } shouldThrow IllegalArgumentException::class
    (FencingToken(42) > FencingToken(41)) shouldBeTrue()
}

@Test
fun `conflict key is resource scoped rather than job scoped`() {
    ConflictKey.summary(TenantId("tenant-a"), YearMonth.of(2026, 7)).value
        .shouldBeEqualTo("summary:tenant-a:2026-07")
}
```

- [ ] **Step 2: Verify the tests fail for missing types**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyTypesTest'`
Expected: compile failure for unresolved `FencingToken` and `ConflictKey`.

- [ ] **Step 3: Implement immutable value objects and execution states**

```kotlin
@JvmInline value class FencingToken(val value: Long) : Comparable<FencingToken> {
    init { value.requirePositive("fencingToken") }
    override fun compareTo(other: FencingToken): Int = value.compareTo(other.value)
}

enum class JobExecutionState {
    REQUESTED, LEADER_ACQUIRED, FENCE_ACQUIRED, RUNNING, COMMITTED,
    EFFECT_PENDING, RECONCILIATION_REQUIRED, COMPLETED, SKIPPED, REJECTED, FAILED,
}
```

Define `LeaderOwnerId`, `FencingOwnerId`, `TenantId`, `ConflictKey`, `MembershipRevision`, `RegionId`, `RegionEpoch`, `NamespaceEpoch`, `ExecutionContractVersion`, and `OperationId` as distinct validated types. Durable data classes implement `Serializable` and declare `serialVersionUID`.

- [ ] **Step 4: Verify GREEN and no production `!!`**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyTypesTest'`
Expected: PASS.

Run: `if rg -n '!!' leader/job-safety-lab/src/main; then exit 1; fi`
Expected: no output.

- [ ] **Step 5: Commit**

Commit intent: `Make ownership and fencing impossible to confuse`.

### Task 3: Lock the coordination lifecycle with deterministic ports

**Complexity:** High
**Depends on:** Task 2
**Pattern guidance:** TDD, `bluetape-kotlin-patterns`

**Files:**
- Create: `.../coordination/FencingLeasePort.kt`
- Create: `.../coordination/LeaderElectionPort.kt`
- Create: `.../coordination/JobRunCoordinator.kt`
- Test: `.../coordination/JobRunCoordinatorTest.kt`
- Test fixture: `.../support/DeterministicLeaseAdapters.kt`

- [ ] **Step 1: Write RED lifecycle tests**

```kotlin
@Test
fun `leader is acquired before the resource fence and both are released`() {
    val events = mutableListOf<String>()
    val coordinator = coordinatorRecordingInto(events)

    coordinator.run(request()) { JobMutation.Committed }

    events shouldContainExactly listOf(
        "leader.acquire", "fence.acquire", "execute", "fence.release", "leader.release"
    )
}

@Test
fun `fence contention releases the acquired leader lease`() {
    val result = coordinatorWithFenceContention().run(request()) { error("must not execute") }
    result.state shouldBeEqualTo JobExecutionState.SKIPPED
}
```

- [ ] **Step 2: Verify RED for missing coordinator**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobRunCoordinatorTest'`
Expected: compile failure for unresolved coordination contracts.

- [ ] **Step 3: Implement sealed lease results and coordinator**

```kotlin
sealed interface FenceAcquireResult {
    data class Acquired(val lease: FencingLease) : FenceAcquireResult
    data class AlreadyOwned(val lease: FencingLease) : FenceAcquireResult
    data object Contended : FenceAcquireResult
    data class BackendFailure(val cause: Throwable) : FenceAcquireResult
}

fun run(request: JobRunRequest, execute: (FencingLease) -> JobMutation): JobRunResult {
    val leader = leaderElection.tryAcquire(request.jobName) ?: return skipped(LEADER_CONTENDED)
    return leader.use {
        when (val acquired = fencingLease.acquire(request.conflictKey, request.fencingOwnerId, ttl)) {
            is Acquired, is AlreadyOwned -> acquired.lease.useFence(execute)
            Contended -> skipped(FENCE_CONTENDED)
            is BackendFailure -> failed(FENCE_BACKEND_FAILURE, acquired.cause)
        }
    }
}
```

Release failure is logged and does not replace an already committed result. Acquisition/backend failure never falls through to DB execution.

- [ ] **Step 4: Verify GREEN across success, contention, exception, and release failure**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobRunCoordinatorTest'`
Expected: PASS with no sleep-based test.

- [ ] **Step 5: Commit**

Commit intent: `Separate leader candidacy from resource fencing`.

### Task 4: Build the Exposed database authority

**Complexity:** High
**Depends on:** Tasks 2-3
**Pattern guidance:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`; raw SQL forbidden

**Files:**
- Create: `.../persistence/JobSafetyTables.kt`
- Create: `.../persistence/JobSafetyEntities.kt`
- Create: `.../persistence/JobSafetyExposedJdbcRepository.kt`
- Create: `.../persistence/JobSafetyRepositories.kt`
- Create: `.../persistence/JobSafetyJdbcExecutor.kt`
- Test: `.../persistence/JobSafetyRepositoryContractTest.kt`
- Test fixture: `.../persistence/JobSafetyDatabaseFixture.kt`

- [ ] **Step 1: Write RED repository architecture and fixture tests**

```kotlin
@Test
fun `all concrete repositories implement ExposedJdbcRepository`() {
    repositoryTypes.forEach { type ->
        ExposedJdbcRepository::class.java.isAssignableFrom(type.java).shouldBeTrue()
    }
}

@Test
fun `fixture seeds authority using Exposed`() {
    fixture.seedAuthority(authority())
    repositories.assignment.findByTenant(TenantId("tenant-a")) shouldNotBe null
}
```

- [ ] **Step 2: Verify RED because persistence types are absent**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRepositoryContractTest'`
Expected: compile failure for missing repositories.

- [ ] **Step 3: Implement tables, entities, repository delegation, and executor**

```kotlin
abstract class JobSafetyExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
```

Define Exposed tables for assignment, rollout marker, resource, execution, checkpoint, outbox, and effect receipt. Use Exposed `SchemaUtils.createMissingTablesAndColumns`, DAO/DSL insert/update/select/delete, top-level Exposed operators, named locals for receiver collisions, and one `JobSafetyJdbcExecutor.transaction {}` boundary. Do not create migration SQL, JDBC calls, `JdbcTemplate`, or `Transaction.exec`.

- [ ] **Step 4: Verify GREEN and scan forbidden DB access**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRepositoryContractTest'`
Expected: PASS.

Run: `if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("|src/main/resources/db/migration' leader/job-safety-lab; then exit 1; fi`
Expected: no output.

- [ ] **Step 5: Commit**

Commit intent: `Keep job authority inside the Exposed boundary`.

### Task 5: Prove atomic fenced mutation in PostgreSQL

**Complexity:** High
**Depends on:** Task 4
**Pattern guidance:** Exposed DSL, PostgreSQL-authoritative concurrency

**Files:**
- Create: `.../execution/FencedJobExecutionService.kt`
- Test: `.../execution/FencedJobExecutionServiceTest.kt`
- Integration test: `.../execution/FencedMutationPostgresIntegrationTest.kt`

- [ ] **Step 1: Write RED tests for fence and authority rejection**

```kotlin
@Test
fun `fence 41 is rejected after fence 42 commits`() {
    service.execute(request(fence = 42)).state shouldBeEqualTo COMMITTED
    service.execute(request(fence = 41)).rejection shouldBeEqualTo STALE_FENCE
    resource().lastAcceptedFence shouldBeEqualTo FencingToken(42)
}

@Test
fun `checkpoint and outbox roll back when the resource update is stale`() {
    service.execute(request(fence = 41))
    repositories.checkpoint.count() shouldBeEqualTo 0L
    repositories.outbox.count() shouldBeEqualTo 0L
}
```

- [ ] **Step 2: Verify RED at the missing execution service**

Run: `./gradlew :leader-job-safety-lab:test --tests '*FencedJobExecutionServiceTest'`
Expected: compile failure.

- [ ] **Step 3: Implement one Exposed transaction with conditional update count**

```kotlin
val updated = JobSafetyResources.update({
    (JobSafetyResources.conflictKey eq request.conflictKey.value) and
        (JobSafetyResources.namespaceEpoch eq request.namespaceEpoch.value) and
        (JobSafetyResources.lastAcceptedFence less request.fencingToken.value)
}) {
    it[lastAcceptedFence] = request.fencingToken.value
    it[summaryValue] = request.nextValue
}
if (updated != 1) return@transaction rejectCurrentAuthority(request)
checkpointRepository.upsert(request)
executionRepository.markCommitted(request)
outboxRepository.enqueue(request.operationId, request.effect)
```

Before the resource update, validate active membership revision, write-home region/epoch, minimum writer version, and checkpoint schema. A zero update count is mapped by current authoritative rows, never guessed from the caller snapshot.

- [ ] **Step 4: Verify GREEN with unit and PostgreSQL integration tests**

Run: `./gradlew :leader-job-safety-lab:test --tests '*FencedJobExecutionServiceTest'`
Expected: PASS.

Run: `./gradlew :leader-job-safety-lab:integrationTest --tests '*FencedMutationPostgresIntegrationTest'`
Expected: PASS against PostgreSQL Testcontainers.

- [ ] **Step 5: Commit**

Commit intent: `Reject stale job generations where state is authoritative`.

### Task 6: Implement the Redis Lua fencing lease

**Complexity:** High
**Depends on:** Tasks 2-3
**Pattern guidance:** Bluetape Lettuce `RedisScript`/`RedisScriptRunner`, server-side atomicity

**Files:**
- Create: `.../coordination/redis/JobFencingScripts.kt`
- Create: `.../coordination/redis/RedisJobFencingLeaseAdapter.kt`
- Test: `.../coordination/redis/JobFencingScriptsTest.kt`
- Integration test: `.../coordination/redis/RedisJobFencingLeaseIntegrationTest.kt`

- [ ] **Step 1: Write RED contract tests**

```kotlin
@Test
fun `takeover increments the fence and renewal preserves it`() {
    val first = lease.acquire(key, owner("a"), ttl).acquiredLease()
    lease.renew(first, ttl).renewedFence shouldBeEqualTo first.fencingToken
    clock.expire(first)
    val second = lease.acquire(key, owner("b"), ttl).acquiredLease()
    (second.fencingToken > first.fencingToken).shouldBeTrue()
}

@Test
fun `stale owner cannot renew or release the newer generation`() {
    lease.renew(first, ttl) shouldBeEqualTo OwnershipLost
    lease.release(first) shouldBeEqualTo OwnershipLost
}

@Test
fun `malformed active lease fails closed`() {
    redis.set(rawLeaseKey, "missing-separator")
    lease.acquire(key, owner("a"), ttl) shouldBeInstanceOf BackendFailure::class
}
```

- [ ] **Step 2: Verify RED for missing Redis adapter**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobFencingScriptsTest'`
Expected: compile failure.

- [ ] **Step 3: Implement scripts and adapter**

Acquire script semantics:

```lua
local active = redis.call('GET', KEYS[1])
if active then
  local separator = string.find(active, '|', 1, true)
  if string.sub(active, 1, separator - 1) == ARGV[1] then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return {'ALREADY_OWNED', string.sub(active, separator + 1)}
  end
  return {'CONTENDED'}
end
local fence = redis.call('INCR', KEYS[2])
redis.call('PSETEX', KEYS[1], ARGV[2], ARGV[1] .. '|' .. fence)
return {'ACQUIRED', tostring(fence)}
```

Renew/release compare both owner and fence. Validate positive TTL, same-slot key derivation, numeric parsing, `Long.MAX_VALUE` overflow, namespace epoch marker, and missing-counter recovery state. Execute through `RedisScriptRunner` so `EVALSHA` and `NOSCRIPT` fallback stay inside Bluetape.

- [ ] **Step 4: Verify GREEN including `SCRIPT FLUSH`**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobFencingScriptsTest'`
Expected: PASS.

Run: `./gradlew :leader-job-safety-lab:integrationTest --tests '*RedisJobFencingLeaseIntegrationTest'`
Expected: PASS for acquire, retry, renew, release, takeover, malformed state, same-slot, overflow, epoch mismatch, and `SCRIPT FLUSH`.

- [ ] **Step 5: Commit**

Commit intent: `Mint orderable job generations without changing leader tokens`.

### Task 7: Adapt Bluetape Redis leader election

**Complexity:** Medium
**Depends on:** Tasks 1 and 3
**Pattern guidance:** current `bluetape4k-leader-redis-lettuce` API, explicit resource ownership

**Files:**
- Create: `.../coordination/redis/RedisLeaderElectionAdapter.kt`
- Test: `.../coordination/redis/RedisLeaderElectionAdapterTest.kt`
- Modify: `.../config/JobSafetyConfiguration.kt`

- [ ] **Step 1: Write RED adapter tests**

```kotlin
@Test
fun `opaque leader token is never exposed as a fencing token`() {
    adapter.tryAcquire(JobName("daily-summary")).use { lease ->
        lease.ownerId shouldBeInstanceOf LeaderOwnerId::class
        lease::class.memberProperties.map { it.name } shouldNotContain "fencingToken"
    }
}
```

- [ ] **Step 2: Verify RED for missing adapter**

Run: `./gradlew :leader-job-safety-lab:test --tests '*RedisLeaderElectionAdapterTest'`
Expected: compile failure.

- [ ] **Step 3: Implement adapter using the actual dependency API**

Inspect the resolved `bluetape4k-leader-redis-lettuce` source/JAR before coding. Map library acquire, auto-extend, and close outcomes into `LeaderLease`; keep the backend token private. Own and close only resources created by this configuration.

```kotlin
override fun tryAcquire(jobName: JobName): LeaderLease? =
    backend.tryAcquire(lockName(jobName))?.let { handle ->
        RedisLeaderLease(LeaderOwnerId(ownerIds.nextId()), handle)
    }
```

- [ ] **Step 4: Verify GREEN and lifecycle cleanup**

Run: `./gradlew :leader-job-safety-lab:test --tests '*RedisLeaderElectionAdapterTest'`
Expected: PASS for contention, auto-extension failure, close, and no token reinterpretation.

- [ ] **Step 5: Commit**

Commit intent: `Use Bluetape leader election only for candidacy`.

### Task 8: Demonstrate cross-job collision and lease overrun

**Complexity:** High
**Depends on:** Tasks 3-7
**Pattern guidance:** deterministic scenario tests, bounded timeline

**Files:**
- Create: `.../scenario/JobSafetyScenario.kt`
- Create: `.../scenario/JobSafetyScenarioService.kt`
- Create: `.../scenario/UnsafeScenarioAdapter.kt`
- Test: `.../scenario/CrossJobCollisionScenarioTest.kt`
- Test: `.../scenario/LeaseOverrunScenarioTest.kt`

- [ ] **Step 1: Write RED unsafe/safe comparison tests**

```kotlin
@Test
fun `different jobs collide when they protect job names but converge on one conflict key`() {
    val unsafe = scenarios.run(CROSS_JOB_COLLISION, UNSAFE)
    val safe = scenarios.run(CROSS_JOB_COLLISION, SAFE)
    unsafe.finalSummary shouldNotBeEqualTo unsafe.expectedSummary
    safe.finalSummary shouldBeEqualTo safe.expectedSummary
}

@Test
fun `resumed stale worker cannot overwrite the takeover result`() {
    val snapshot = scenarios.run(LEASE_OVERRUN, SAFE)
    snapshot.executions.single { it.fencingToken == FencingToken(41) }.rejection
        .shouldBeEqualTo(STALE_FENCE)
    snapshot.resource.lastAcceptedFence shouldBeEqualTo FencingToken(42)
}
```

- [ ] **Step 2: Verify RED for missing scenarios**

Run: `./gradlew :leader-job-safety-lab:test --tests '*CrossJobCollisionScenarioTest' --tests '*LeaseOverrunScenarioTest'`
Expected: compile failure.

- [ ] **Step 3: Implement deterministic scripted timelines**

`daily-summary` and `backfill-summary` retain distinct leader names but derive the same `ConflictKey.summary(tenant, month)`. Lease overrun uses logical events `A_ACQUIRE_41`, `A_PAUSE`, `A_EXPIRE`, `B_ACQUIRE_42`, `B_COMMIT`, `A_RESUME`, with no wall-clock sleep. Cap timeline rows and count dropped rows.

- [ ] **Step 4: Verify GREEN**

Run: `./gradlew :leader-job-safety-lab:test --tests '*CrossJobCollisionScenarioTest' --tests '*LeaseOverrunScenarioTest'`
Expected: PASS with named unsafe and safe outcomes.

- [ ] **Step 5: Commit**

Commit intent: `Show why job locks do not protect shared business state`.

### Task 9: Demonstrate tenant, region, and rollout authority

**Complexity:** High
**Depends on:** Tasks 5 and 8
**Pattern guidance:** Exposed conditional updates, explicit rollout protocol

**Files:**
- Modify: `.../scenario/JobSafetyScenarioService.kt`
- Test: `.../scenario/DynamicTenantScenarioTest.kt`
- Test: `.../scenario/RegionPartitionScenarioTest.kt`
- Test: `.../scenario/MixedVersionRolloutScenarioTest.kt`

- [ ] **Step 1: Write RED authority tests**

```kotlin
@Test
fun `removed tenant snapshot is rejected at commit`() {
    runWithSnapshot(revision = 7) { fixture.deactivateTenant(nextRevision = 8) }
        .rejection.shouldBeEqualTo(STALE_MEMBERSHIP)
}

@Test
fun `partitioned non-home region cannot write even with a local fence`() {
    runFrom(region = "region-b", epoch = 3, fence = 100)
        .rejection.shouldBeEqualTo(WRONG_REGION)
}

@Test
fun `minimum writer marker blocks the old worker`() {
    runWithContractVersion(1, minimumWriterVersion = 2)
        .rejection.shouldBeEqualTo(INCOMPATIBLE_VERSION)
}
```

- [ ] **Step 2: Verify RED at missing scenario behavior**

Run: `./gradlew :leader-job-safety-lab:test --tests '*DynamicTenantScenarioTest' --tests '*RegionPartitionScenarioTest' --tests '*MixedVersionRolloutScenarioTest'`
Expected: failing assertions for absent rejection behavior.

- [ ] **Step 3: Implement the three authority scenarios**

Unsafe mode trusts the scheduler snapshot or local Redis. Safe mode changes authoritative rows between trigger and commit, then proves transaction-time rejection. Mixed rollout fixtures follow expand-compatible deploy → checkpoint schema marker → minimum writer marker and prohibit marker downgrade.

- [ ] **Step 4: Verify GREEN and PostgreSQL authority parity**

Run: `./gradlew :leader-job-safety-lab:test --tests '*DynamicTenantScenarioTest' --tests '*RegionPartitionScenarioTest' --tests '*MixedVersionRolloutScenarioTest'`
Expected: PASS.

Run: `./gradlew :leader-job-safety-lab:integrationTest --tests '*JobAuthorityPostgresIntegrationTest'`
Expected: PASS for all stable rejection codes.

- [ ] **Step 5: Commit**

Commit intent: `Reject stale topology and rollout assumptions at commit`.

### Task 10: Contain non-fenceable external effects

**Complexity:** High
**Depends on:** Tasks 4-5
**Pattern guidance:** stable idempotency, transactional outbox, deterministic fake

**Files:**
- Create: `.../effect/ExternalEffectPort.kt`
- Create: `.../effect/DeterministicExternalEffectAdapter.kt`
- Create: `.../effect/OutboxEffectWorker.kt`
- Test: `.../effect/OutboxEffectWorkerTest.kt`
- Integration test: `.../effect/ExternalEffectRecoveryIntegrationTest.kt`

- [ ] **Step 1: Write RED ambiguous-result tests**

```kotlin
@Test
fun `unknown provider response is reconciled with the original operation id`() {
    provider.script(operationId, APPLIED_BUT_TIMEOUT)
    worker.deliverNext()
    outbox(operationId).state shouldBeEqualTo RECONCILIATION_REQUIRED

    worker.reconcileNext()

    provider.executeCount(operationId) shouldBeEqualTo 1
    receipt(operationId).result shouldBeEqualTo CONFIRMED
}
```

- [ ] **Step 2: Verify RED for missing worker**

Run: `./gradlew :leader-job-safety-lab:test --tests '*OutboxEffectWorkerTest'`
Expected: compile failure.

- [ ] **Step 3: Implement claim/deliver/reconcile without DB-held network calls**

Claim one outbox row in a short transaction, release the transaction, call the provider with the stored `OperationId`, then record confirmed/declined/unknown in a new transaction. An unknown response never creates a new operation. Consumer receipts enforce `(provider, operationId)` uniqueness.

- [ ] **Step 4: Verify GREEN and restart recovery**

Run: `./gradlew :leader-job-safety-lab:test --tests '*OutboxEffectWorkerTest'`
Expected: PASS.

Run: `./gradlew :leader-job-safety-lab:integrationTest --tests '*ExternalEffectRecoveryIntegrationTest'`
Expected: PASS after context restart and duplicate delivery.

- [ ] **Step 5: Commit**

Commit intent: `Recover external effects without pretending they are fenced`.

### Task 11: Add production-safe Spring Boot configuration and API

**Complexity:** Medium
**Depends on:** Tasks 3-10
**Pattern guidance:** Spring Boot 4 MVC/Security, validation, safe defaults

**Files:**
- Create: `.../config/JobSafetyProperties.kt`
- Modify: `.../config/JobSafetyConfiguration.kt`
- Create: `.../web/JobSafetyApiModels.kt`
- Create: `.../web/JobSafetyController.kt`
- Create: `.../web/UnsafeJobSafetyController.kt`
- Create: `.../web/JobSafetySecurityConfiguration.kt`
- Test: `.../config/JobSafetyConfigurationTest.kt`
- Test: `.../web/JobSafetyControllerTest.kt`
- Test: `.../web/JobSafetySecurityTest.kt`

- [ ] **Step 1: Write RED configuration and authorization tests**

```kotlin
@Test
fun `unsafe controller is absent from production`() {
    contextRunner.withPropertyValues("spring.profiles.active=prod", "job-safety.lab.unsafe-enabled=true")
        .run { it shouldNotHaveBean UnsafeJobSafetyController::class }
}

@Test
fun `reconcile requires operator role`() {
    mvc.post("/api/job-safety/effects/reconcile").andExpect { status { isUnauthorized() } }
}
```

- [ ] **Step 2: Verify RED for missing Boot configuration**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyConfigurationTest' --tests '*JobSafetyControllerTest' --tests '*JobSafetySecurityTest'`
Expected: compile failure.

- [ ] **Step 3: Implement validated properties, virtual-thread executor, MVC and security**

```kotlin
@Bean
fun jobExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

http.authorizeHttpRequests {
    it.requestMatchers(HttpMethod.GET, "/api/job-safety/scenarios/**").authenticated()
    it.requestMatchers("/api/job-safety/effects/**", "/api/job-safety/scenarios/*/reset").hasRole("JOB_SAFETY_OPERATOR")
    it.anyRequest().denyAll()
}
http.csrf { it.disable() }
http.httpBasic(Customizer.withDefaults())
```

Unsafe controller requires both `lab-unsafe` profile and `job-safety.lab.unsafe-enabled=true`; reset/reconcile/unsafe require operator authority. Validate positive TTL, supported region, namespace epoch, bounded timeline, and closed scenario names.
CSRF is disabled only because this module exposes a stateless JSON API authenticated with HTTP Basic in the workshop fixture; no cookie-backed browser session is configured.

- [ ] **Step 4: Verify GREEN and virtual-thread lifecycle**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyConfigurationTest' --tests '*JobSafetyControllerTest' --tests '*JobSafetySecurityTest'`
Expected: PASS including executor shutdown and forbidden endpoint cases.

- [ ] **Step 5: Commit**

Commit intent: `Expose failure labs without weakening production defaults`.

### Task 12: Add opt-in backend and end-to-end proof

**Complexity:** High
**Depends on:** Tasks 5-11
**Pattern guidance:** Testcontainers serialization, deterministic default path

**Files:**
- Modify: `leader/job-safety-lab/build.gradle.kts`
- Create: `.../support/AbstractJobSafetyIntegrationTest.kt`
- Create: `.../JobSafetyEndToEndIntegrationTest.kt`
- Create: `.../JobSafetyContextRestartIntegrationTest.kt`
- Create: `.../KotlinPatternArchitectureTest.kt`

- [ ] **Step 1: Write RED end-to-end and architecture tests**

```kotlin
@Tag("integration")
@Test
fun `takeover commits fence 42 and rejects resumed fence 41`() {
    val snapshot = client.runScenario(LEASE_OVERRUN)
    snapshot.resource.lastAcceptedFence shouldBeEqualTo 42L
    snapshot.timeline.map { it.reason } shouldContain STALE_FENCE.name
}

@Test
fun `production source contains no raw database access`() {
    forbiddenSourceMatches() shouldBeEmpty()
}
```

- [ ] **Step 2: Verify RED because tasks and fixtures are incomplete**

Run: `./gradlew :leader-job-safety-lab:integrationTest --tests '*JobSafetyEndToEndIntegrationTest'`
Expected: FAIL before complete container wiring.

- [ ] **Step 3: Add opt-in task and serialized shared containers**

```kotlin
val integrationTest = tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
}
tasks.test {
    useJUnitPlatform { excludeTags("integration", "stress") }
    usesService(gradle.sharedServices.registrations.named("test-mutex").get().service)
}
```

Use Bluetape PostgreSQL/Redis Testcontainers helpers. Verify startup, context restart, Redis script cache flush, DB rollback, duplicate outbox delivery, and container cleanup sequentially.

- [ ] **Step 4: Verify default and integration paths separately**

Run: `./gradlew :leader-job-safety-lab:test`
Expected: PASS without starting containers.

Run: `./gradlew :leader-job-safety-lab:integrationTest --max-workers=1`
Expected: PASS with PostgreSQL and Redis containers.

- [ ] **Step 5: Commit**

Commit intent: `Prove job fencing against real Redis and PostgreSQL`.

### Task 13: Write bilingual runbooks and generate diagrams

**Complexity:** High
**Depends on:** Tasks 1-12
**Pattern guidance:** `bluetape-writer`, `bluetape-diagram`

**Files:**
- Create: `leader/job-safety-lab/README.md`
- Create: `leader/job-safety-lab/README.ko.md`
- Create: `scripts/generate-job-safety-lab-diagrams.mjs`
- Create: `scripts/validate-job-safety-lab-readme.mjs`
- Create: four SVG and four PNG files under `docs/images/readme-diagrams/`
- Test: `.../JobSafetyReadmeContractTest.kt`

- [ ] **Step 1: Write RED README contract tests**

```kotlin
@Test
fun `both readmes explain all six scenarios and five distinct guarantees`() {
    listOf(readmeEnglish, readmeKorean).forEach { text ->
        scenarioNames.forEach(text::shouldContain)
        listOf("mutual exclusion", "failover", "replay safety", "fencing", "durable completion")
            .forEach(text::shouldContain)
    }
}
```

- [ ] **Step 2: Verify RED because README files do not exist**

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest'`
Expected: FAIL for missing README.

- [ ] **Step 3: Write README locale pair and diagram generator**

Both README files include prerequisites/Java 25, start commands, safe and unsafe scenario commands, architecture, execution state, lease-overrun sequence, microservice extraction, state definitions, test map, Redis counter-history recovery, mixed-version rollout, security, observability, and limitations. Link tenant scheduler, backend comparison, blog PR #249, and projects #1068.

Generate SVG source and PNG render for:

1. architecture and authority boundaries;
2. execution state diagram;
3. A41 pause → B42 commit → A41 reject sequence;
4. modular-monolith to microservices extraction.

- [ ] **Step 4: Validate README and rendered assets**

Run: `node scripts/generate-job-safety-lab-diagrams.mjs`
Expected: eight deterministic assets.

Run: `node scripts/validate-job-safety-lab-readme.mjs`
Expected: PASS for headings, commands, links, scenarios, states, and locale parity.

Run: `./scripts/smoke-validate.sh diagram-qa`
Expected: PASS.

Run: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest'`
Expected: PASS.

- [ ] **Step 5: Inspect all four PNG files**

Open every PNG at original detail and verify readable labels, correct A41/B42 order, no clipped nodes, correct state arrows, and clear service ownership. Repair source and regenerate until all pass.

- [ ] **Step 6: Commit**

Commit intent: `Teach operators where leader safety actually ends`.

### Task 14: Register the maintained module surface

**Complexity:** Medium
**Depends on:** Tasks 12-13
**Pattern guidance:** module registration hazard checklist

**Files:**
- Modify: `README.md`
- Modify: `README.ko.md`
- Modify: `.github/workflows/Examples.yml`
- Modify: `scripts/smoke-validate.sh`

Current source search found no Kover/Codecov module list in this repository, so no coverage aggregation file is modified. The Examples workflow path filters, container command, and uploaded test-result paths are the applicable CI registration surfaces.

- [ ] **Step 1: Add the root module matrix and commands in both locales**

Register `leader-job-safety-lab` as Advanced, Java 25, Spring Boot, Exposed JDBC, leader/Lettuce, PostgreSQL + Redis Testcontainers. Keep English/Korean capability descriptions equivalent.

- [ ] **Step 2: Put container-backed verification in the full lane**

Add `leader/job-safety-lab/**` to both push and pull-request path filters in `.github/workflows/Examples.yml`. Add `:leader-job-safety-lab:test` to the existing non-container example command because the default task excludes integration tags. Add `:leader-job-safety-lab:integrationTest` to the serialized container-backed command, document it in the representative-module comment, and add its XML/HTML directories to `container-example-test-results`. Add the default test plus integration task to the `full` branch of `scripts/smoke-validate.sh`; do not add the integration task to `all-smoke`.

- [ ] **Step 3: Verify the complete registration chain**

Run: `./gradlew projects | rg 'leader-job-safety-lab'`
Expected: one project.

Run: `./scripts/smoke-validate.sh stale-check`
Expected: PASS.

Run: `actionlint .github/workflows/Examples.yml`
Expected: PASS.

Run: `rg -n 'leader-job-safety-lab' README.md README.ko.md .github/workflows/Examples.yml scripts/smoke-validate.sh`
Expected: all required registration surfaces listed.

- [ ] **Step 4: Commit**

Commit intent: `Keep the job safety lab on the maintained workshop path`.

### Task 15: Run risk scans and final verification

**Complexity:** High
**Depends on:** Tasks 1-14
**Pattern guidance:** verification-before-completion, Kotlin checklist, performance/stability scan

**Files:**
- Create: `docs/lessons/2026-07-22-issue-548-job-safety-lab.md`
- Create when useful for review evidence: `docs/review/2026-07-22-issue-548-job-safety-lab.md`
- Modify any earlier file only to repair a verified finding

- [ ] **Step 1: Run targeted verification in dependency order**

Run sequentially:

```bash
./gradlew :leader-job-safety-lab:test
./gradlew :leader-job-safety-lab:integrationTest --max-workers=1
./gradlew :leader-job-safety-lab:detekt :leader-job-safety-lab:detektTest
node scripts/validate-job-safety-lab-readme.mjs
./scripts/smoke-validate.sh diagram-qa
./scripts/smoke-validate.sh stale-check
git diff --check
```

Expected: every command exits `0`; no unexplained retry-only pass.

- [ ] **Step 2: Run explicit source guards**

```bash
if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("' leader/job-safety-lab; then exit 1; fi
if rg -n '!!|println\(|System\.(out|err)' leader/job-safety-lab/src/main; then exit 1; fi
if rg -n 'LeaderLockHandle.*fenc|leader.*token.*FencingToken' leader/job-safety-lab/src; then exit 1; fi
```

Expected: no matches.

- [ ] **Step 3: Run performance and stability review**

Confirm no Redis/provider call occurs inside Exposed transactions, every created client/executor closes, fence hot keys are resource scoped, timeline/result collections are bounded, virtual-thread tasks terminate, Testcontainers are serialized, retry keeps the same operation ID, and cancellation/close paths do not hide failures.

- [ ] **Step 4: Run six inline code-review perspectives and repair blockers**

Review performance, stability, security, Ops, developer/API, and user/caller independently against the exact diff. Normalize P0/P1/P2/P3, fix every P0/P1, rerun affected tests, and record P2/P3 disposition. Stop only at P0=0 and P1=0.

- [ ] **Step 5: Write and commit the lesson**

The Korean lesson records context, the separation between leader/lease/fence/DB/outbox, any failed test or surprising API discovery, verification commands, review misses, and the future guard that the opaque leader token must not become a fence.

Commit intent: `Preserve the failure boundaries proven by the job safety lab`.

- [ ] **Step 6: Converge the exact branch**

Run: `git status --short`
Expected: clean.

Run: `git log --oneline origin/develop..HEAD`
Expected: only intentional Lore commits for issue #548.

Run: `git diff --stat origin/develop...HEAD`
Expected: only the module, registration, diagrams, spec/plan/review/lesson surfaces.

## 3. Risk prediction and recovery points

| Risk | Signal | Mitigation | Rollback/rerun point |
|---|---|---|---|
| Redis counter rollback | returned fence does not exceed DB fence, missing counter for existing resource, epoch mismatch | fail closed and require namespace epoch rollover | reset only lab fixture; never decrement production counter |
| stale worker overwrite | conditional update count `0` after takeover | map current authority to `STALE_FENCE` | rerun Task 5 unit + PostgreSQL integration |
| independent regional Redis | non-home region presents locally high fence | DB write-home region/epoch condition | restore assignment; rerun Task 9 region test |
| mixed-version corruption | writer below marker or incompatible checkpoint schema | expand-compatible rollout and marker order | rollback only to compatible reader; rerun Task 9 rollout test |
| provider duplicate | execute count greater than one for operation ID | stable operation ID and receipt uniqueness | reconcile existing ID; rerun Task 10 |
| Redis/DB call under transaction | source inspection or connection starvation | strict executor boundary and architecture test | return to Tasks 5, 6, or 10 |
| Testcontainers flake | retry-only pass, port/container lifecycle error | serialize with `TestMutexService`, investigate before rerun | restart failed integration task once cause is fixed |
| diagram drift | README state/link differs from source or clipped PNG | generator + validator + original-size inspection | repair Task 13 source and regenerate all affected assets |

## 4. Spec-to-task traceability

| Spec requirement | Plan task and proof |
|---|---|
| Java 25 Spring Boot only | Task 1 runtime contract |
| Bluetape leader election | Task 7 adapter contract |
| local Lua fencing port | Tasks 3 and 6 unit/integration tests |
| ExposedJdbcRepository and no raw SQL | Task 4 architecture/source guards |
| conditional stale-writer rejection | Task 5 PostgreSQL test |
| cross-job collision | Task 8 scenario test |
| lease overrun | Tasks 8 and 12 |
| dynamic tenant | Task 9 membership test |
| region partition | Task 9 region authority test |
| mixed-version rollout | Task 9 rollout test |
| non-fenceable effects | Task 10 recovery test |
| deterministic default tests | Tasks 3, 8-10, 12 default task proof |
| opt-in PostgreSQL/Redis proof | Tasks 5-6 and 12 integration task |
| safe API and unsafe double gate | Task 11 security tests |
| README locale parity and state diagrams | Task 13 validation and visual inspection |
| microservice guide | Task 13 extraction diagram and prose |
| module/full-nightly registration | Task 14 registration commands |
| lesson, review, exact branch verification | Task 15 |

## 5. Plan self-review

### Spec coverage

All six issue scenarios, five distinct safety guarantees, Java 25/Spring Boot, Bluetape reuse,
Exposed repository constraints, Redis history-loss recovery, security, deterministic/default and opt-in
tests, bilingual docs, diagrams, microservice guidance, registration, lesson, and PR-readiness evidence map
to Tasks 1-15.

### Dependency order

No task calls a type produced later: domain types precede ports; ports precede coordinator; persistence
precedes execution; execution and Redis precede scenarios; outbox precedes API integration; behavior
precedes docs and registration; all artifacts precede final verification.

### Type and command consistency

The plan consistently uses `FencingToken`, `FencingOwnerId`, `ConflictKey`, `NamespaceEpoch`,
`MembershipRevision`, `RegionEpoch`, `ExecutionContractVersion`, `OperationId`,
`JobExecutionState`, `JobRejectionReason`, `integrationTest`, and module path
`:leader-job-safety-lab`. Placeholder and vague-step scans must remain empty before plan commit.

## 6. Inline six-perspective plan review

| Lens | Finding and plan repair | Final result |
|---|---|---|
| Performance | Added resource-scoped hot-key review, bounded timeline, transaction/network separation, and opt-in stress evidence | P0=0, P1=0 |
| Stability | Added release-failure semantics, counter rollback, restart, cleanup, Testcontainers serialization, and retry-only investigation | P0=0, P1=0 |
| Security | Added unsafe double gate, production bean absence, operator authorization, secret/token logging guard | P0=0, P1=0 |
| Operator/Ops | Added epoch rollover, rollout order, health/runbook, registration, exact validation and rollback points | P0=0, P1=0 |
| Developer/API | Added exact file ownership, typed contracts, real dependency API inspection, Exposed source guards, TDD commands | P0=0, P1=0 |
| User/caller | Added bilingual runnable guide, unsafe/safe comparison, state definitions, four diagrams, limitations and microservice extraction | P0=0, P1=0 |

Main-session integration found no missing acceptance criterion, backward dependency, or unresolved P2/P3.
Implementation remains blocked until the user approves this committed plan.
