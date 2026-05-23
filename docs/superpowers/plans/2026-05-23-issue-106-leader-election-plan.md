# Plan: Issue #106 — Leader Election Scheduled Job

**날짜**: 2026-05-23
**브랜치**: feat/issue-106-leader-election
**Spec**: docs/superpowers/specs/2026-05-23-issue-106-leader-election-design.md
**모듈**: `leader/leader-election` → Gradle 모듈명: `leader-leader-election`

## Task List

### Phase 1: Project Scaffolding

#### P1-1: settings.gradle.kts 에 leader 도메인 등록
- **complexity**: low
- **file(s)**:
  - `settings.gradle.kts`
- **what**:
  - 기존 `includeModules(...)` 호출 블록에 `includeModules("leader", false, true)` 한 줄 추가.
  - 알파벳 순서를 고려해 적절한 위치에 삽입.
  - Gradle 모듈명은 `leader-leader-election` 으로 자동 등록됨을 주석으로 명시.

#### P1-2: gradle/libs.versions.toml 에 bluetape4k-leader 의존성 추가
- **complexity**: low
- **file(s)**:
  - `gradle/libs.versions.toml`
- **what**:
  - `[versions]` 섹션에 `bluetape4k-leader = "0.1.0"` 추가.
    - 기존 `bluetape4k = "1.8.0-SNAPSHOT"` 와 **독립적** 인 버전 축 (groupId `io.github.bluetape4k.leader`).
  - `[libraries]` 섹션에 다음 두 항목 추가:
    - `bluetape4k-leader-core = { module = "io.github.bluetape4k.leader:bluetape4k-leader-core", version.ref = "bluetape4k-leader" }`
    - `bluetape4k-leader-redis-lettuce = { module = "io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce", version.ref = "bluetape4k-leader" }`
  - Kotlin DSL 접근자: `libs.bluetape4k.leader.core`, `libs.bluetape4k.leader.redis.lettuce`.

#### P1-3: 모듈 build.gradle.kts 작성
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/build.gradle.kts`
- **what**:
  - `plugins { alias(libs.plugins.kotlin.spring); alias(libs.plugins.spring.boot) }` 선언.
  - `springBoot { mainClass.set("io.bluetape4k.workshop.leader.LeaderElectionAppKt") }` 설정.
  - `configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }` 추가.
  - Spec §8 의 의존성 목록 반영:
    - `implementation(libs.bluetape4k.leader.core)`, `implementation(libs.bluetape4k.leader.redis.lettuce)`, `implementation(libs.lettuce.core)`
    - `implementation(libs.bluetape4k.logging)`
    - `implementation(libs.spring.boot.autoconfigure.lib)` + `implementation(libs.spring.boot.starter.actuator)` (sibling 모듈 패턴과 동일; bare `spring-boot-starter` 키는 카탈로그에 없음)
    - `annotationProcessor(libs.spring.boot.autoconfigure.processor)` + `annotationProcessor(libs.spring.boot.configuration.processor)`
    - 테스트: `testImplementation(project(":shared"))`, `libs.bluetape4k.junit5`, `libs.bluetape4k.testcontainers`, `libs.bluetape4k.assertions`
    - `libs.spring.boot.starter.test` (junit/vintage/mockito-core 제외)

#### P1-4: 패키지 디렉토리 + LeaderElectionApp + LeaderElectionProperties 생성
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/LeaderElectionApp.kt`
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/config/LeaderElectionProperties.kt`
- **what**:
  - `LeaderElectionApp.kt`:
    - `@SpringBootApplication` + `@EnableScheduling` 부착.
    - top-level `fun main(args: Array<String>)` 에서 `runApplication<LeaderElectionApp>(*args)` 호출.
    - 패키지 `io.bluetape4k.workshop.leader`.
  - `LeaderElectionProperties.kt` (패키지 `io.bluetape4k.workshop.leader.config`):
    - `@ConfigurationProperties(prefix = "leader")` 를 가진 `data class` 선언.
    - 필드: `redis: RedisConfig = RedisConfig()`, `waitTime: java.time.Duration = Duration.ofSeconds(2)`,
      `leaseTime: java.time.Duration = Duration.ofSeconds(30)`, `jobFixedDelay: String = "PT10S"`.
    - 중첩 `data class RedisConfig(val url: String = "redis://localhost:6379")`.
    - `Serializable` 구현 + `companion object { private const val serialVersionUID = 1L }`.
    - `init {}` 에서 `leaseTime.toMillis().requireGe(waitTime.toMillis(), "leaseTime must be >= waitTime")` 호출
      (bluetape4k `require*` 확장 함수 사용).

---

### Phase 2: Core Implementation

#### P2-1: LeaderElectionConfig — RedisClient + StatefulRedisConnection + LeaderElector beans
- **complexity**: high
- **file(s)**:
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/config/LeaderElectionConfig.kt`
- **what**:
  - `@Configuration` + `@EnableConfigurationProperties(LeaderElectionProperties::class)` 부착.
  - `@Bean(destroyMethod = "shutdown") fun redisClient(props): RedisClient = RedisClient.create(props.redis.url)`.
  - `@Bean(destroyMethod = "close") fun lettuceConnection(client): StatefulRedisConnection<String, String> = client.connect(StringCodec.UTF8)`.
  - `@Bean fun leaderElector(connection, props): LeaderElector = LettuceLeaderElector(connection, LeaderElectionOptions(...))`.
  - **⚠️ CRITICAL**: `LeaderElectionOptions` 는 `kotlin.time.Duration` 을 받으므로
    반드시 `props.waitTime.toKotlinDuration()`, `props.leaseTime.toKotlinDuration()` 으로 변환.
    `java.time.Duration` 직접 전달 시 **컴파일 에러** (Spec §5-3 P0 finding).
  - `import kotlin.time.toKotlinDuration` 명시.

#### P2-2: LeaderGuardedJob 인터페이스
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/job/LeaderGuardedJob.kt`
- **what**:
  - `interface LeaderGuardedJob` 선언.
  - `val lockName: String` — 분산 락 키. KDoc: 구현체 init{} 에서 `requireNotBlank` 호출 필수 명시.
  - `fun execute()` — 반환 타입은 항상 `Unit`.
    (Risk 1 해결: null 은 항상 "skipped" 를 의미하므로 반환 타입 혼동 없음.)
  - English KDoc: 한 줄 요약 + `## Behavior / Contract` 섹션.

#### P2-3: CacheWarmupJob + StaleWorkflowCleanupJob 구현
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/job/CacheWarmupJob.kt`
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/job/StaleWorkflowCleanupJob.kt`
- **what**:
  - **⚠️ `@Component` 어노테이션 필수**: 누락 시 `List<LeaderGuardedJob>` 빈 리스트 주입 → silent skip (Spec §5-4).
  - `CacheWarmupJob`: `override val lockName = "leader:cache-warmup"`.
  - `StaleWorkflowCleanupJob`: `override val lockName = "leader:stale-workflow-cleanup"`.
  - **선언 순서 강제**: `val lockName` 선언 → `init { lockName.requireNotBlank("lockName") }` 순서.
    역순이면 init{} 에서 lockName 이 아직 초기화 안 됨 → NPE (Spec §5-4).
  - `execute()`: `log.info { "..." }` 로깅 + simulate (단순 로그 또는 50ms delay).
  - `companion object : KLogging()` 패턴 사용.

#### P2-4: LeaderScheduledJobService — @Scheduled 디스패처
- **complexity**: high
- **file(s)**:
  - `leader/leader-election/src/main/kotlin/io/bluetape4k/workshop/leader/job/LeaderScheduledJobService.kt`
- **what**:
  - `@Service` 부착. 생성자 주입: `private val leaderElector: LeaderElector, private val jobs: List<LeaderGuardedJob>`.
  - `init {}` 블록:
    - **lockName 중복 감지**: `jobs.map { it.lockName }.groupBy { it }.filter { it.value.size > 1 }.keys`
      → `check(duplicates.isEmpty()) { "Duplicate lockNames: $duplicates" }`.
    - `jobs.isEmpty()` 인 경우 `log.warn { "No LeaderGuardedJob beans registered..." }` 출력.
  - `@Scheduled(fixedDelayString = "\${leader.job-fixed-delay:PT10S}") fun runJobs()`:
    - `jobs.forEach { job -> try { ... } catch (e: Exception) { log.error(e) { ... } } }`.
    - 개별 try/catch: 한 Job 실패가 다른 Job 실행을 차단하지 않음.
    - `result != null` → `log.info { "[LEADER] Job '${job.lockName}' executed" }`.
    - `null` → `log.debug { "[SKIPPED] Job '${job.lockName}' — not elected" }`.
  - `companion object : KLogging()` 패턴 사용.

#### P2-5: application.yml + logback-spring.xml
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/main/resources/application.yml`
  - `leader/leader-election/src/main/resources/logback-spring.xml`
- **what**:
  - `application.yml`:
    - `spring.application.name: leader-election`
    - `leader.redis.url: redis://localhost:6379`
    - `leader.wait-time: 2s`, `leader.lease-time: 30s`, `leader.job-fixed-delay: PT10S`
    - 로깅: `io.bluetape4k.workshop.leader: INFO`, `io.bluetape4k.leader: DEBUG`
  - `logback-spring.xml` (**`logback.xml` 아님** — workshop 규약, Spec §5-1):
    - Spring profile-aware 콘솔 appender.
    - `io.bluetape4k.workshop.leader` 패키지 INFO.

---

### Phase 3: Tests

#### P3-1: 테스트 리소스 — junit-platform.properties + logback-test.xml
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/test/resources/junit-platform.properties`
  - `leader/leader-election/src/test/resources/logback-test.xml`
- **what**:
  - `junit-platform.properties`: 다른 워크샵 모듈 패턴 복사 후 조정.
    - `junit.jupiter.execution.parallel.enabled=false` (직렬 실행 — Redis 충돌 방지).
    - `junit.jupiter.testinstance.lifecycle.default=per_class`.
    - **`@Tag("smoke")` 제외**: `junit.jupiter.execution.exclude.tags=smoke` 추가
      (T5 LeaseExpiryTest, T6 RedisFailureTest 는 기본 `test` task 에서 제외; `@Tag("smoke")` 테스트는 별도 Gradle task 또는 Nightly 에서만 실행).
  - `logback-test.xml`: 콘솔 appender.
    - `io.bluetape4k.workshop.leader=DEBUG`, `io.bluetape4k.leader=TRACE`, root `INFO`.

#### P3-2: AbstractLeaderElectionTest — Redis Testcontainers base
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/AbstractLeaderElectionTest.kt`
- **what**:
  - `abstract class AbstractLeaderElectionTest`, `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 부착.
  - `companion object : KLogging() { val redis = RedisServer.Launcher.redis; val redisUrl: String get() = redis.url }`.
  - **`@Testcontainers` 사용 금지** — bluetape4k Launcher 패턴이 처리함.
  - `protected fun newConnection(): StatefulRedisConnection<String, String>` 헬퍼.
  - `protected fun newElector(options: LeaderElectionOptions = defaultOptions): LettuceLeaderElector` 헬퍼.
  - `protected val defaultOptions = LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 5.seconds)`.

#### P3-3: LeaderElectionContextTest (T0) — Spring Boot context loading
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/LeaderElectionContextTest.kt`
- **what**:
  - `@SpringBootTest` + **`@DynamicPropertySource` companion static method** 로 Testcontainers Redis URL 주입.
    - `@TestPropertySource(properties = ["...${redis.port}"])` 패턴 금지 — placeholder 해석 안 됨.
    - 올바른 패턴:
      ```kotlin
      companion object : KLogging() {
          val redis = RedisServer.Launcher.redis
          @JvmStatic
          @DynamicPropertySource
          fun registerProperties(registry: DynamicPropertyRegistry) {
              registry.add("leader.redis.url") { redis.url }
          }
      }
      ```
  - 생성자 주입: `@Autowired val leaderElector: LeaderElector`, `@Autowired val jobService: LeaderScheduledJobService`, `@Autowired val jobs: List<LeaderGuardedJob>`.
  - 테스트: `\`Spring Boot context loads with all leader beans\``:
    - `leaderElector.shouldNotBeNull()`, `jobService.shouldNotBeNull()`, `jobs.shouldHaveSize(2)`.
  - 목적: `@Component` 누락 / Duration 타입 불일치 / Config 바인딩 오류를 런타임 조기 감지.

#### P3-4: LeaderElectionSingleRunnerTest (T1) — 단일 인스턴스
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/LeaderElectionSingleRunnerTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속 (Spring 컨텍스트 불필요).
  - `\`single instance acquires leadership and executes action\``:
    - `val result = newElector().runIfLeader("test:t1:\${UUID.randomUUID()}") { "executed" }`.
    - `result.shouldNotBeNull() shouldBeEqualTo "executed"`.
  - `bluetape4k-assertions` 매처 사용.

#### P3-5: ConcurrentLeaderElectionTest (T2) — MultithreadingTester N=3
- **complexity**: high
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/ConcurrentLeaderElectionTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속.
  - **`MultithreadingTester` (bluetape4k-junit5) 사용 필수** — raw Thread/Executors/CyclicBarrier 금지 (Spec §7).
  - 결정론적 옵션: `waitTime = 100.milliseconds, leaseTime = 5.seconds`.
  - **UUID 접미사 lockName**: `"test:t2:\${UUID.randomUUID()}"` — 테스트 격리.
  - 카운터: `val executions = AtomicInteger(0)`, `val attemptCount = AtomicInteger(0)`
    (`java.util.concurrent.atomic.AtomicInteger` — local 변수 → atomicfu 아님).
  - 각 worker: `newConnection()` 으로 **별도 connection** + 별도 `LettuceLeaderElector` (singleton 공유 금지).
  - Assertion 2종:
    - `attemptCount.get() shouldBeEqualTo 3` — 3개 모두 시도.
    - `executions.get() shouldBeEqualTo 1` — 정확히 1개만 실행.

#### P3-6: LeaderElectionJobRecoveryTest (T3) — 실패 후 재선출
- **complexity**: high
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/LeaderElectionJobRecoveryTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속.
  - `val lockName = "test:t3:\${UUID.randomUUID()}"`, `elector1 = newElector()`, `elector2 = newElector()`.
  - `assertFailsWith<IllegalStateException> { elector1.runIfLeader(lockName) { throw IllegalStateException("fail") } }`.
    (`assertFailsWith<T>` 사용 — `assertThrows`/`invoking shouldThrow` 금지).
  - `val recovered = elector2.runIfLeader(lockName) { "recovered" }`.
  - `recovered.shouldNotBeNull() shouldBeEqualTo "recovered"`.
  - 검증 포인트: `runImpl.finally { unlock }` 가 예외 경로에서도 동작.

#### P3-7: MultiJobIndependenceTest (T4) — 두 Job 독립 lockName
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/MultiJobIndependenceTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속.
  - `val lockA = "test:t4:job-a:\${UUID.randomUUID()}"`, `val lockB = "test:t4:job-b:\${UUID.randomUUID()}"`.
  - 단일 elector 로 두 lockName 모두 `runIfLeader` 호출 → 둘 다 non-null 결과.
  - `a shouldBeEqualTo "A"`, `b shouldBeEqualTo "B"`.

#### P3-8: LeaseExpiryTest (T5) — @Tag("smoke")
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/LeaseExpiryTest.kt`
- **what**:
  - **`@Tag("smoke")` 부착 필수** — 타이밍 의존성으로 CI flaky 가능.
  - `LeaderElectionOptions(waitTime = 50.milliseconds, leaseTime = 200.milliseconds)`.
  - 첫 elector: `runIfLeader(lockName) { Thread.sleep(500) }` — leaseTime(200ms) 초과.
  - 작업 완료 후 두 번째 elector 선출 시도 → 라이브러리 계약 동작 관찰 + 로그 출력.
  - KDoc 에 "educational smoke test" 명시.

#### P3-9: RedisFailureTest (T6) — @Tag("smoke")
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/RedisFailureTest.kt`
- **what**:
  - **`@Tag("smoke")` 부착 필수**.
  - 별도 Testcontainers Redis 컨테이너 생성 (singleton 공유 금지 — `stop()` 호출 시 다른 테스트 영향).
    - `GenericContainer("redis:7-alpine").withExposedPorts(6379).apply { start() }` 로 직접 생성.
  - 시나리오: 정상 elector 생성 → Redis stop → `runIfLeader` 호출 시 동작 관찰.
  - 예외 전파 또는 timeout — silent hang 이면 안 됨.
  - 검증 방법 (확정): `assertTimeoutPreemptively(Duration.ofSeconds(10)) { assertFailsWith<Exception> { elector.runIfLeader(lockName) { } } }`
    - outer `assertTimeoutPreemptively`: silent hang 방지 (library 가 끝없이 기다리는 경우 대비).
    - inner `assertFailsWith<Exception>`: 연결 실패 예외 전파 검증.
  - KDoc 에 "educational smoke — library contract" 명시.

#### P3-10: LockReleaseTest (T7) — 즉시 재획득
- **complexity**: high
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/LockReleaseTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속.
  - 목적: `runImpl.finally { lock.unlock() }` 계약 검증.
  - `LeaderElectionOptions(waitTime = 100.milliseconds, leaseTime = 30.seconds)` — 긴 leaseTime.
  - `val lockName = "test:t7:\${UUID.randomUUID()}"`.
  - `elector1.runIfLeader(lockName) { /* no-op */ }` → `finally unlock` 즉시 실행.
  - `val result = elector2.runIfLeader(lockName) { "reacquired" }` → 즉시 재획득.
  - `result.shouldNotBeNull() shouldBeEqualTo "reacquired"` — leaseTime(30s) 만료 대기 없이 성공.
  - **`connection.close()` 호출 금지** — Redis key 삭제 안 됨 (잘못된 가정, Spec §6 T7 수정).

#### P3-11: DuplicateLockNameTest — lockName 중복 감지 단위 테스트
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/DuplicateLockNameTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속 불필요 — Redis 컨테이너 없이 순수 단위 테스트.
  - `val elector = mockk<LeaderElector>()` (MockK 사용).
  - `val job1 = object : LeaderGuardedJob { override val lockName = "leader:cache-warmup"; override fun execute() {} }`
    `val job2 = object : LeaderGuardedJob { override val lockName = "leader:cache-warmup"; override fun execute() {} }` (동일 lockName)
  - `assertFailsWith<IllegalStateException> { LeaderScheduledJobService(elector, listOf(job1, job2)) }`.
  - KDoc: "Validates duplicate lockName detection in LeaderScheduledJobService init{}".

#### P3-12: JobIsolationTest — 첫 번째 Job 실패 시 나머지 Job 계속 실행
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/JobIsolationTest.kt`
- **what**:
  - `AbstractLeaderElectionTest` 상속.
  - `val failingJob = object : LeaderGuardedJob { override val lockName = "test:isolate:fail"; override fun execute() { throw RuntimeException("intentional") } }`
  - `val successJob = object : LeaderGuardedJob { override val lockName = "test:isolate:success"; override fun execute() { successCount.incrementAndGet() } }`
  - `val successCount = AtomicInteger(0)` (java.util.concurrent.atomic — local 변수 규칙).
  - `runJobs()` 직접 호출 (reflection 불필요 — `@Scheduled` 는 테스트에서 비활성).
  - `successCount.get() shouldBeEqualTo 1` — failingJob 예외가 successJob 실행을 차단하지 않음.
  - MockK 로 `LeaderElector.runIfLeader` stub: 두 lockName 모두 실제 실행 (null 아님).

#### P3-13: PropertiesValidationTest — LeaderElectionProperties init{} 검증
- **complexity**: low
- **file(s)**:
  - `leader/leader-election/src/test/kotlin/io/bluetape4k/workshop/leader/PropertiesValidationTest.kt`
- **what**:
  - Spring 컨텍스트 불필요 — 순수 단위 테스트.
  - `\`leaseTime less than waitTime throws IllegalArgumentException\``:
    ```kotlin
    assertFailsWith<IllegalArgumentException> {
        LeaderElectionProperties(
            waitTime = Duration.ofSeconds(30),
            leaseTime = Duration.ofSeconds(10),  // leaseTime < waitTime — invalid
        )
    }
    ```
  - `\`valid properties construction succeeds\``:
    ```kotlin
    val props = LeaderElectionProperties(waitTime = Duration.ofSeconds(2), leaseTime = Duration.ofSeconds(30))
    props.waitTime.toSeconds() shouldBeEqualTo 2L
    props.leaseTime.toSeconds() shouldBeEqualTo 30L
    ```

---

### Phase 4: README + Diagrams

#### P4-1: SVG 다이어그램 3종 작성
- **complexity**: high
- **file(s)**:
  - `docs/images/readme-diagrams/architecture-overview.svg`
  - `docs/images/readme-diagrams/leader-class-diagram.svg`
  - `docs/images/readme-diagrams/leader-election-sequence.svg`
- **what**:
  - SVG 직접 작성 (Mermaid 렌더링 금지 — Spec §2-3). Pastel infographic 스타일.
  - `architecture-overview.svg`: 다중 인스턴스 → LettuceLeaderElector → Redis 단일 키.
    `@Scheduled` 트리거, `LeaderScheduledJobService`, Job 실행 흐름 포함.
  - `leader-class-diagram.svg`: `LeaderElector` ← `LettuceLeaderElector`;
    `LeaderGuardedJob` ← `CacheWarmupJob`/`StaleWorkflowCleanupJob`;
    `LeaderScheduledJobService` 의존 관계.
  - `leader-election-sequence.svg`: 3개 인스턴스 동시 `SET NX EX` 경쟁 →
    1개 leader 선출 → `execute()` 수행 → `finally unlock`.

#### P4-2: PNG 생성 (rsvg-convert)
- **complexity**: low
- **file(s)**:
  - `docs/images/readme-diagrams/architecture-overview.png`
  - `docs/images/readme-diagrams/leader-class-diagram.png`
  - `docs/images/readme-diagrams/leader-election-sequence.png`
- **what**:
  - SVG 3종 각각에 대해 `rsvg-convert -w 1200 {name}.svg -o {name}.png` 실행.
  - SVG 와 PNG 모두 커밋.

#### P4-3: README.md + README.ko.md 작성
- **complexity**: medium
- **file(s)**:
  - `leader/leader-election/README.md`
  - `leader/leader-election/README.ko.md`
- **what**:
  - `README.md` (English):
    - Sections: Overview → Architecture (PNG embed) → Used Bluetape4k Features (table) →
      Key Patterns → Class Diagram (PNG) → Sequence Diagram (PNG) → Running / Tests.
    - PNG 경로: `../../docs/images/readme-diagrams/{name}.png` 상대 경로.
    - Used Bluetape4k Features 표: leader-core, leader-redis-lettuce, logging, junit5, testcontainers, assertions.
    - Running: `./gradlew :leader-leader-election:bootRun`, `./gradlew :leader-leader-election:test`.
  - `README.ko.md` (Korean — workspace 다국어 정책):
    - 동일 구조, 한국어 번역. PNG 임베드 동일.

---

### Phase 5: Final Verification

#### P5-1: 모듈 테스트 실행
- **complexity**: low
- **file(s)**: 없음 (검증 단계)
- **what**:
  - `./gradlew :leader-leader-election:test` 실행.
  - T0~T7 모두 통과 확인 (T5/T6 `@Tag("smoke")` 포함).
  - 실패 시 해당 작업으로 복귀 후 수정.
  - 실행 시간 + 통과 테스트 수 기록.

#### P5-2: detekt 정적 분석
- **complexity**: low
- **file(s)**: 없음 (검증 단계)
- **what**:
  - `./gradlew :leader-leader-election:detekt` 실행.
  - lint 이슈 0건 확인. 발생 시 코드 수정 후 재실행.
  - IDE diagnostics: `@Deprecated` 미해결 0건, import error 0건.

---

## Notes & References

- Spec: `docs/superpowers/specs/2026-05-23-issue-106-leader-election-design.md`
- 기존 패턴 참조 모듈:
  - `ratelimit/bucket4j-redis/build.gradle.kts` — Spring Boot + Redis + Lettuce 빌드 구조
  - `exposed/mvc-jdbc/` — Spring Boot 메인 앱 + 테스트 리소스 패턴
- 라이브러리 버전: `bluetape4k-leader` (`io.github.bluetape4k.leader`) 는 `io.github.bluetape4k` 와 **별도 groupId/버전 축**.
- 동시성 테스트: `MultithreadingTester` 만 허용. `Thread`/`Executors`/`CyclicBarrier`/`CountDownLatch` 직접 사용 금지 (Spec §7).
- 예외 검증: `assertFailsWith<T> { }` 만 사용. `assertThrows`/`invoking shouldThrow`/`kotlin.test.assertFailsWith` 금지.
- `@Tag("smoke")` 테스트 (T5, T6): educational — 실패 시 nightly 블록되지 않도록 분류.
- Duration 변환 주의: `@ConfigurationProperties` 는 `java.time.Duration` 바인딩. `LeaderElectionOptions` 는 `kotlin.time.Duration`. → `toKotlinDuration()` 변환 필수 (P2-1 CRITICAL).
