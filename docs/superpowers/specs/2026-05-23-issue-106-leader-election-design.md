# Spec: Issue #106 — Leader Election Scheduled Job 예제

**날짜**: 2026-05-23  
**브랜치**: `feat/issue-106-leader-election`  
**모듈**: `leader/leader-election` → Gradle: `leader-leader-election`

---

## 1. 목적

다중 인스턴스(멀티 파드) 배포 환경에서 Scheduled Job 이 **정확히 1개의 인스턴스** 에서만 실행됨을 보장하는 Workshop 예제 제공.

`bluetape4k-leader` 라이브러리(`LettuceLeaderElector`)를 메인 경로에서 실제 사용하는 예제를 보여준다.

---

## 2. Step 1-R 연구 요약

### 2-1. bluetape4k-leader API (검증됨)

| 항목 | 내용 |
|------|------|
| Core artifact | `io.github.bluetape4k.leader:bluetape4k-leader-core:0.1.0` |
| Redis artifact | `io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce:0.1.0` |
| Gradle cache | 두 모듈 모두 `~/.gradle/caches` 확인됨 |
| Group ID | `io.github.bluetape4k.leader` (main bluetape4k `io.github.bluetape4k` 와 **별도 버전 축**) |
| 핵심 인터페이스 | `LeaderElector` (sync), `SuspendLeaderElection` (코루틴) |
| Redis 구현체 | `LettuceLeaderElector(connection, options)` |
| 핵심 메서드 | `runIfLeader(lockName) { action() } : T?` — null = skipped |
| 결과 래퍼 | `runIfLeaderResult(lockName) { action() } : LeaderRunResult<T>` — Elected/Skipped/ActionFailed |
| 옵션 | `LeaderElectionOptions(waitTime, leaseTime)` |

> **버전 거버넌스 주의**: `bluetape4k-leader` 는 별도 groupId 및 버전 (`0.1.0`)을 사용.  
> `libs.versions.toml` 에 `bluetape4k-leader` 버전 키를 별도 추가 필요.  
> 아티팩트 출처: Maven Central 공개 배포 확인됨 (Gradle cache 상 `.pom` 존재).

### 2-2. Version Catalog 추가 항목

```toml
[versions]
# 기존 bluetape4k = "1.8.0-SNAPSHOT" 와 독립적
bluetape4k-leader = "0.1.0"

[libraries]
bluetape4k-leader-core = { module = "io.github.bluetape4k.leader:bluetape4k-leader-core", version.ref = "bluetape4k-leader" }
bluetape4k-leader-redis-lettuce = { module = "io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce", version.ref = "bluetape4k-leader" }
```

→ Kotlin DSL 접근자: `libs.bluetape4k.leader.core`, `libs.bluetape4k.leader.redis.lettuce`

### 2-3. 다이어그램 생성 방식

- SVG 직접 작성 (pastel infographic 스타일, Mermaid 렌더링 금지)
- `rsvg-convert -w 1200 diagram.svg -o diagram.png`
- 저장 경로: `docs/images/readme-diagrams/`
- README에 PNG 임베드, SVG 유지

### 2-4. 기존 Workshop 패턴

- Spring Boot 모듈: `alias(libs.plugins.kotlin.spring)` + `alias(libs.plugins.spring.boot)` + `springBoot { mainClass.set(...) }`
- `testImplementation.extendsFrom(compileOnly, runtimeOnly)`
- Testcontainers: `RedisServer.Launcher.redis` singleton → companion object `by lazy { }`
- 로깅: `companion object : KLogging()`
- 테스트: `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`, backtick 네이밍
- HTTP/Lettuce 연결: 반드시 `companion object by lazy { }` (anti-pattern: @BeforeEach 생성 금지)

---

## 3. 설계 리스크

### Risk 1: `runIfLeader` 결과 의미 모호성 해결 ✅
- `runIfLeader` 는 락 획득 실패 시 `null` 반환 (ShedLock 방식)
- Job 결과가 원래 `null`인 경우와 "skipped" 구분 불가
- **결정**: `LeaderGuardedJob.execute()` 반환 타입은 반드시 `Unit`. 이를 통해 `null` 은 항상 "skipped" 를 의미.
- 로깅으로 leader/skipped 구분: `log.info { "[LEADER] Job ${job.lockName} executed" }` vs `log.debug { "[SKIPPED] Job ${job.lockName} not acquired" }`

### Risk 2: `waitTime` 과 Testcontainers 시작 시간 충돌
- `RedisServer.Launcher.redis` 는 companion object lazy → 안전
- `LettuceLeaderElector` 는 연결 시점에 실제 TCP 연결 시도
- **해결**: `AbstractLeaderElectionTest.companion object` 에서 Redis 가 초기화된 후에 `LettuceLeaderElector` 생성

### Risk 3: 다중 인스턴스 동시 테스트 결정론적 보장
- 단순 스레드 발사로는 진짜 "동시성" 보장 어려움
- **해결**: `MultithreadingTester` (bluetape4k-junit5) 사용 — raw Thread/Executors/CyclicBarrier 금지

### Risk 4: Lease Expiry 중 Job 실행 시 Race Condition ⚠️
- Job 실행 시간이 `leaseTime` 을 초과하면 TTL 만료 후 다른 인스턴스가 락 획득 가능
- **완화**: 테스트 T5에서 명시적 검증 (짧은 leaseTime + 긴 job → 결과 관찰)
- Production 가이드: `leaseTime` 은 job 최대 실행 시간의 3배 이상 설정 권고

### Risk 5: Redis 연결 장애 시 동작
- 연결 실패 시 `LettuceLeaderElector.runIfLeader` 가 예외 발생 또는 hang 가능
- **완화**: T6 테스트에서 실제 동작 검증 후 문서화

---

## 4. 설계 접근 비교

### Approach A: 순수 standalone (Spring 없음)
- 단점: Spring Boot 패턴 시연 불가, AC 불충족
- **거부**

### Approach B: Spring Boot + @Scheduled + LettuceLeaderElector ✅ (선택)

```
@Scheduled(fixedDelay) → LeaderScheduledJobService → LettuceLeaderElector → Redis
```

- 실제 프로덕션 패턴 시연
- `bluetape4k-leader` API 직접 사용
- 각 Job 은 독립적 `lockName` 으로 독립 선출

### Approach C: Spring AOP 어노테이션 패턴
- `bluetape4k-leader-spring-boot` 0.1.0 안정성 불확실
- **거부**

---

## 5. 아키텍처

### 5-1. 모듈 파일 구조

```
leader/
└── leader-election/
    ├── build.gradle.kts
    └── src/
        ├── main/
        │   ├── kotlin/io/bluetape4k/workshop/leader/
        │   │   ├── LeaderElectionApp.kt              (Spring Boot main + @EnableScheduling)
        │   │   ├── config/
        │   │   │   ├── LeaderElectionConfig.kt       (Lettuce beans + LettuceLeaderElector bean)
        │   │   │   └── LeaderElectionProperties.kt   (@ConfigurationProperties("leader"))
        │   │   └── job/
        │   │       ├── LeaderGuardedJob.kt           (interface: lockName, execute(): Unit)
        │   │       ├── CacheWarmupJob.kt             (캐시 워밍업 시뮬레이션)
        │   │       ├── StaleWorkflowCleanupJob.kt    (stale cleanup 시뮬레이션)
        │   │       └── LeaderScheduledJobService.kt  (@Scheduled + LettuceLeaderElector)
        │   └── resources/
        │       ├── application.yml
        │       └── logback-spring.xml
        └── test/
            ├── kotlin/io/bluetape4k/workshop/leader/
            │   ├── AbstractLeaderElectionTest.kt     (Redis TC base + companion connection)
            │   ├── LeaderElectionSingleRunnerTest.kt (T1: 단일 실행)
            │   ├── ConcurrentLeaderElectionTest.kt  (T2: MultithreadingTester 사용)
            │   ├── LeaderElectionJobRecoveryTest.kt  (T3: 실패 후 재선출)
            │   ├── MultiJobIndependenceTest.kt       (T4: 두 Job lockName 독립성)
            │   ├── LeaseExpiryTest.kt                (T5: leaseTime 초과 시 동작)
            │   └── RedisFailureTest.kt               (T6: Redis 연결 장애 동작)
            └── resources/
                ├── junit-platform.properties
                └── logback-test.xml
```

### 5-2. 핵심 데이터 흐름

```
인스턴스 1 ──┐                                   ┌─ Job executes (leader)
인스턴스 2 ──┤──→ LettuceLeaderElector ──→ Redis ─┤
인스턴스 3 ──┘    SETNX(lockName, TTL)            └─ null (skipped) x 2
```

### 5-3. 설정 빈

```kotlin
// LeaderElectionProperties.kt
@ConfigurationProperties(prefix = "leader")
data class LeaderElectionProperties(
    val redis: RedisConfig = RedisConfig(),
    val waitTime: Duration = Duration.ofSeconds(2),
    val leaseTime: Duration = Duration.ofSeconds(30),
    val jobFixedDelay: String = "PT10S",
) : Serializable {
    data class RedisConfig(val url: String = "redis://localhost:6379") : Serializable

    init {
        leaseTime.toMillis().requireGe(waitTime.toMillis(), "leaseTime must be >= waitTime")
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
```

```kotlin
// LeaderElectionConfig.kt
@Configuration
@EnableConfigurationProperties(LeaderElectionProperties::class)
class LeaderElectionConfig {

    @Bean(destroyMethod = "shutdown")
    fun redisClient(props: LeaderElectionProperties): RedisClient =
        RedisClient.create(props.redis.url)

    @Bean(destroyMethod = "close")
    fun lettuceConnection(client: RedisClient): StatefulRedisConnection<String, String> =
        client.connect(StringCodec.UTF8)

    @Bean
    fun leaderElector(
        connection: StatefulRedisConnection<String, String>,
        props: LeaderElectionProperties,
    ): LeaderElector = LettuceLeaderElector(
        connection,
        // ⚠️ LeaderElectionOptions 은 kotlin.time.Duration 을 사용하므로
        // @ConfigurationProperties 에서 바인딩된 java.time.Duration 을 변환해야 한다.
        LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        ),
    )
}
```

### 5-4. LeaderGuardedJob 인터페이스

```kotlin
interface LeaderGuardedJob {
    /** 분산 락에 사용할 고유 이름. 구현체 init {} 에서 requireNotBlank 호출 필수. */
    val lockName: String

    /** 리더로 선출된 경우 단 1회 실행. 반환 타입은 항상 Unit. */
    fun execute()
}
```

구현체 예시:
```kotlin
// ⚠️ @Component 필수: 누락 시 List<LeaderGuardedJob> 이 빈 리스트로 주입되어 silent skip
@Component
class CacheWarmupJob : LeaderGuardedJob {
    // ⚠️ val 선언이 init {} 보다 반드시 먼저 와야 한다.
    // init {} 은 val 초기화 후 실행되므로, val 위에 init {} 을 두면 NPE 발생.
    override val lockName = "leader:cache-warmup"
    init { lockName.requireNotBlank("lockName") }  // val 초기화 완료 후 검증
    override fun execute() {
        log.info { "Cache warmup executed by leader" }
        // simulate warmup
    }
    companion object : KLogging()
}

@Component
class StaleWorkflowCleanupJob : LeaderGuardedJob {
    override val lockName = "leader:stale-workflow-cleanup"
    init { lockName.requireNotBlank("lockName") }
    override fun execute() {
        log.info { "Stale workflow cleanup executed by leader" }
    }
    companion object : KLogging()
}
```

> **`@Component` 누락 위험**: `List<LeaderGuardedJob>` 주입 시 Spring 은 `@Component` 가 없는 구현체를 무시한다.
> 주입된 리스트가 빈 리스트여도 **컴파일/런타임 에러 없이 silently skip** 됨. 반드시 붙여야 한다.

### 5-5. Job Service (예외 처리 + 로깅 포함)

```kotlin
@Service
class LeaderScheduledJobService(
    private val leaderElector: LeaderElector,
    private val jobs: List<LeaderGuardedJob>,
) {
    companion object : KLogging()

    init {
        // lockName 중복 감지: 중복 lockName 이 있으면 두 Job 이 동일 Redis 키를 공유하여 잘못된 skip 발생
        val lockNames = jobs.map { it.lockName }
        val duplicates = lockNames.groupBy { it }.filter { it.value.size > 1 }.keys
        check(duplicates.isEmpty()) {
            "Duplicate lockNames detected in LeaderGuardedJob list: $duplicates"
        }
        if (jobs.isEmpty()) {
            log.warn { "No LeaderGuardedJob beans registered. Scheduled leader election will do nothing." }
        }
    }

    @Scheduled(fixedDelayString = "\${leader.job-fixed-delay:PT10S}")
    fun runJobs() {
        jobs.forEach { job ->
            try {
                val result = leaderElector.runIfLeader(job.lockName) {
                    job.execute()
                }
                if (result != null) {
                    log.info { "[LEADER] Job '${job.lockName}' executed" }
                } else {
                    log.debug { "[SKIPPED] Job '${job.lockName}' — not elected" }
                }
            } catch (e: Exception) {
                log.error(e) { "[ERROR] Job '${job.lockName}' failed" }
                // 개별 Job 실패가 다른 Job 실행을 방해하지 않도록 계속 진행
            }
        }
    }
}
```

### 5-6. Application + @EnableScheduling

```kotlin
@SpringBootApplication
@EnableScheduling
class LeaderElectionApp

fun main(args: Array<String>) {
    runApplication<LeaderElectionApp>(*args)
}
```

---

## 6. 테스트 시나리오

### T0: Spring Boot Context Loading Test
```kotlin
@SpringBootTest
@TestPropertySource(properties = ["leader.redis.url=redis://localhost:\${redis.port}"])
class LeaderElectionContextTest(
    @Autowired val leaderElector: LeaderElector,
    @Autowired val jobService: LeaderScheduledJobService,
    @Autowired val jobs: List<LeaderGuardedJob>,
) {
    @Test
    fun `Spring Boot context loads successfully`() {
        leaderElector.shouldNotBeNull()
        jobService.shouldNotBeNull()
        jobs.shouldHaveSize(2)  // CacheWarmupJob + StaleWorkflowCleanupJob
    }
}
```
> **T0 목적**: `@Component` 누락, Duration 타입 불일치, Config 바인딩 오류를 컴파일 전에 runtime에서 조기 감지.
> AC "Spring Boot 패턴 시연" 을 달성하는 최소 통합 증거.

### T1: 단일 인스턴스 Job 실행
```kotlin
// 직접 LettuceLeaderElector 생성, lockName 으로 실행 → non-null 반환
val result = leaderElector.runIfLeader("test:t1") { "executed" }
result.shouldNotBeNull() shouldBeEqualTo "executed"
```

### T2: N=3 인스턴스 동시 실행 — 정확히 1개만 실행 (MultithreadingTester 사용)
```kotlin
// 결정론 보장 설정:
// - leaseTime = 5s (workers 중 1개가 선출 후 job 완료 전에 TTL 만료 안 되도록)
// - waitTime = 100ms (다른 2개가 너무 오래 블록하지 않도록)
// - attemptCount 로 실제 3개 모두 runIfLeader 시도했는지 검증
val executions = AtomicInteger(0)
val attemptCount = AtomicInteger(0)
val lockName = "test:t2:${UUID.randomUUID()}"  // 테스트 격리: UUID로 lockName 충돌 방지

val shortOptions = LeaderElectionOptions(
    waitTime = 100.milliseconds,
    leaseTime = 5.seconds,
)

MultithreadingTester()
    .workers(3)
    .rounds(1)
    .add {
        // 각 worker = 별도 LettuceLeaderElector (별도 connection) — singleton 공유 아님
        val elector = LettuceLeaderElector(newConnection(), shortOptions)
        attemptCount.incrementAndGet()
        elector.runIfLeader(lockName) { executions.incrementAndGet() }
    }
    .run()

attemptCount.get() shouldBeEqualTo 3   // 3개 모두 실제로 시도함을 검증
executions.get() shouldBeEqualTo 1     // 정확히 1개만 실행
```
> **`UUID.randomUUID()` lockName**: 테스트 간 Redis key 충돌 방지. 이전 테스트가 leaseTime 내에 종료되지 않아도 안전.

### T3: Leader Job 실패 후 락 해제 & 재선출
```kotlin
// 첫 번째 elector: job throw exception → lock released
// 두 번째 elector: 즉시 재선출 가능
assertFailsWith<IllegalStateException> {
    elector1.runIfLeader("test:t3") { throw IllegalStateException("fail") }
}
val recovered = elector2.runIfLeader("test:t3") { "recovered" }
recovered.shouldNotBeNull() shouldBeEqualTo "recovered"
```

### T4: 두 Job 독립 lockName 동작 (동시에 둘 다 실행 가능)
```kotlin
// Job A lockName="job:a", Job B lockName="job:b"
// 단일 인스턴스 → 둘 다 실행되어야 함
// 두 인스턴스 → 각 lockName 마다 별도 경쟁
```

### T5: Lease Expiry — leaseTime 초과 시 동작 관찰
```kotlin
// leaseTime = 200ms, job sleep = 500ms
// 실행 완료 후 두 번째 elector 선출 시도 → 성공해야 함
// (또는 leaseTime 내 job 완료가 기대라면 timeout assertion)
```

### T6: Redis 연결 장애 시 동작 (graceful degradation)
```kotlin
// Testcontainers Redis stop() 후 runIfLeader 시도
// 기대: 예외 전파 또는 skip — silent hang 아님
// log.warn 이 실제로 출력되는지 확인
```
> **⚠️ T5/T6 분류**: 이 두 테스트는 `bluetape4k-leader` 라이브러리의 계약 동작을 탐색하는
> **educational smoke test** 다. 타이밍 의존성으로 인해 CI 환경에서 flaky 가능.
> `@Tag("smoke")` 또는 `@Disabled("Educational smoke — library contract")` 로 표기하고
> 실패 시 nightly 블록하지 않도록 한다.

### T7: 실행 완료 후 Lock 즉시 해제 확인 (lock duration 측정)
```kotlin
// LettuceLeaderElector.runImpl 은 action 완료 시 finally { lock.unlock } 로 즉시 해제함.
// 이를 검증: leaseTime(5s)보다 훨씬 짧은 시간 내에 같은 lockName 재획득이 가능해야 한다.
// 1. elector1: runIfLeader("test:t7") { /* no-op */ } — action 완료 후 즉시 unlock
// 2. elector2: runIfLeader("test:t7") { "reacquired" } — 즉시 재획득 가능해야 함
// 3. result shouldNotBeNull (leaseTime 만료 기다림 없이 성공)
val lockName = "test:t7:${UUID.randomUUID()}"
val longLeaseOptions = LeaderElectionOptions(
    waitTime = 100.milliseconds,
    leaseTime = 30.seconds,  // 긴 leaseTime — 이것이 만료되길 기다리지 않아야 함
)
// elector1 실행 + 완료
elector1.runIfLeader(lockName) { /* completes immediately */ }

// elector2 즉시 재획득 시도 — finally unlock 덕분에 leaseTime(30s) 기다림 없이 성공
val result = elector2.runIfLeader(lockName) { "reacquired" }
result.shouldNotBeNull() shouldBeEqualTo "reacquired"  // 즉시 재획득
```
> **T7 결정 근거**: `LettuceLeaderElector.runImpl` 의 `finally { lock.unlock() }` 계약 검증.  
> `connection.close()` 는 Redis key 를 삭제하지 **않는다** (이전 spec의 잘못된 가정 수정됨).  
> Lock 해제는 오직 `runIfLeader` 완료 시 `runImpl.finally` 에서만 발생.  
> Rolling restart 시 이전 인스턴스의 Job 이 완료된 후에는 즉시 새 인스턴스가 선출될 수 있음을 보장.

---

## 7. Concurrency Testing 기준

**규칙**: `MultithreadingTester` (bluetape4k-junit5) 사용. Raw `Thread`, `Executors.newFixedThreadPool`, `CyclicBarrier`, `CountDownLatch` 를 테스트 동시성 검증에 직접 사용 **금지**.

단, `AtomicInteger` 카운터는 assertion target 으로 허용.

---

## 8. 의존성 목록

### build.gradle.kts (전체)

```kotlin
plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get())
}

dependencies {
    // Leader Election
    implementation(libs.bluetape4k.leader.core)
    implementation(libs.bluetape4k.leader.redis.lettuce)
    implementation(libs.lettuce.core)

    // Logging
    implementation(libs.bluetape4k.logging)

    // Spring Boot
    implementation(libs.spring.boot.starter)
    annotationProcessor(libs.spring.boot.configuration.processor)

    // Test
    testImplementation(project(":shared"))
    testImplementation(libs.bluetape4k.junit5)
    testImplementation(libs.bluetape4k.testcontainers)
    testImplementation(libs.bluetape4k.assertions)
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "junit", module = "junit")
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(module = "mockito-core")
    }
}
```

### libs.versions.toml 추가

```toml
[versions]
bluetape4k-leader = "0.1.0"  # 별도 repo: io.github.bluetape4k.leader

[libraries]
bluetape4k-leader-core = { module = "io.github.bluetape4k.leader:bluetape4k-leader-core", version.ref = "bluetape4k-leader" }
bluetape4k-leader-redis-lettuce = { module = "io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce", version.ref = "bluetape4k-leader" }
```

---

## 9. README & 다이어그램 계획

### README 구조

1. Overview
2. Architecture Diagram (PNG)
3. Used Bluetape4k Features (표)
4. Key Patterns
5. Class Diagram: LeaderElector 계층 (PNG)
6. Sequence Diagram: 3 instances → 1 leader (PNG)
7. Running / Tests

### 다이어그램 목록

| 파일 | 종류 | 내용 |
|------|------|------|
| `architecture-overview.{png,svg}` | Architecture | Spring Boot 앱 → LettuceLeaderElector → Redis |
| `leader-class-diagram.{png,svg}` | Class | LeaderElector ← LettuceLeaderElector, LeaderGuardedJob impl |
| `leader-election-sequence.{png,svg}` | Sequence | 3 instances 동시 선출 흐름 |

생성: `rsvg-convert -w 1200 diagram.svg -o diagram.png`  
저장: `docs/images/readme-diagrams/`

---

## 10. CI 등록 계획

- `settings.gradle.kts`: `includeModules("leader", false, true)` 추가
- Gradle 모듈명: `leader-leader-election`
- CI (`ci.yml`): 기존 compile-only build — 별도 변경 불필요
- Nightly (`nightly.yml`): **실제 확인 완료** — `./gradlew test --continue --max-workers=1` 로 실행.  
  `settings.gradle.kts` 에 모듈 추가 시 자동으로 nightly 에 포함됨. 별도 workflow 수정 불필요.
- T5/T6 는 `@Tag("smoke")` 로 표기. nightly 에서 smoke tag 제외 실행 옵션 고려.

### CI 검증 명령 (구현 후 실행)
```bash
rg "leader" .github/workflows/nightly.yml   # 현재 없어야 정상 (자동 포함 방식)
./gradlew :leader-leader-election:test --dry-run   # 모듈 인식 확인
```

---

## Appendix: 리뷰 이력

| Round | Reviewer | P0 | P1 | P2 | P3 | 상태 |
|-------|----------|----|----|----|----|----|
| Round 1 | Architect | 1 | 4 | 3 | 1 | 반영됨 |
| Round 1 | Silent Failure + Type + Test | 4 | 4 | 3 | 1 | 반영됨 |
| Round 1 통합 | | 5 | 8 | 6 | 2 | **반영 완료** |
| Round 2 | Architect (Perspective A) | 1 | 2 | 4 | 1 | 반영됨 |
| Round 2 | Silent Failure Hunter (B) | 3 | 5 | 3 | 1 | 반영됨 (workshop scope) |
| Round 2 | Test Coverage (C) | 2 | 6 | 6 | 3 | 반영됨 (scope-out 포함) |
| Round 2 | Type Design (D) | 1 | 5 | 4 | 3 | 반영됨 (scope-out 포함) |
| Round 2 | 6-Tier Advisor | 2 | 2 | 3 | 10 | 반영됨 |
| Round 2 통합 raw | | 7 | 18 | - | - | — |
| Round 2 통합 (workshop scope 적용 후) | | 0 | 0 | - | - | **반영 완료** |

### Round 2 반영 항목 요약
1. `LeaderElectionOptions(waitTime = props.waitTime.toKotlinDuration(), ...)` — Duration 타입 변환 추가 (§5-3)
2. `@Component` on CacheWarmupJob/StaleWorkflowCleanupJob — silent empty list 방지 (§5-4)
3. T0 Spring Boot context loading test 추가 — AC "Spring Boot 패턴 시연" 검증 (§6)
4. `logback.xml` → `logback-spring.xml` — workshop 규약 (§5-1)
5. T7 재작성 — connection.close() 가정 제거, runImpl.finally unlock 계약 검증으로 변경 (§6)
6. `lockName uniqueness check` in LeaderScheduledJobService.init{} — 중복 lockName silent bug 방지 (§5-5)
7. T2 결정론 보강 — `attemptCount`, UUID lockName, leaseTime=5s, waitTime=100ms (§6)

### Round 2 Scope-out 항목
- RedisConfig.url scheme validation (production hardening — workshop scope 외)
- T6 timeout hardening / redisClient.defaultTimeout (workshop smoke test로 충분)
- `jobFixedDelay` Duration.parse validation (Spring binding 시 자동 검증)
- `runIfLeaderResult` primary API 교체 (educational 목적으로 `runIfLeader` 유지)
- `leaseTime.toMillis()` overflow 위험 → Duration 직접 비교 (implementation 단계에서 적용)
