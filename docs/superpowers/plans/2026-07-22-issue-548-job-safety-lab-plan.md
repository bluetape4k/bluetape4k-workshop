# 리더 직업안전연구소 추진계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 리더 선택만으로는 왜 부족한지 설명하고 6가지 생산 실패 시나리오에 걸쳐 펜싱, PostgreSQL 권한, 롤아웃 가드 및 외부 효과 복구를 입증하는 Java 25 Spring Boot 워크숍 모듈을 구축합니다.

**아키텍처:** `bluetape4k-leader-redis-lettuce`는 리더 후보의 범위를 좁히는 반면 로컬 `FencingLeasePort` 어댑터는 `bluetape4k-lettuce` Lua 실행을 사용하여 리소스 범위의 단조 토큰을 생성합니다. Exposed JDBC 트랜잭션은 현재 tenant/region/version 메타데이터와 최신 펜스만 허용한 다음 보호된 변형, 체크포인트, 실행 결과 및 발신함을 원자적으로 커밋합니다. 외부 효과는 안정적인 작업 ID, 멱등성 및 조정을 사용합니다.

**기술 스택:** Kotlin 2.4, Java 25개 툴체인, Spring Boot 4 MVC/Security/Actuator, `bluetape4k-leader-redis-lettuce`, `bluetape4k-lettuce`, JetBrains Exposed JDBC, `bluetape4k-exposed-jdbc`, PostgreSQL, Redis, Testcontainers, JUnit 5, `bluetape4k-assertions`, 가상 스레드.

**실행 결정:** 사용자가 명시적으로 요청한 대로 현재 기능 작업 트리에서 인라인 실행입니다. 하위 에이전트 구현이나 검토 파견이 없습니다.

---

## 1. 파일 맵

### 모듈 및 구성

- `leader/job-safety-lab/build.gradle.kts`: Java 25, Spring Boot, Bluetape/Exposed/Redis 종속성, 기본 및 선택 테스트 작업.
- `leader/job-safety-lab/src/main/resources/application.yml`: 검증된 Redis/PostgreSQL/job-safety 기본값.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyApplication.kt`: Spring Boot 진입점.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyProperties.kt`: 구성 속성 및 의미론적 유효성 검사.
- `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/config/JobSafetyConfiguration.kt`: 가상 스레드 실행기 및 어댑터 배선.

### 도메인 및 조정

- `.../domain/JobSafetyTypes.kt`: 입력된 소유자, 펜스, 충돌, 멤버십, 지역, 버전, 작업 식별자.
- `.../domain/JobExecution.kt`: 요청, 상태, 거부 이유, 타임라인, 스냅샷 모델.
- `.../coordination/FencingLeasePort.kt`: acquire/renew/release 봉인된 계약서.
- `.../coordination/LeaderElectionPort.kt`: Bluetape 리더 주변의 리더 acquire/use 경계입니다.
- `.../coordination/JobRunCoordinator.kt`: 획득 순서, 릴리스 수명 주기, 실행 조정.
- `.../coordination/redis/JobFencingScripts.kt`: Lua 소스 및 결과 디코딩 계약.
- `.../coordination/redis/RedisJobFencingLeaseAdapter.kt`: `RedisScriptRunner` 구현.
- `.../coordination/redis/RedisLeaderElectionAdapter.kt`: `bluetape4k-leader-redis-lettuce` 어댑터.

### PostgreSQL 권한

- `.../persistence/JobSafetyTables.kt`: Exposed 테이블 및 제약 조건.
- `.../persistence/JobSafetyEntities.kt`: Exposed DAO 엔터티.
- `.../persistence/JobSafetyExposedJdbcRepository.kt`: 필수 `ExposedJdbcRepository` 위임 기반입니다.
- `.../persistence/JobSafetyRepositories.kt`: 할당, 롤아웃, 리소스, 실행, 체크포인트, 보낸 편지함, 영수증 저장소.
- `.../persistence/JobSafetyJdbcExecutor.kt`: 제한된 Exposed 트랜잭션 경계.
- `.../execution/FencedJobExecutionService.kt`: 전제 조건 검증 및 원자 울타리 돌연변이.

### 시나리오 및 효과

- `.../scenario/JobSafetyScenario.kt`: 6개의 시나리오 이름과 unsafe/safe 모드 모델.
- `.../scenario/JobSafetyScenarioService.kt`: 결정론적 설정, 실행 및 스냅샷 API.
- `.../scenario/UnsafeScenarioAdapter.kt`: 프로필 기반 교육 기준만 해당됩니다.
- `.../effect/ExternalEffectPort.kt`: 안정적인 운영 lookup/execute 계약.
- `.../effect/DeterministicExternalEffectAdapter.kt`: 스크립트된 멱등성 가짜 공급자.
- `.../effect/OutboxEffectWorker.kt`: 청구, 전달, 모호한 결과, 조정.

### 웹 및 테스트

- `.../web/JobSafetyController.kt`: 안전한 run/reset/query/reconcile 엔드포인트.
- `.../web/UnsafeJobSafetyController.kt`: 이중으로 차단된 안전하지 않은 엔드포인트.
- `.../web/JobSafetySecurityConfiguration.kt`: 운영자 및 인증된 액세스 규칙.
- `.../web/JobSafetyApiModels.kt`: 검증된 request/response DTO.
- `leader/job-safety-lab/src/test/kotlin/...`: 유닛, 아키텍처, 보안, PostgreSQL/Redis 통합, 엔드투엔드, README 계약 테스트.
- `leader/job-safety-lab/src/test/resources/junit-platform.properties`: 결정적 JUnit 설정.
- `leader/job-safety-lab/src/test/resources/logback-test.xml`: 제한된 테스트 로깅.

### 문서화 및 저장소 등록

- `leader/job-safety-lab/README.md`, `README.ko.md`: 실행 가능한 가이드 및 생산 경계.
- `docs/images/readme-diagrams/leader-job-safety-lab-readme-{architecture,state,lease-overrun,microservices}-01.{svg,png}`: 다이어그램 쌍 4개.
- `scripts/generate-job-safety-lab-diagrams.mjs`: 결정적 SVG 생성.
- `scripts/validate-job-safety-lab-readme.mjs`: locale/command/state/link 유효성 검사.
- `README.md`, `README.ko.md`, `.github/workflows/Examples.yml`, `scripts/smoke-validate.sh`: 등록 체인.
- `docs/lessons/2026-07-22-issue-548-job-safety-lab.md`: 지속 가능한 구현 교훈.

## 2. 주문된 실행 작업

### 작업 1: Java 25 Spring Boot 모듈 스캐폴드

**복잡성:** 작음
**다음에 따라 다름:** 승인된 사양 및 계획
**패턴 지침:** `bluetape-kotlin-patterns` 모듈 설정, `ecc-springboot-kotlin`

**파일:**
- 생성: `leader/job-safety-lab/build.gradle.kts`
- 생성: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyApplication.kt`
- 생성: `leader/job-safety-lab/src/main/resources/application.yml`
- 생성: `leader/job-safety-lab/src/test/resources/junit-platform.properties`
- 생성: `leader/job-safety-lab/src/test/resources/logback-test.xml`
- 테스트: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/JobSafetyRuntimeContractTest.kt`

- [ ] **1단계: 실패한 런타임 계약 테스트 작성**

```kotlin
class JobSafetyRuntimeContractTest {
    @Test
    fun `runtime uses Java 25 without preview`() {
        Runtime.version().feature() shouldBeEqualTo 25
        ManagementFactory.getRuntimeMXBean().inputArguments shouldNotContain "--enable-preview"
    }
}
```

- [ ] **2단계: 프로젝트가 등록되지 않았으므로 RED를 확인하세요**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRuntimeContractTest'`
예상: FAIL 및 `project 'leader-job-safety-lab' not found`.

- [ ] **3단계: 모듈 빌드 및 부팅 진입점 추가**

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

`gradle/libs.versions.toml`에 이미 있는 별칭만 사용: Bluetape core/logging/assertions/JUnit5/Testcontainers, virtual-thread API/JDK25 런타임, 리더 core/Redis Lettuce, Lettuce, Exposed core/DAO/JDBC/Spring Boot JDBC, Spring Boot webmvc/security/validation/actuator/JDBC, PostgreSQL 드라이버 및 PostgreSQL Testcontainers. 버전이나 개별 Bluetape BOM를 추가하지 마십시오.

- [ ] **4단계: GREEN 확인 및 프로젝트 검색**

실행: `./gradlew projects | rg 'leader-job-safety-lab'`
예상: 등록된 프로젝트 라인 1개.

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRuntimeContractTest'`
예상: PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Run the job safety lab on the workshop Java baseline`
테스트된 예고편: 런타임 계약 및 `./gradlew projects`.

### 작업 2: 오용 방지 도메인 계약 정의

**복잡성:** 중간
**다음에 따라 다름:** 작업 1
**패턴 지침:** `bluetape-kotlin-patterns`, `ecc-kotlin-patterns`

**파일:**
- 생성: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobSafetyTypes.kt`
- 생성: `leader/job-safety-lab/src/main/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobExecution.kt`
- 테스트: `leader/job-safety-lab/src/test/kotlin/io/bluetape4k/workshop/leader/jobsafety/domain/JobSafetyTypesTest.kt`

- [ ] **1단계: 입력된 ID 및 의미 제약 조건에 대한 RED 테스트 작성**

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

- [ ] **2단계: 누락된 유형에 대한 테스트가 실패했는지 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyTypesTest'`
예상: 해결되지 않은 `FencingToken` 및 `ConflictKey`에 대한 컴파일 실패.

- [ ] **3단계: 변경할 수 없는 값 객체 및 실행 상태 구현**

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

`LeaderOwnerId`, `FencingOwnerId`, `TenantId`, `ConflictKey`, `MembershipRevision`, `RegionId`, `RegionEpoch`, `NamespaceEpoch`, `ExecutionContractVersion` 및 `OperationId`를 고유한 검증된 유형으로 정의합니다. 내구성 있는 데이터 클래스는 `Serializable`을 구현하고 `serialVersionUID`을 선언합니다.

- [ ] **4단계: GREEN을 확인하고 생산되지 않음 `!!`**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyTypesTest'`
예상: PASS.

실행: `if rg -n '!!' leader/job-safety-lab/src/main; then exit 1; fi`
예상: 출력이 없습니다.

- [ ] **5단계: 커밋**

커밋 의도: `Make ownership and fencing impossible to confuse`.

### 작업 3: 결정적 포트를 사용하여 조정 수명 주기 고정

**복잡성:** 높음
**다음에 따라 다름:** 작업 2
**패턴 지침:** TDD, `bluetape-kotlin-patterns`

**파일:**
- 생성: `.../coordination/FencingLeasePort.kt`
- 생성: `.../coordination/LeaderElectionPort.kt`
- 생성: `.../coordination/JobRunCoordinator.kt`
- 테스트: `.../coordination/JobRunCoordinatorTest.kt`
- 테스트 픽스처: `.../support/DeterministicLeaseAdapters.kt`

- [ ] **1단계: RED 수명 주기 테스트 작성**

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

- [ ] **2단계: RED에서 누락된 코디네이터가 있는지 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobRunCoordinatorTest'`
예상: 해결되지 않은 조정 계약에 대한 컴파일 실패.

- [ ] **3단계: 봉인된 임대 결과 및 조정자 구현**

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

릴리스 실패는 기록되며 이미 커밋된 결과를 대체하지 않습니다. Acquisition/backend 실패는 결코 DB 실행으로 이어지지 않습니다.

- [ ] **4단계: 성공, 경합, 예외 및 릴리스 실패 전반에 걸쳐 GREEN 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobRunCoordinatorTest'`
예상: 수면 기반 테스트가 없는 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Separate leader candidacy from resource fencing`.

### 작업 4: Exposed 데이터베이스 권한 구축

**복잡성:** 높음
**다음에 따라 다름:** 작업 2-3
**패턴 지침:** `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`; 원시 SQL 금지됨

**파일:**
- 생성: `.../persistence/JobSafetyTables.kt`
- 생성: `.../persistence/JobSafetyEntities.kt`
- 생성: `.../persistence/JobSafetyExposedJdbcRepository.kt`
- 생성: `.../persistence/JobSafetyRepositories.kt`
- 생성: `.../persistence/JobSafetyJdbcExecutor.kt`
- 테스트: `.../persistence/JobSafetyRepositoryContractTest.kt`
- 테스트 픽스처: `.../persistence/JobSafetyDatabaseFixture.kt`

- [ ] **1단계: RED 저장소 아키텍처 및 고정 테스트 작성**

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

- [ ] **2단계: 지속성 유형이 없으므로 RED 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRepositoryContractTest'`
예상: 누락된 저장소로 인해 컴파일이 실패했습니다.

- [ ] **3단계: 테이블, 엔터티, 저장소 위임 및 실행자 구현**

```kotlin
abstract class JobSafetyExposedJdbcRepository<E : Entity<ID>, ID : Any>(
    domainClass: Class<E>,
) : ExposedJdbcRepository<E, ID> by SimpleExposedJdbcRepository(
    ExposedEntityInformationImpl(domainClass),
)
```

할당, 롤아웃 마커, 리소스, 실행, 체크포인트, 아웃박스 및 효과 수신에 대한 Exposed 테이블을 정의합니다. Exposed `SchemaUtils.createMissingTablesAndColumns`, DAO/DSL insert/update/select/delete, 최상위 Exposed 연산자, 수신기 충돌을 위한 명명된 로컬 및 하나의 `JobSafetyJdbcExecutor.transaction {}` 경계를 사용하세요. 마이그레이션 SQL, JDBC 호출, `JdbcTemplate` 또는 `Transaction.exec`을 생성하지 마세요.

- [ ] **4단계: GREEN 확인 및 금지된 DB 액세스 검사**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyRepositoryContractTest'`
예상: PASS.

실행: `if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("|src/main/resources/db/migration' leader/job-safety-lab; then exit 1; fi`
예상: 출력이 없습니다.

- [ ] **5단계: 커밋**

커밋 의도: `Keep job authority inside the Exposed boundary`.

### 작업 5: PostgreSQL에서 원자 울타리 돌연변이 증명

**복잡성:** 높음
**다음에 따라 다름:** 작업 4
**패턴 지침:** Exposed DSL, PostgreSQL-권한 있는 동시성

**파일:**
- 생성: `.../execution/FencedJobExecutionService.kt`
- 테스트: `.../execution/FencedJobExecutionServiceTest.kt`
- 통합 테스트: `.../execution/FencedMutationPostgresIntegrationTest.kt`

- [ ] **1단계: 울타리 및 권한 거부에 대한 RED 테스트 작성**

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

- [ ] **2단계: 누락된 실행 서비스에서 RED 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*FencedJobExecutionServiceTest'`
예상: 컴파일 실패.

- [ ] **3단계: 조건부 업데이트 횟수를 사용하여 하나의 Exposed 트랜잭션 구현**

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

리소스를 업데이트하기 전에 활성 멤버십 개정, write-home region/epoch, 최소 작성자 버전 및 체크포인트 스키마를 검증하십시오. 업데이트 횟수 0은 현재 신뢰할 수 있는 행에 의해 매핑되며 호출자 스냅샷에서는 절대 추측되지 않습니다.

- [ ] **4단계: 단위 및 PostgreSQL 통합 테스트로 GREEN 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*FencedJobExecutionServiceTest'`
예상: PASS.

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*FencedMutationPostgresIntegrationTest'`
예상: PostgreSQL Testcontainers에 대해 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Reject stale job generations where state is authoritative`.

### 작업 6: Redis Lua 펜싱 임대 구현

**복잡성:** 높음
**다음에 따라 다름:** 작업 2-3
**패턴 지침:** Bluetape Lettuce `RedisScript`/`RedisScriptRunner`, 서버 측 원자성

**파일:**
- 생성: `.../coordination/redis/JobFencingScripts.kt`
- 생성: `.../coordination/redis/RedisJobFencingLeaseAdapter.kt`
- 테스트: `.../coordination/redis/JobFencingScriptsTest.kt`
- 통합 테스트: `.../coordination/redis/RedisJobFencingLeaseIntegrationTest.kt`

- [ ] **1단계: RED 계약 테스트 작성**

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

- [ ] **2단계: RED에서 Redis 어댑터가 누락되었는지 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobFencingScriptsTest'`
예상: 컴파일 실패.

- [ ] **3단계: 스크립트 및 어댑터 구현**

스크립트 의미 획득:

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

Renew/release 소유자와 울타리를 모두 비교합니다. 양수 TTL, 동일 슬롯 키 파생, 숫자 구문 분석, `Long.MAX_VALUE` 오버플로, 네임스페이스 에포크 마커 및 누락 카운터 복구 상태를 검증합니다. `RedisScriptRunner`을 통해 실행하므로 `EVALSHA` 및 `NOSCRIPT` 대체가 Bluetape 내부에 유지됩니다.

- [ ] **4단계: `SCRIPT FLUSH`을 포함하여 GREEN 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobFencingScriptsTest'`
예상: PASS.

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*RedisJobFencingLeaseIntegrationTest'`
예상: 획득, 재시도, 갱신, 해제, 인계, 잘못된 상태, 동일 슬롯, 오버플로, 에포크 불일치 및 `SCRIPT FLUSH`에 대해 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Mint orderable job generations without changing leader tokens`.

### 작업 7: Bluetape Redis 리더 선택 적응

**복잡성:** 중간
**다음에 따라 다름:** 작업 1 및 3
**패턴 지침:** 현재 `bluetape4k-leader-redis-lettuce` API, 명시적인 리소스 소유권

**파일:**
- 생성: `.../coordination/redis/RedisLeaderElectionAdapter.kt`
- 테스트: `.../coordination/redis/RedisLeaderElectionAdapterTest.kt`
- 수정: `.../config/JobSafetyConfiguration.kt`

- [ ] **1단계: RED 어댑터 테스트 작성**

```kotlin
@Test
fun `opaque leader token is never exposed as a fencing token`() {
    adapter.tryAcquire(JobName("daily-summary")).use { lease ->
        lease.ownerId shouldBeInstanceOf LeaderOwnerId::class
        lease::class.memberProperties.map { it.name } shouldNotContain "fencingToken"
    }
}
```

- [ ] **2단계: RED에서 누락된 어댑터가 있는지 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*RedisLeaderElectionAdapterTest'`
예상: 컴파일 실패.

- [ ] **3단계: 실제 종속성을 사용하여 어댑터 구현 API**

코딩하기 전에 해결된 `bluetape4k-leader-redis-lettuce` source/JAR를 검사하세요. 지도 라이브러리 획득, 자동 확장 및 결과를 `LeaderLease`로 종료합니다. 백엔드 토큰을 비공개로 유지하세요. 이 구성으로 생성된 리소스만 소유하고 닫습니다.

```kotlin
override fun tryAcquire(jobName: JobName): LeaderLease? =
    backend.tryAcquire(lockName(jobName))?.let { handle ->
        RedisLeaderLease(LeaderOwnerId(ownerIds.nextId()), handle)
    }
```

- [ ] **4단계: GREEN 확인 및 수명주기 정리**

실행: `./gradlew :leader-job-safety-lab:test --tests '*RedisLeaderElectionAdapterTest'`
예상: 경합, 자동 확장 실패, 닫기 및 토큰 재해석 없음의 경우 PASS입니다.

- [ ] **5단계: 커밋**

커밋 의도: `Use Bluetape leader election only for candidacy`.

### 작업 8: 작업 간 충돌 및 임대 초과 실행 시연

**복잡성:** 높음
**다음에 따라 다름:** 작업 3-7
**패턴 지침:** 결정적 시나리오 테스트, 제한된 타임라인

**파일:**
- 생성: `.../scenario/JobSafetyScenario.kt`
- 생성: `.../scenario/JobSafetyScenarioService.kt`
- 생성: `.../scenario/UnsafeScenarioAdapter.kt`
- 테스트: `.../scenario/CrossJobCollisionScenarioTest.kt`
- 테스트: `.../scenario/LeaseOverrunScenarioTest.kt`

- [ ] **1단계: RED unsafe/safe 비교 테스트 작성**

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

- [ ] **2단계: 누락된 시나리오가 있는지 RED 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*CrossJobCollisionScenarioTest' --tests '*LeaseOverrunScenarioTest'`
예상: 컴파일 실패.

- [ ] **3단계: 결정적 스크립트 타임라인 구현**

`daily-summary` 및 `backfill-summary`은 서로 다른 리더 이름을 유지하지만 동일한 `ConflictKey.summary(tenant, month)`를 파생합니다. 임대 오버런은 벽시계 절전 모드 없이 논리 이벤트 `A_ACQUIRE_41`, `A_PAUSE`, `A_EXPIRE`, `B_ACQUIRE_42`, `B_COMMIT`, `A_RESUME`를 사용합니다. 타임라인 행을 제한하고 삭제된 행 수를 계산합니다.

- [ ] **4단계: GREEN 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*CrossJobCollisionScenarioTest' --tests '*LeaseOverrunScenarioTest'`
예상되는 결과: 안전하지 않은 결과와 안전한 결과가 명명된 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Show why job locks do not protect shared business state`.

### 작업 9: 테넌트, 지역 및 롤아웃 권한 시연

**복잡성:** 높음
**다음에 따라 다름:** 작업 5 및 8
**패턴 지침:** Exposed 조건부 업데이트, 명시적 롤아웃 프로토콜

**파일:**
- 수정: `.../scenario/JobSafetyScenarioService.kt`
- 테스트: `.../scenario/DynamicTenantScenarioTest.kt`
- 테스트: `.../scenario/RegionPartitionScenarioTest.kt`
- 테스트: `.../scenario/MixedVersionRolloutScenarioTest.kt`

- [ ] **1단계: RED 권한 테스트 작성**

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

- [ ] **2단계: 누락된 시나리오 동작에서 RED 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*DynamicTenantScenarioTest' --tests '*RegionPartitionScenarioTest' --tests '*MixedVersionRolloutScenarioTest'`
예상됨: 거부 동작이 없기 때문에 어설션이 실패합니다.

- [ ] **3단계: 세 가지 권한 시나리오 구현**

안전하지 않은 모드는 스케줄러 스냅샷 또는 로컬 Redis을 신뢰합니다. 안전 모드는 트리거와 커밋 사이에 신뢰할 수 있는 행을 변경한 다음 트랜잭션 시간 거부를 증명합니다. 혼합 롤아웃 설비는 확장 호환 배포 → 체크포인트 스키마 마커 → 최소 작성자 마커를 따르고 마커 다운그레이드를 금지합니다.

- [ ] **4단계: GREEN 및 PostgreSQL 권한 패리티 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*DynamicTenantScenarioTest' --tests '*RegionPartitionScenarioTest' --tests '*MixedVersionRolloutScenarioTest'`
예상: PASS.

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*JobAuthorityPostgresIntegrationTest'`
예상: 모든 안정적인 거부 코드에 대해 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Reject stale topology and rollout assumptions at commit`.

### 작업 10: 차단할 수 없는 외부 효과 포함

**복잡성:** 높음
**다음에 따라 다름:** 작업 4-5
**패턴 지침:** 안정적인 멱등성, 트랜잭션 발신함, 결정적 가짜

**파일:**
- 생성: `.../effect/ExternalEffectPort.kt`
- 생성: `.../effect/DeterministicExternalEffectAdapter.kt`
- 생성: `.../effect/OutboxEffectWorker.kt`
- 테스트: `.../effect/OutboxEffectWorkerTest.kt`
- 통합 테스트: `.../effect/ExternalEffectRecoveryIntegrationTest.kt`

- [ ] **1단계: RED 모호한 결과 테스트 작성**

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

- [ ] **2단계: RED에서 누락된 작업자 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*OutboxEffectWorkerTest'`
예상: 컴파일 실패.

- [ ] **3단계: DB 보류 네트워크 호출 없이 claim/deliver/reconcile 구현**

짧은 트랜잭션에서 보낼 편지함 행 하나를 요청하고, 트랜잭션을 해제하고, 저장된 `OperationId`을 사용하여 공급자를 호출한 다음 새 트랜잭션에 confirmed/declined/unknown를 기록합니다. 알 수 없는 응답은 새로운 작업을 생성하지 않습니다. 소비자 영수증은 `(provider, operationId)` 고유성을 강화합니다.

- [ ] **4단계: GREEN 확인 및 복구 다시 시작**

실행: `./gradlew :leader-job-safety-lab:test --tests '*OutboxEffectWorkerTest'`
예상: PASS.

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*ExternalEffectRecoveryIntegrationTest'`
예상: PASS 컨텍스트 다시 시작 및 중복 전달 후.

- [ ] **5단계: 커밋**

커밋 의도: `Recover external effects without pretending they are fenced`.

### 작업 11: 프로덕션에 안전한 Spring Boot 구성 및 API 추가

**복잡성:** 중간
**다음에 따라 다름:** 작업 3-10
**패턴 지침:** Spring Boot 4 MVC/Security, 검증, 안전한 기본값

**파일:**
- 생성: `.../config/JobSafetyProperties.kt`
- 수정: `.../config/JobSafetyConfiguration.kt`
- 생성: `.../web/JobSafetyApiModels.kt`
- 생성: `.../web/JobSafetyController.kt`
- 생성: `.../web/UnsafeJobSafetyController.kt`
- 생성: `.../web/JobSafetySecurityConfiguration.kt`
- 테스트: `.../config/JobSafetyConfigurationTest.kt`
- 테스트: `.../web/JobSafetyControllerTest.kt`
- 테스트: `.../web/JobSafetySecurityTest.kt`

- [ ] **1단계: RED 구성 및 인증 테스트 작성**

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

- [ ] **2단계: RED에서 부팅 구성이 누락되었는지 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyConfigurationTest' --tests '*JobSafetyControllerTest' --tests '*JobSafetySecurityTest'`
예상: 컴파일 실패.

- [ ] **3단계: 검증된 속성, 가상 스레드 실행기, MVC 및 보안 구현**

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

안전하지 않은 컨트롤러에는 `lab-unsafe` 프로필과 `job-safety.lab.unsafe-enabled=true`이 모두 필요합니다. reset/reconcile/unsafe 운영자 권한이 필요합니다. 긍정적인 TTL, 지원되는 지역, 네임스페이스 시대, 제한된 타임라인 및 닫힌 시나리오 이름을 검증합니다.
CSRF은(는) 이 모듈이 작업장 고정 장치에서 HTTP Basic으로 인증된 무상태 JSON API를 노출하기 때문에 비활성화됩니다. 쿠키 지원 브라우저 세션이 구성되어 있지 않습니다.

- [ ] **4단계: GREEN 및 가상 스레드 수명 주기 확인**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyConfigurationTest' --tests '*JobSafetyControllerTest' --tests '*JobSafetySecurityTest'`
예상: PASS 실행기 종료 및 금지된 엔드포인트 사례를 포함합니다.

- [ ] **5단계: 커밋**

커밋 의도: `Expose failure labs without weakening production defaults`.

### 작업 12: 옵트인 백엔드 및 엔드투엔드 증명 추가

**복잡성:** 높음
**다음에 따라 다름:** 작업 5-11
**패턴 지침:** Testcontainers 직렬화, 결정적 기본 경로

**파일:**
- 수정: `leader/job-safety-lab/build.gradle.kts`
- 생성: `.../support/AbstractJobSafetyIntegrationTest.kt`
- 생성: `.../JobSafetyEndToEndIntegrationTest.kt`
- 생성: `.../JobSafetyContextRestartIntegrationTest.kt`
- 생성: `.../KotlinPatternArchitectureTest.kt`

- [ ] **1단계: RED 엔드투엔드 및 아키텍처 테스트 작성**

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

- [ ] **2단계: 작업 및 일정이 불완전하므로 RED를 확인하세요**

실행: `./gradlew :leader-job-safety-lab:integrationTest --tests '*JobSafetyEndToEndIntegrationTest'`
예상: 컨테이너 배선이 완료되기 전 FAIL.

- [ ] **3단계: 옵트인 작업 및 직렬화된 공유 컨테이너 추가**

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

Bluetape PostgreSQL/Redis Testcontainers 도우미를 사용하세요. 시작, 컨텍스트 다시 시작, Redis 스크립트 캐시 플러시, DB 롤백, 중복 보낸 편지함 배달 및 컨테이너 정리를 순차적으로 확인합니다.

- [ ] **4단계: 기본 경로와 통합 경로를 별도로 확인**

실행: `./gradlew :leader-job-safety-lab:test`
예상: 컨테이너를 시작하지 않고 PASS.

실행: `./gradlew :leader-job-safety-lab:integrationTest --max-workers=1`
예상: PostgreSQL 및 Redis 컨테이너가 있는 PASS.

- [ ] **5단계: 커밋**

커밋 의도: `Prove job fencing against real Redis and PostgreSQL`.

### 작업 13: 이중 언어 런북 작성 및 다이어그램 생성

**복잡성:** 높음
**다음에 따라 다름:** 작업 1-12
**패턴 지침:** `bluetape-writer`, `bluetape-diagram`

**파일:**
- 생성: `leader/job-safety-lab/README.md`
- 생성: `leader/job-safety-lab/README.ko.md`
- 생성: `scripts/generate-job-safety-lab-diagrams.mjs`
- 생성: `scripts/validate-job-safety-lab-readme.mjs`
- 생성: `docs/images/readme-diagrams/` 아래에 4개의 SVG 및 4개의 PNG 파일 생성
- 테스트: `.../JobSafetyReadmeContractTest.kt`

- [ ] **1단계: RED README 계약 테스트 작성**

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

- [ ] **2단계: README 파일이 존재하지 않으므로 RED을 확인하세요**

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest'`
예상: README 누락에 대해 FAIL.

- [ ] **3단계: README 로케일 쌍 및 다이어그램 생성기 작성**

두 README 파일 모두 prerequisites/Java 25, 시작 명령, 안전 및 안전하지 않은 시나리오 명령, 아키텍처, 실행 상태, 임대 오버런 시퀀스, 마이크로서비스 추출, 상태 정의, 테스트 맵, Redis 카운터 기록 복구, 혼합 버전 롤아웃, 보안, 관찰 가능성 및 제한 사항을 포함합니다. 테넌트 스케줄러, 백엔드 비교, 블로그 PR #249 및 프로젝트 #1068를 연결합니다.

다음을 위한 SVG 소스 및 PNG 렌더링 생성:

1. 아키텍처 및 권한 경계;
2. 실행 상태 다이어그램;
3. A41 일시 중지 → B42 커밋 → A41 시퀀스 거부;
4. 모듈식 모노리스에서 마이크로서비스 추출로.

- [ ] **4단계: README 및 렌더링된 자산 확인**

실행: `node scripts/generate-job-safety-lab-diagrams.mjs`
예상: 8개의 결정적 자산.

실행: `node scripts/validate-job-safety-lab-readme.mjs`
제목, 명령, 링크, 시나리오, 상태 및 로캘 패리티에 대해 예상되는 내용은 PASS입니다.

실행: `./scripts/smoke-validate.sh diagram-qa`
예상: PASS.

실행: `./gradlew :leader-job-safety-lab:test --tests '*JobSafetyReadmeContractTest'`
예상: PASS.

- [ ] **5단계: PNG 파일 4개 모두 검사**

원본 세부 정보에서 모든 PNG을 열고 읽을 수 있는 레이블을 확인하고 A41/B42 순서가 올바른지, 잘린 노드가 없는지, 상태 화살표가 올바른지, 서비스 소유권이 명확한지 확인합니다. 소스를 수리하고 모두 통과할 때까지 재생성합니다.

- [ ] **6단계: 커밋**

커밋 의도: `Teach operators where leader safety actually ends`.

### 작업 14: 유지 관리된 모듈 표면 등록

**복잡성:** 중간
**다음에 따라 다름:** 작업 12-13
**패턴 지침:** 모듈 등록 위험 체크리스트

**파일:**
- 수정: `README.md`
- 수정: `README.ko.md`
- 수정: `.github/workflows/Examples.yml`
- 수정: `scripts/smoke-validate.sh`

현재 소스 검색에서는 이 저장소에 Kover/Codecov 모듈 목록이 없으므로 적용 범위 집계 파일이 수정되지 않습니다. 예제 워크플로 경로 필터, 컨테이너 명령 및 업로드된 테스트 결과 경로는 적용 가능한 CI 등록 표면입니다.

- [ ] **1단계: 두 로캘 모두에 루트 모듈 매트릭스와 명령을 추가합니다**

`leader-job-safety-lab`을 고급으로 등록하고, Java 25, Spring Boot, Exposed JDBC, leader/Lettuce, PostgreSQL + Redis Testcontainers를 등록합니다. English/Korean 기능 설명을 동일하게 유지하세요.

- [ ] **2단계: 전체 레인에 컨테이너 지원 검증 적용**

`.github/workflows/Examples.yml`의 푸시 및 풀 요청 경로 필터에 `leader/job-safety-lab/**`을 추가합니다. 기본 작업에서는 통합 태그를 제외하므로 기존 비컨테이너 예제 명령에 `:leader-job-safety-lab:test`를 추가합니다. 직렬화된 컨테이너 지원 명령에 `:leader-job-safety-lab:integrationTest`을 추가하고, 이를 대표 모듈 주석에 문서화하고, 해당 XML/HTML 디렉터리를 `container-example-test-results`에 추가합니다. `scripts/smoke-validate.sh`의 `full` 분기에 기본 테스트와 통합 작업을 추가합니다. `all-smoke`에 통합 작업을 추가하지 마세요.

- [ ] **3단계: 전체 등록 체인 확인**

실행: `./gradlew projects | rg 'leader-job-safety-lab'`
예상: 하나의 프로젝트.

실행: `./scripts/smoke-validate.sh stale-check`
예상: PASS.

실행: `actionlint .github/workflows/Examples.yml`
예상: PASS.

실행: `rg -n 'leader-job-safety-lab' README.md README.ko.md .github/workflows/Examples.yml scripts/smoke-validate.sh`
예상: 모든 필수 등록 표면이 나열됩니다.

- [ ] **4단계: 커밋**

커밋 의도: `Keep the job safety lab on the maintained workshop path`.

### 작업 15: 위험 검색 및 최종 확인 실행

**복잡성:** 높음
**다음에 따라 다름:** 작업 1-14
**패턴 지침:** 완료 전 확인, Kotlin 체크리스트, performance/stability 스캔

**파일:**
- 생성: `docs/lessons/2026-07-22-issue-548-job-safety-lab.md`
- 증거 검토에 유용한 경우 만들기: `docs/review/2026-07-22-issue-548-job-safety-lab.md`
- 확인된 결과를 복구하기 위해서만 이전 파일을 수정하세요.

- [ ] **1단계: 종속성 순서에 따라 대상 확인 실행**

순차적으로 실행:

```bash
./gradlew :leader-job-safety-lab:test
./gradlew :leader-job-safety-lab:integrationTest --max-workers=1
./gradlew :leader-job-safety-lab:detekt :leader-job-safety-lab:detektTest
node scripts/validate-job-safety-lab-readme.mjs
./scripts/smoke-validate.sh diagram-qa
./scripts/smoke-validate.sh stale-check
git diff --check
```

예상: 모든 명령이 `0` 종료됩니다. 설명되지 않은 재시도 전용 패스가 없습니다.

- [ ] **2단계: 명시적 소스 가드 실행**

```bash
if rg -n 'JdbcTemplate|PreparedStatement|createStatement|Transaction\.exec|exec\("' leader/job-safety-lab; then exit 1; fi
if rg -n '!!|println\(|System\.(out|err)' leader/job-safety-lab/src/main; then exit 1; fi
if rg -n 'LeaderLockHandle.*fenc|leader.*token.*FencingToken' leader/job-safety-lab/src; then exit 1; fi
```

예상: 일치하는 항목이 없습니다.

- [ ] **3단계: 성능 및 안정성 검토 실행**

Exposed 트랜잭션 내에서 Redis/provider 호출이 발생하지 않는지 확인하고, 생성된 모든 client/executor이 닫히고, 펜스 단축키가 리소스 범위에 지정되고, timeline/result 컬렉션이 제한되고, 가상 스레드 작업이 종료되고, Testcontainers가 ​​직렬화되고, 재시도가 동일한 작업을 유지하고 ID, cancellation/close 경로가 실패를 숨기지 않는지 확인합니다.

- [ ] **4단계: 6개의 인라인 코드 검토 관점 실행 및 차단 요소 복구**

성능, 안정성, 보안, Ops, developer/API 및 user/caller을 정확한 차이점과 독립적으로 검토하세요. P0/P1/P2/P3를 정규화하고 모든 P0/P1을 수정하고 영향을 받은 테스트를 다시 실행하고 P2/P3 처리를 기록합니다. P0=0 및 P1=0에서만 중지합니다.

- [ ] **5단계: 강의 작성 및 커밋**

한국어 수업에서는 컨텍스트, leader/lease/fence/DB/outbox 간의 분리, 실패한 테스트 또는 놀라운 API 발견, 확인 명령, 검토 누락 및 불투명한 리더 토큰이 울타리가 되어서는 안 되는 미래의 가드를 기록합니다.

커밋 의도: `Preserve the failure boundaries proven by the job safety lab`.

- [ ] **6단계: 정확한 분기 수렴**

실행: `git status --short`
예상 : 깨끗합니다.

실행: `git log --oneline origin/develop..HEAD`
예상: 이슈 #548에 대해 의도적인 Lore 커밋만 있습니다.

실행: `git diff --stat origin/develop...HEAD`
예상: 모듈, 등록, 다이어그램, spec/plan/review/lesson 표면만 해당됩니다.

## 3. 위험 예측 및 복구 시점

| 위험 | 신호 | 완화 | Rollback/rerun점 |
|---|---|---|---|
| Redis 카운터 롤백 | 반환된 펜스가 DB 펜스를 초과하지 않음, 기존 리소스에 대한 카운터 누락, 에포크 불일치 | 실패 시 닫히고 네임스페이스 에포크 롤오버가 필요함 | 실험실 고정 장치만 재설정합니다. 생산 카운터를 절대 줄이지 마세요 |
| 오래된 작업자 덮어쓰기 | 인계 후 조건부 업데이트 횟수 `0` | 현재 권한을 `STALE_FENCE`에 매핑 | 태스크 5 단위 + PostgreSQL 통합 재실행 |
| 독립 지역 Redis | 비고향 지역은 지역적으로 높은 울타리를 제공합니다 | DB write-home region/epoch 조건 | 할당 복원; 작업 9 지역 테스트 다시 실행 |
| 혼합 버전 손상 | 마커 아래 작성자 또는 호환되지 않는 체크포인트 스키마 | 확장 호환 롤아웃 및 마커 순서 | 호환되는 리더로만 롤백합니다. 작업 9 롤아웃 테스트 다시 실행 |
| 공급자 중복 | ID 작업에 대해 1보다 큰 실행 횟수 | 안정적인 운영 ID 및 수신 고유성 | 기존 ID를 조정합니다. 작업 10 다시 실행 |
| Redis/DB 거래 중인 통화 | 소스 검사 또는 연결 기아 | 엄격한 실행자 경계 및 아키텍처 테스트 | 작업 5, 6 또는 10으로 돌아가기 |
| Testcontainers 플레이크 | 재시도 전용 통과, port/container 수명 주기 오류 | `TestMutexService`으로 직렬화하고 재실행하기 전에 조사 | 원인이 해결되면 실패한 통합 작업을 다시 시작 |
| 다이어그램 드리프트 | README state/link이 소스와 다르거나 잘림 PNG | 생성기 + 검증기 + 원본 크기 검사 | 작업 13을 복구하고 영향을 받은 모든 자산을 재생성합니다. |

## 4. 사양별 추적성

| 사양 요구 사항 | 계획 작업 및 증명 |
|---|---|
| Java 25개 Spring Boot만 | 작업 1 런타임 계약 |
| 블루테이프 지도자 선거 | 작업 7 어댑터 계약 |
| 로컬 Lua 펜싱 포트 | 작업 3 및 6 unit/integration 테스트 |
| ExposedJdbcRepository 및 원시 없음 SQL | 작업 4 architecture/source 경비원 |
| 조건부 오래된 작성자 거부 | 작업 5 PostgreSQL 테스트 |
| 직장 간 충돌 | Task 8 시나리오 테스트 |
| 임대 초과 | 작업 8과 12 |
| 동적 임차인 | 9과제 회원자격 테스트 |
| 지역 파티션 | 과제 9 지역권한시험 |
| 혼합 버전 출시 | 태스크 9 롤아웃 테스트 |
| 울타리가 불가능한 효과 | 태스크 10 회복 테스트 |
| 결정적 기본 테스트 | 작업 3, 8-10, 12 기본 작업 증명 |
| 선택 PostgreSQL/Redis 증명 | 과제 5~6, 12 통합과제 |
| 안전 API 및 안전하지 않은 이중 게이트 | 작업 11 보안 테스트 |
| README 로케일 패리티 및 상태 다이어그램 | 작업 13 검증 및 육안 검사 |
| 마이크로서비스 가이드 | 작업 13 추출 다이어그램 및 산문 |
| module/full-nightly 등록 | 작업 14 등록 명령 |
| 강의, 복습, 정확한 지점 검증 | 작업 15 |

## 5. 자체 검토 계획

### 사양 범위

6가지 이슈 시나리오 모두, 5가지 고유한 안전 보장, Java 25/Spring 부팅, Bluetape 재사용,
Exposed 저장소 제약, Redis 기록 손실 복구, 보안, deterministic/default 및 선택
테스트, 이중 언어 문서, 다이어그램, 마이크로서비스 지침, 등록, 강의 및 PR 준비 증거 맵
작업 1-15.

### 종속성 순서

나중에 생성된 유형을 호출하는 작업은 없습니다. 도메인 유형이 포트보다 우선합니다. 포트는 코디네이터보다 우선합니다. 고집
실행에 앞서; 실행 및 Redis 시나리오 선행; outbox는 API 통합보다 우선합니다. 행동
문서 및 등록보다 우선합니다. 모든 아티팩트는 최종 검증보다 우선합니다.

### 유형 및 명령 일관성

계획에서는 `FencingToken`, `FencingOwnerId`, `ConflictKey`, `NamespaceEpoch`을 일관되게 사용합니다.
`MembershipRevision`, `RegionEpoch`, `ExecutionContractVersion`, `OperationId`,
`JobExecutionState`, `JobRejectionReason`, `integrationTest` 및 모듈 경로
`:leader-job-safety-lab`. 자리 표시자와 모호한 단계 스캔은 계획을 확정하기 전에 비어 있어야 합니다.

## 6. 인라인 6관점 계획 검토

| 렌즈 | 수리 찾기 및 계획 | 최종 결과 |
|---|---|---|
| 성과 | 리소스 범위의 핫키 검토, 제한된 타임라인, transaction/network 분리 및 옵트인 스트레스 증거 추가 | P0=0, P1=0 |
| 안정성 | 릴리스 실패 의미, 카운터 롤백, 다시 시작, 정리, Testcontainers 직렬화 및 재시도 전용 조사 추가 | P0=0, P1=0 |
| 보안 | 안전하지 않은 이중 게이트, 생산 빈 부재, 운영자 인증, secret/token 로깅 가드 추가 | P0=0, P1=0 |
| Operator/Ops | 에포크 롤오버, 롤아웃 순서, health/runbook, 등록, 정확한 검증 및 롤백 지점 추가 | P0=0, P1=0 |
| Developer/API | 정확한 파일 소유권, 형식화된 계약, 실제 종속성 API 검사, Exposed 소스 가드, TDD 명령이 추가되었습니다. P0=0, P1=0 |
| User/caller | 이중 언어 실행 가능 가이드, unsafe/safe 비교, 상태 정의, 4개 다이어그램, 제한 사항 및 마이크로서비스 추출 추가 | P0=0, P1=0 |

기본 세션 통합에서 누락된 승인 기준, 하위 종속성 또는 해결되지 않은 P2/P3이 발견되지 않았습니다.
사용자가 이 커밋된 계획을 승인할 때까지 구현이 차단된 상태로 유지됩니다.
