# leader/leader-zookeeper 워크숍 모듈 구현 플랜

> 작성일: 2026-05-25  
> 작업 브랜치: feat/leader-zookeeper  
> 관련 이슈: #10 (bluetape4k-leader 예제 제작 Epic) — PR-B  
> 관련 스펙: `docs/superpowers/specs/2026-05-25-leader-zookeeper-design.md`

---

## 1. 목표 및 범위

Apache Curator 기반 `bluetape4k-leader-zookeeper:0.2.1` 라이브러리를 워크숍에 도입하는
신규 모듈 `leader/leader-zookeeper/`를 처음부터 구현한다. 기존 `leader/leader-election/`
(Redis/Lettuce) 모듈의 Spring Boot 4 컨벤션을 따르되, ZooKeeper 고유의 R16 계약
(세션 기반 ephemeral node, TTL 없음)을 명시적으로 시연한다.

### 산출물

1. `gradle/libs.versions.toml` — `bluetape4k-leader-zookeeper` alias 1개 추가 (버전 핀 없음, BOM 관리)
2. 신규 모듈 디렉터리 `leader/leader-zookeeper/` (settings.gradle.kts는 `includeModules("leader", ...)` 헬퍼가 자동 등록 — 별도 수정 불필요)
3. `build.gradle.kts` + main/test 소스 + 리소스 + 양 언어 README
4. 10개 테스트 클래스 (T0~T9) — DoD 통과 기준

### 비-목표

- `ListeningLeaderElector` 래퍼는 ZK 모듈에 포함하지 않음 (라이브러리 미제공). README에서 cross-link만 제공.
- 새 라이브러리 버전 배포는 본 작업 범위 외 (BOM 1.1.3 사용).
- T5 `LeaseExpiryTest` / T6 `RedisFailureTest`류 smoke 태그는 ZK 모듈에서 사용하지 않음 (R16: TTL 부재로 의미 없음).

---

## 2. 모듈 등록 검증 (사전 확인)

`settings.gradle.kts` 라인 32 `includeModules("leader", false, true)` 헬퍼가
`leader/` 하위 디렉터리를 자동으로 `:leader:<dir>` 프로젝트로 등록한다.
→ **settings.gradle.kts 수정은 필요 없음.** 단, `leader/leader-zookeeper/build.gradle.kts`가
생성된 직후 `./gradlew projects` 출력에 `:leader:leader-zookeeper`가 나타나는지 확인.

---

## 3. 작업 목록 (Task Breakdown)

### Phase 0 — 카탈로그 및 모듈 골격

#### T-CAT-1: Gradle 카탈로그 alias 등록 — complexity: low
- **파일**: `gradle/libs.versions.toml`
- **변경**: line 208 (`bluetape4k-leader-redis-lettuce`) 다음 줄에 추가:
  ```toml
  bluetape4k-leader-zookeeper = { module = "io.github.bluetape4k.leader:bluetape4k-leader-zookeeper" }
  ```
- **검증**: alias key `libs.bluetape4k.leader.zookeeper`로 변환됨을 `./gradlew help` 또는 build 단계에서 확인
- **버전 핀 금지** — `bluetape4k-dependencies` BOM 1.1.3이 0.2.1을 관리
- **의존**: 없음

#### T-MOD-1: 모듈 디렉터리 골격 생성 — complexity: low
- **파일/디렉터리**:
  - `leader/leader-zookeeper/`
  - `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/{config,service}/`
  - `leader/leader-zookeeper/src/main/resources/`
  - `leader/leader-zookeeper/src/test/kotlin/io/bluetape4k/workshop/leader/zookeeper/`
  - `leader/leader-zookeeper/src/test/resources/`
- **검증**: `./gradlew projects` 출력에 `:leader:leader-zookeeper` 등장
- **의존**: T-CAT-1 (alias가 있어야 build.gradle.kts 작성 가능)

#### T-MOD-2: build.gradle.kts 작성 — complexity: high
- **파일**: `leader/leader-zookeeper/build.gradle.kts`
- **내용**:
  - `plugins { alias(libs.plugins.kotlin.spring); alias(libs.plugins.spring.boot) }`
  - `springBoot.mainClass.set("io.bluetape4k.workshop.leader.zookeeper.LeaderZookeeperAppKt")`
  - `configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }`
  - `tasks.test { useJUnitPlatform { excludeTags("smoke") } }` (smoke tag 없어도 ALA 컨벤션 유지)
  - dependencies:
    - `implementation(libs.bluetape4k.leader.zookeeper)` — Apache Curator transitive
    - `implementation(libs.bluetape4k.logging)`
    - `implementation(libs.spring.boot.autoconfigure.lib)` + `libs.spring.boot.starter.actuator`
    - `annotationProcessor(libs.spring.boot.autoconfigure.processor)` + `libs.spring.boot.configuration.processor`
    - `runtimeOnly(libs.spring.boot.devtools)`
    - testImplementation: `project(":shared")`, `bluetape4k.coroutines`, `bluetape4k.junit5`, `kotlinx.coroutines.test.lib`, `bluetape4k.testcontainers`, `bluetape4k.assertions`, `spring.boot.starter.test` (mockito-core/junit-vintage/junit exclude), `mockk`
    - testImplementation: `ch.qos.logback:logback-classic` 명시 — T7 `ListAppender<ILoggingEvent>` 사용을 위해 (또는 Spring Boot test 의존성으로 충분한지 검증)
- **검증**: `./gradlew :leader:leader-zookeeper:compileKotlin` 성공
- **의존**: T-CAT-1, T-MOD-1

### Phase 1 — 메인 소스 (Production)

#### T-APP-1: LeaderZookeeperApp 메인 클래스 — complexity: low
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/LeaderZookeeperApp.kt`
- **내용**:
  - `@SpringBootApplication`, `@EnableScheduling`
  - `class LeaderZookeeperApp`
  - `fun main(args: Array<String>) { runApplication<LeaderZookeeperApp>(*args) }`
  - English KDoc on class (1줄 요약 + `## Behavior / Contract` 섹션: 이 앱은 ZK 기반 4-elector 데모임을 명시)
- **검증**: 컴파일 성공
- **의존**: T-MOD-2

#### T-PROPS-1: LeaderZookeeperProperties — complexity: medium
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/config/LeaderZookeeperProperties.kt`
- **내용**: 스펙 §5.1 그대로
  - `@ConfigurationProperties(prefix = "leader.zookeeper")`
  - `data class LeaderZookeeperProperties` (Serializable, serialVersionUID)
  - 필드: `zookeeper`(중첩), `basePath`, `waitTime: java.time.Duration`, `groupMaxLeaders: Int`, `jobFixedDelay`, `suspendJobFixedDelay`, `groupJobFixedDelay`, `suspendGroupJobFixedDelay`
  - **R16 — leaseTime/autoExtend 의도적 부재** (코드 주석 명시)
  - 중첩 `data class ZooKeeperConfig`: `connectString`, `sessionTimeoutMs`, `connectionTimeoutMs`, `blockUntilConnectedSeconds`
  - Outer `init { basePath.requireNotBlank("basePath"); groupMaxLeaders.requirePositiveNumber("groupMaxLeaders") }` — connectString is validated inside `ZooKeeperConfig.init` (not outer class)
  - `ZooKeeperConfig.init { connectString.requireNotBlank("connectString") }` — spec §5.1 ZooKeeperConfig.init already shows this correctly
- **English KDoc**: `## Behavior / Contract (R16 — ZooKeeper has no TTL)` 섹션 + `## Production Considerations` 섹션 (스펙 §5.1 KDoc 전체 사용)
- **검증**: `compileKotlin` 성공; `@ConfigurationProperties` 어노테이션 프로세서 메타데이터 생성 확인
- **의존**: T-APP-1

#### T-CFG-1: LeaderZookeeperConfig (Spring 빈 등록) — complexity: high
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/config/LeaderZookeeperConfig.kt`
- **내용**: 스펙 §5.2 정밀 구현
  - `@Configuration`, `@EnableConfigurationProperties(LeaderZookeeperProperties::class)`
  - `companion object : KLogging()`
  - `@Bean(destroyMethod = "close")` `fun curatorFramework(props): CuratorFramework`:
    1. `CuratorFrameworkFactory.newClient(connectString, sessionTimeoutMs, connectionTimeoutMs, ExponentialBackoffRetry(1000, 3))`
    2. **start() 호출 전에** `connectionStateListenable.addListener { _, newState -> ... }` 등록 (SUSPENDED/LOST/RECONNECTED 로깅)
    3. `client.start()`
    4. `if (!client.blockUntilConnected(cfg.blockUntilConnectedSeconds, TimeUnit.SECONDS)) { client.close(); error("...") }` — **C2: 명시적 close**
    5. ACL 주석 명시 (`OPEN_ACL_UNSAFE` default; production은 `DigestACLProvider` 권장)
  - 4개 elector 빈:
    - `@Bean fun leaderElector(curator, props): ZooKeeperLeaderElector` (basePath: `${props.basePath}/single`, options.waitTime = `props.waitTime.toKotlinDuration()`)
    - `@Bean fun suspendLeaderElector(curator, props): ZooKeeperSuspendLeaderElector` (basePath: `${props.basePath}/single-suspend`)
    - `@Bean fun leaderGroupElector(curator, props): ZooKeeperLeaderGroupElector`:
      ```kotlin
      ZooKeeperLeaderGroupElector(
          client = curator,
          options = LeaderGroupElectionOptions(maxLeaders = props.groupMaxLeaders, waitTime = props.waitTime.toKotlinDuration()),
          basePath = "${props.basePath}/group"
      )
      ```
      **NOTE (T3-1)**: Parameter order is `(client, options, basePath)` — NOT `(client, basePath, options)`. Confirmed from library source `ZooKeeperLeaderGroupElector.kt:179-184`.
    - `@Bean fun suspendLeaderGroupElector(curator, props): ZooKeeperSuspendLeaderGroupElector`:
      ```kotlin
      ZooKeeperSuspendLeaderGroupElector(
          client = curator,
          options = LeaderGroupElectionOptions(maxLeaders = props.groupMaxLeaders, waitTime = props.waitTime.toKotlinDuration()),
          basePath = "${props.basePath}/group-suspend"
      )
      ```
      Same parameter order as ZooKeeperLeaderGroupElector.
  - **R16 — leaseTime 미설정** (LeaderElectionOptions에 leaseTime 인자 미전달)
- **English KDoc**: ConnectionStateListener 등록 시점, blockUntilConnected 실패 시 자원 정리, ACL 기본값 한계
- **검증**: 컴파일; `@SpringBootTest` 컨텍스트 로드 (T0)
- **의존**: T-PROPS-1

#### T-SVC-1: BlockingLeaderService — complexity: medium
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/service/BlockingLeaderService.kt`
- **내용**: 스펙 §5.3 BlockingLeaderService
  - `@Service`
  - `companion object : KLogging()`
  - `executionCount: AtomicInteger`
  - `fun runLeaderWork(lockName = "workshop:blocking-job"): String?` — `elector.runIfLeader(lockName) { ... }` 사용
  - `@Scheduled(fixedDelayString = "\${leader.zookeeper.job-fixed-delay:PT10S}")` `fun runScheduled()` — try/catch Exception (CancellationException 노출 없음 — blocking)
- **English KDoc**: `## Behavior / Contract` (skip semantics, executionCount는 테스트용)
- **검증**: 컴파일 + T1 통과
- **의존**: T-CFG-1

#### T-SVC-2: SuspendLeaderZkService — complexity: medium
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/service/SuspendLeaderZkService.kt`
- **내용**: 스펙 §5.3 SuspendLeaderZkService
  - `@Service`
  - `companion object : KLoggingChannel()` (coroutine-safe)
  - `executionCount: AtomicInteger`
  - `suspend fun runLeaderWork(lockName: String = "workshop:suspend-job"): String?`
  - `@Scheduled` wrapper:
    ```kotlin
    fun runScheduled() {
        try { runBlocking { runLeaderWork() } }
        catch (e: CancellationException) { throw e }  // O6 fix
        catch (e: Exception) { log.warn(e) { "..." } }
    }
    ```
- **English KDoc**: CancellationException 재throw 정책, `runBlocking` 사용 위치 (스케줄러 경계만)
- **검증**: 컴파일 + T3 통과 + **CancellationException rethrow 단위 테스트 (H6)**:
  ```kotlin
  @Test fun `runScheduled rethrows CancellationException`() {
      val service = SuspendLeaderZkService(mockk { coEvery { runIfLeader(any()) { any() } } throws CancellationException("test") })
      assertFailsWith<CancellationException> { service.runScheduled() }
  }
  ```
  단위 테스트는 Spring 컨텍스트 불필요 — MockK로 `SuspendLeaderElector` stub. `SuspendLeaderZkService`의 `runScheduled()` 직접 호출 → `CancellationException` 전파 확인.
  동일 패턴을 `SuspendGroupLeaderService`에도 적용 (T-SVC-4).
- **의존**: T-CFG-1

#### T-SVC-3: GroupLeaderService — complexity: medium
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/service/GroupLeaderService.kt`
- **내용**: 스펙 §5.3 GroupLeaderService
  - `@Service`
  - `ZooKeeperLeaderGroupElector` 주입
  - `companion object : KLogging()`
  - `fun runLeaderWork(lockName = "workshop:group-job"): String?` — `groupElector.runIfLeader(lockName) { ... }`
  - `fun inspectState(lockName): LeaderGroupState` — 활성/슬롯 검증용 (테스트용 헬퍼; KDoc에 internal exposure 명시)
  - `@Scheduled` wrapper: try/catch Exception (blocking이므로 CancellationException 분기 불필요)
- **English KDoc**: `maxLeaders` 의미, inspectState 사용 권장 시점
- **검증**: 컴파일 + T4 통과
- **의존**: T-CFG-1

#### T-SVC-4: SuspendGroupLeaderService — complexity: medium
- **파일**: `leader/leader-zookeeper/src/main/kotlin/io/bluetape4k/workshop/leader/zookeeper/service/SuspendGroupLeaderService.kt`
- **내용**: 스펙 §5.3 SuspendGroupLeaderService
  - `@Service`
  - `ZooKeeperSuspendLeaderGroupElector` 주입
  - `companion object : KLoggingChannel()`
  - `suspend fun runLeaderWork(lockName): String?`
  - `@Scheduled` wrapper — **CancellationException 재throw 포함** (SuspendLeaderZkService와 동일 패턴)
- **English KDoc**: SuspendLeaderZkService 패턴 cross-link
- **검증**: 컴파일 + T5 통과 + CancellationException rethrow 단위 테스트 (T-SVC-2와 동일 패턴, `SuspendGroupLeaderElector` stub 사용)
- **의존**: T-CFG-1

#### T-RES-1: application.yml — complexity: low
- **파일**: `leader/leader-zookeeper/src/main/resources/application.yml`
- **내용**:
  - `spring.application.name: leader-zookeeper`
  - `leader.zookeeper`:
    - `zookeeper.connect-string: localhost:2181`
    - `zookeeper.session-timeout-ms: 60000`
    - `zookeeper.connection-timeout-ms: 15000`
    - `zookeeper.block-until-connected-seconds: 10`
    - `base-path: /workshop/leader-zookeeper`
    - `wait-time: 2s`
    - `group-max-leaders: 2`
    - `job-fixed-delay: PT10S`
    - `suspend-job-fixed-delay: PT12S`
    - `group-job-fixed-delay: PT15S`
    - `suspend-group-job-fixed-delay: PT18S`
  - `management.endpoints.web.exposure.include: health,info`
  - `logging.level`:
    - `io.bluetape4k.workshop.leader.zookeeper: INFO`
    - `io.bluetape4k.leader.zookeeper: DEBUG`
- **검증**: 부트 시 바인딩 오류 없음 (T0가 검증)
- **의존**: T-PROPS-1

#### T-RES-2: logback-spring.xml — complexity: low
- **파일**: `leader/leader-zookeeper/src/main/resources/logback-spring.xml`
- **내용**: `leader-election`의 `logback-spring.xml`을 그대로 복제하고 logger 이름만 변경
  - `<logger name="io.bluetape4k.workshop.leader.zookeeper" level="INFO"/>`
  - `<logger name="io.bluetape4k.leader.zookeeper" level="DEBUG"/>`
- **검증**: 앱 부트 콘솔 로그 정상 출력
- **의존**: T-APP-1

### Phase 2 — 테스트 인프라

#### T-TEST-RES-1: junit-platform.properties + logback-test.xml — complexity: low
- **파일**:
  - `leader/leader-zookeeper/src/test/resources/junit-platform.properties`
  - `leader/leader-zookeeper/src/test/resources/logback-test.xml`
- **내용**:
  - `junit-platform.properties`: `leader-election`의 것을 복제 (PER_CLASS, parallel=false, exclude tag smoke, constructor autowire=all)
  - `logback-test.xml`: `leader-election`의 것을 복제 + `io.bluetape4k.leader.zookeeper` 레벨 추가
- **검증**: 테스트 실행 시 로그 캡처 가능
- **의존**: T-MOD-2

#### T-TEST-BASE-1: AbstractLeaderZookeeperTest — complexity: high
- **파일**: `leader/leader-zookeeper/src/test/kotlin/io/bluetape4k/workshop/leader/zookeeper/AbstractLeaderZookeeperTest.kt`
- **내용**: 스펙 §5.4 정밀 구현
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`
  - `abstract class AbstractLeaderZookeeperTest`
  - `companion object : KLogging()`:
    - `val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper`
    - `val curator: CuratorFramework by lazy { ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also { it.start(); check(it.blockUntilConnected(10, TimeUnit.SECONDS)) { "Test curator could not connect to ZooKeeper within 10s (url=${zookeeper.url})" }; ShutdownQueue.register { it.close() } } }`
    - **NOTE (T2-1)**: `blockUntilConnected` return value MUST be checked — otherwise slow CI silently uses a CONNECTING state curator.
  - factory 메서드 4종 (`newElector`, `newSuspendElector`, `newGroupElector(maxLeaders=2)`, `newSuspendGroupElector(maxLeaders=2)`) — 모두 `waitTime = 500.milliseconds`
  - `fun randomLockName(prefix = "t") = "$prefix:${UUID.randomUUID()}"`
- **English KDoc**: 단일 shared curator 정당성 (InterProcessMutex는 thread-keyed); ZK Launcher 싱글턴 사용 이유
- **검증**: 모든 테스트의 베이스로 의존성 만족; T1~T7 통과 (T8은 별도 격리)
- **의존**: T-CFG-1 (elector 타입을 노출하므로)

### Phase 3 — 테스트 클래스 (T0~T8)

#### T-T0: LeaderZookeeperContextTest — complexity: medium
- **파일**: `leader/leader-zookeeper/src/test/kotlin/io/bluetape4k/workshop/leader/zookeeper/LeaderZookeeperContextTest.kt`
- **내용**:
  - `@SpringBootTest`
  - companion: `ZooKeeperServer.Launcher.zookeeper` + `@DynamicPropertySource` `leader.zookeeper.zookeeper.connect-string` 주입
  - 생성자 주입: `ZooKeeperLeaderElector`, `ZooKeeperSuspendLeaderElector`, `ZooKeeperLeaderGroupElector`, `ZooKeeperSuspendLeaderGroupElector`, `BlockingLeaderService`, `SuspendLeaderZkService`, `GroupLeaderService`, `SuspendGroupLeaderService`
  - `@Test fun `Spring context loads all 4 electors and 4 services``: 모든 빈 shouldNotBeNull
- **English KDoc**: T0 목적, `@DynamicPropertySource` 사용 이유
- **검증**: `./gradlew :leader:leader-zookeeper:test --tests "*Context*"` 성공
- **의존**: T-SVC-{1..4}, T-RES-1, T-TEST-RES-1

#### T-T1: BlockingSingleLeaderTest — complexity: medium
- **파일**: `.../BlockingSingleLeaderTest.kt`
- **내용**:
  - extends `AbstractLeaderZookeeperTest`
  - `@Test runIfLeader returns "done"`: `newElector().runIfLeader(randomLockName()) { "done" } shouldBeEqualTo "done"`
  - `@Test runAsyncIfLeader returns CompletableFuture<42>`: `newElector().runAsyncIfLeader(randomLockName(), VirtualThreadExecutor) { CompletableFuture.completedFuture(42) }.get(5, SECONDS) shouldBeEqualTo 42`
  - `@Test runIfLeader returns null when action body throws — caught at caller`: (선택, 동작 명확화용)
- **English KDoc**: T1 목적 (single-leader 두 진입점)
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T2: ConcurrentBlockingLeaderTest — complexity: high
- **파일**: `.../ConcurrentBlockingLeaderTest.kt`
- **내용**:
  - extends `AbstractLeaderZookeeperTest`
  - `@Test 8 workers contend; peakConcurrent ≤ 1`:
    - `val peakConcurrent = AtomicInteger(0)`; `val current = AtomicInteger(0)`; `val executed = AtomicInteger(0)`
    - `val lockName = randomLockName("t2")`
    - `val elector = newElector()` — **단일 shared curator + 단일 elector** (스펙 §5.4 근거)
    - `MultithreadingTester().workers(8).rounds(1).add { elector.runIfLeader(lockName) { val c = current.incrementAndGet(); peakConcurrent.updateAndGet { max(it, c) }; executed.incrementAndGet(); Thread.sleep(600); current.decrementAndGet() } }.run()`
    - `peakConcurrent.get() shouldBeLessOrEqualTo 1`; `executed.get() shouldBeGreaterThan 3` — asserting > 3 (not just > 0) ensures at least some workers acquired in sequence; a 0-count means ZK/waitTime misconfiguration, not timing
- **English KDoc**: T2 목적, `Thread.sleep(600)` (>waitTime=500ms) 정당성, single shared curator/elector 정당성
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T3: SuspendSingleLeaderTest — complexity: high
- **파일**: `.../SuspendSingleLeaderTest.kt`
- **내용**:
  - extends `AbstractLeaderZookeeperTest`
  - `@Test runIfLeader (suspend) returns "done"`: `runTest { newSuspendElector().runIfLeader(randomLockName()) { "done" } shouldBeEqualTo "done" }`
  - `@Test 8 suspend coroutines contend; peakConcurrent ≤ 1`:
    - `SuspendedJobTester` 8-coroutine + shared `suspendElector`
    - `peakConcurrent` 패턴 + `delay(600.milliseconds)`
- **English KDoc**: SuspendedJobTester 사용 이유; per-call single-thread dispatcher가 라이브러리 내부에서 처리되므로 외부 동기화 불필요
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T4: GroupLeaderTest — complexity: high
- **파일**: `.../GroupLeaderTest.kt`
- **내용**: 스펙 §5.5 T4 CountDownLatch 패턴 정밀 구현
  - extends `AbstractLeaderZookeeperTest`
  - `@Test maxLeaders=2 admits exactly 2 simultaneous holders`:
    - `val maxLeaders = 2`
    - `val groupElector = newGroupElector(maxLeaders)`
    - `val lockName = randomLockName("t4")`
    - `val enteredLatch = CountDownLatch(maxLeaders)`
    - `val releaseLatch = CountDownLatch(1)`
    - `val peakConcurrent = AtomicInteger(0)`; `val current = AtomicInteger(0)`
    - body:
      ```kotlin
      MultithreadingTester().workers(4).rounds(1).add {
          groupElector.runIfLeader(lockName) {
              val c = current.incrementAndGet()
              peakConcurrent.updateAndGet { max(it, c) }
              try {
                  enteredLatch.countDown()
                  check(releaseLatch.await(3, TimeUnit.SECONDS)) { "releaseLatch timeout" }
              } finally { current.decrementAndGet() }
          }
      }
      ```
    - **⚠️ CRITICAL ORDERING (T5-1)**: The orchestrator MUST start BEFORE `MultithreadingTester.run()` — `run()` blocks the calling thread until all workers finish:
      ```kotlin
      // CORRECT: orchestrator thread starts before blocking .run()
      val orchestrator = Thread {
          check(enteredLatch.await(5, TimeUnit.SECONDS)) {
              "Not enough workers entered — CI too slow or maxLeaders not reached"
          }
          releaseLatch.countDown()
      }
      orchestrator.start()  // ← START BEFORE .run()
      MultithreadingTester().workers(4).rounds(1).add { ... }.run()  // blocks here
      orchestrator.join(6_000)
      ```
      If orchestrator starts AFTER `.run()`, workers block on `releaseLatch.await` forever → deadlock.
    - assert: `peakConcurrent.get() shouldBeEqualTo maxLeaders` (== 2)
- **English KDoc**: `CountDownLatch` 핸드쉐이크 정당성 (6T1 해결); `MultithreadingTester.run()` 외부에서 sampling 불가능한 이유
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T5: SuspendGroupLeaderTest — complexity: high
- **파일**: `.../SuspendGroupLeaderTest.kt`
- **내용**: T4와 동일 패턴, `SuspendedJobTester(coroutines=4)` 사용:
  - `delay(600.milliseconds)` 사용
  - `enteredLatch`/`releaseLatch`는 `java.util.concurrent.CountDownLatch` (동작 동일)
  - **⚠️ CRITICAL (T5-1)**: T4와 동일하게 orchestrator를 `SuspendedJobTester.run()` 이전에 시작해야 함 — `run()`이 blocking이므로 이후에 시작 시 deadlock 발생
  - 또는 `Channel`/`Job.join` 기반 코루틴 패턴 — 결정은 구현자가 가독성 우선
- **English KDoc**: T4 패턴 cross-link
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T6: ExtensionFunctionTest — complexity: medium
- **파일**: `.../ExtensionFunctionTest.kt`
- **내용**: `CuratorFramework`의 4 elector-family extension 함수 커버
  - extends `AbstractLeaderZookeeperTest`
  - `@Test runIfLeader extension returns "done"`: `curator.runIfLeader(randomLockName(), basePath = "/test/ext-single") { "done" } shouldBeEqualTo "done"`
  - `@Test runAsyncIfLeader extension returns 42`: `curator.runAsyncIfLeader(randomLockName(), basePath = "/test/ext-single-async") { CompletableFuture.completedFuture(42) }.get(5, SECONDS) shouldBeEqualTo 42`
  - `@Test suspendRunIfLeader extension returns "done"`: `runTest { curator.suspendRunIfLeader(randomLockName(), basePath = "/test/ext-suspend") { "done" } shouldBeEqualTo "done" }`
  - `@Test runIfLeaderGroup extension admits leader`:
    ```kotlin
    curator.runIfLeaderGroup(
        randomLockName(),
        options = LeaderGroupElectionOptions(2, waitTime = 500.milliseconds),  // param is "options", NOT "groupOptions"
        basePath = "/test/ext-group"
    ) { "done" } shouldBeEqualTo "done"
    ```
    **⚠️ COMPILE FIX (T3-2)**: The parameter is named `options`, NOT `groupOptions`. Source-verified from `ZooKeeperLeaderGroupElector.kt:181`. Using `groupOptions=` causes compile error.
  - (선택 5th) `@Test suspendRunIfLeaderGroup extension`: 위 코루틴 버전
- **English KDoc**: T6은 라이브러리 extension API 매끄러움 검증
- **검증**: 통과
- **의존**: T-TEST-BASE-1
- **참고**: 
  - `runIfLeaderGroup`/`suspendRunIfLeaderGroup` 시그니처: `(lockName, options: LeaderGroupElectionOptions = .Default, basePath: String = ..., action)` — 파라미터명 `options` (NOT `groupOptions`)
  - `runIfLeader`/`suspendRunIfLeader` 시그니처: `(lockName, basePath, options, action)` 순서 확인
  - ⚠️ 모든 extension 함수는 named argument로 명시적 호출 권장 (순서 혼동 방지)

#### T-T7: R16AutoExtendIgnoredTest — complexity: high
- **파일**: `.../R16AutoExtendIgnoredTest.kt`
- **내용**: 스펙 §5.5 T7 Logback ListAppender 패턴
  - extends `AbstractLeaderZookeeperTest`
  - field: `private lateinit var appender: ListAppender<ILoggingEvent>`
  - `@BeforeEach setupLogCapture()`: `LoggerFactory.getLogger("io.bluetape4k.leader.zookeeper") as Logger` → `ListAppender<ILoggingEvent>().also { it.start() }` 첨부
  - `@AfterEach teardownLogCapture()`:
    ```kotlin
    @AfterEach fun teardownLogCapture() {
        (LoggerFactory.getLogger("io.bluetape4k.leader.zookeeper") as Logger).detachAppender(appender)
        appender.stop()
    }
    ```
    **⚠️ MUST call detachAppender BEFORE stop() (T5-4)** — `stop()` alone does NOT remove the appender from the logger. Without `detachAppender`, the stopped appender leaks into subsequent tests sharing the same logger.
  - `@Test autoExtend option is silently ignored with WARN log`:
    - `val elector = ZooKeeperLeaderElector(curator, "/test/r16", LeaderElectionOptions(autoExtend = true, waitTime = 500.milliseconds))`
    - `val result = elector.runIfLeader(randomLockName()) { "r16-done" }`
    - `result shouldBeEqualTo "r16-done"`
    - `val warnEvents = appender.list.filter { it.level == Level.WARN && "autoExtend" in it.formattedMessage }`
    - `warnEvents.shouldNotBeEmpty()`
    - **NOTE**: The WARN message is in Korean: `"ZooKeeper 는 TTL 이 없는 세션 기반 락 — autoExtend=true 설정이 무시됩니다. ..."`. The ASCII token `"autoExtend"` appears verbatim in the Korean text. **Do NOT change the assertion substring** — `"autoExtend" in formattedMessage` is correct and source-confirmed from `ZooKeeperLeaderElector.kt:111-112`.
- **English KDoc**: T7은 R16 계약의 관찰 가능한 시연; ListAppender 패턴 이유 (Logback 의존 — Spring Boot test가 제공)
- **검증**: 통과; `LeaderElectionOptions.autoExtend` 시그니처 라이브러리 확인 후 인자 이름 조정
- **의존**: T-TEST-BASE-1
- **참고**: 라이브러리 WARN 레벨 확인됨 — `ZooKeeperLeaderElector.kt:110-115`: `if (options.autoExtend) { log.warn { "ZooKeeper 는 TTL 이 없는 세션 기반 락 — autoExtend=true 설정이 무시됩니다..." } }`. R-1 위험 항목 무효화됨(퇴역). WARN 레벨 어설션이 정확함.

#### T-T8: SessionLossFailoverTest — complexity: high
- **파일**: `.../SessionLossFailoverTest.kt`
- **내용**: 클라이언트 측 세션 강제 종료로 R16 세션 만료 시나리오 시연
  - **⚠️ STANDALONE class — does NOT extend AbstractLeaderZookeeperTest (H1)**:
    `AbstractLeaderZookeeperTest`를 상속하면 싱글턴 `ZooKeeperServer.Launcher.zookeeper`가 companion을 통해 접근 가능해져 격리 보장이 깨짐 → standalone `@TestInstance(PER_CLASS)` 클래스로 구현
  - **⚠️ NO container restart (T2-2/T6-2 CRITICAL fix)**:
    `ZooKeeperServer(reuse=true)` default로 `stop()`/`start()`는 포트가 바뀌어 curator 클라이언트가 stale 상태가 됨. 컨테이너 재시작 대신 **클라이언트 측 세션 강제 종료** 사용.
  - fields:
    - `private lateinit var isolatedZk: ZooKeeperServer`
    - `private lateinit var clientA: CuratorFramework`
    - `private lateinit var clientB: CuratorFramework`
    - `fun randomLockName(prefix: String = "t8") = "$prefix:${UUID.randomUUID()}"` — AbstractLeaderZookeeperTest 미상속이므로 로컬 선언
  - `@BeforeAll fun startIsolatedZk()`:
    ```kotlin
    @BeforeAll fun startIsolatedZk() {
        isolatedZk = ZooKeeperServer(reuse = false).also { it.start() }  // reuse=false for controllable lifecycle
        clientA = ZooKeeperServer.Launcher.getCuratorFramework(isolatedZk).also {
            it.start()
            check(it.blockUntilConnected(10, TimeUnit.SECONDS)) { "clientA connection timeout" }
        }
        clientB = ZooKeeperServer.Launcher.getCuratorFramework(isolatedZk).also {
            it.start()
            check(it.blockUntilConnected(10, TimeUnit.SECONDS)) { "clientB connection timeout" }
        }
    }
    ```
  - `@AfterAll fun stopIsolatedZk()`:
    ```kotlin
    @AfterAll fun stopIsolatedZk() {
        runCatching { clientA.close() }
        runCatching { clientB.close() }
        runCatching { isolatedZk.stop() }
    }
    ```
  - `@Test session loss causes leadership failover`:
    ```kotlin
    @Test fun `session loss causes leadership failover`() {
        val lockName = randomLockName()
        val workerAHoldingLatch = CountDownLatch(1)  // T5-2: ensure workerA HOLDS the lock before session close
        val workerADoneLatch = CountDownLatch(1)
        
        // workerA acquires leadership in background thread
        val workerAThread = Thread {
            clientA.runIfLeader(lockName, basePath = "/test/t8") {
                workerAHoldingLatch.countDown()  // signal: "I now hold the lock"
                Thread.sleep(60_000)  // hold indefinitely until session closed
                "A-done"
            }
            workerADoneLatch.countDown()
        }
        workerAThread.start()
        
        // Wait for workerA to hold the lock before proceeding
        check(workerAHoldingLatch.await(10, TimeUnit.SECONDS)) { "workerA did not acquire leadership in time" }
        
        // Simulate session expiry by closing the underlying ZooKeeper client connection
        // This forces Curator into LOST state and removes ephemeral nodes
        clientA.zookeeperClient.zooKeeper.close()  // client-side session close — no container restart needed
        
        // workerB should eventually acquire leadership after workerA's session expires
        var workerBResult: String? = null
        await().atMost(Duration.ofSeconds(30)).untilAsserted {
            workerBResult = clientB.runIfLeader(lockName, basePath = "/test/t8") { "B-acquired" }
            workerBResult shouldBeEqualTo "B-acquired"
        }
        
        workerAThread.interrupt()  // release workerA from sleep
        workerADoneLatch.await(5, TimeUnit.SECONDS)
    }
    ```
  - **타이밍**: ZK session 만료는 `sessionTimeoutMs` 기반 (기본 60초 → 너무 길면 테스트용 `sessionTimeoutMs=3000`으로 단축 가능). `Awaitility.await().atMost(30.seconds)` 사용.
  - **Extension 함수 사용**: `clientA.runIfLeader(...)` — `ZooKeeperLeaderElectorExtensions.kt`의 extension 함수 사용 가능. 또는 `ZooKeeperLeaderElector(clientA, basePath, options).runIfLeader(lockName)` 직접 생성도 가능.
- **English KDoc**: T8 isolation 정당성 (standalone, no AbstractLeaderZookeeperTest inheritance); R16 client-side session close approach vs container restart pitfall
- **검증**: 통과; 타이밍 문제 시 `sessionTimeoutMs` 조정
- **의존**: T-MOD-2 (Testcontainers), T-CFG-1 (elector type for extension function)
- **참고**: `clientA.zookeeperClient.zooKeeper.close()`는 `org.apache.zookeeper.ZooKeeper` 직접 접근 — ZooKeeper 세션을 ZK 서버 측에서 종료시켜 Ephemeral Node 삭제를 트리거. 이는 네트워크 파티션/프로세스 크래시 시나리오와 동일한 결과를 만듦.

#### T-T9: LeaderZookeeperPropertiesValidationTest — complexity: low
- **파일**: `.../LeaderZookeeperPropertiesValidationTest.kt`
- **내용**: CLAUDE.md "Parameter validation tasks present" 체크 — `requireNotBlank`/`requirePositiveNumber` 검증
  - extends `AbstractLeaderZookeeperTest` (ZK 컨테이너 불필요하지만 base 클래스 컨벤션 유지, 또는 독립 클래스)
  - `@Test blank basePath throws IllegalArgumentException`:
    ```kotlin
    assertFailsWith<IllegalArgumentException> {
        LeaderZookeeperProperties(basePath = "")
    }
    ```
  - `@Test zero groupMaxLeaders throws IllegalArgumentException`:
    ```kotlin
    assertFailsWith<IllegalArgumentException> {
        LeaderZookeeperProperties(groupMaxLeaders = 0)
    }
    ```
  - `@Test blank connectString throws IllegalArgumentException`:
    ```kotlin
    assertFailsWith<IllegalArgumentException> {
        LeaderZookeeperProperties(zookeeper = LeaderZookeeperProperties.ZooKeeperConfig(connectString = ""))
    }
    ```
- **English KDoc**: T9 목적 (parameter validation contract verification)
- **검증**: 통과
- **의존**: T-PROPS-1

### Phase 4 — 문서

#### T-DOC-1: README.md (English) — complexity: medium
- **파일**: `leader/leader-zookeeper/README.md`
- **내용** (스펙 §7 DoD):
  1. **Architecture**: 모듈 다이어그램 (SVG+PNG, `docs/images/readme-diagrams/` 하위; mermaid/ASCII 금지) — Curator + ZK + 4 elector 빈 구조
  2. **Core features**: 4 elector 종류 + R16 callout 정의
  3. **Usage examples — side-by-side**:
     - single-leader (`runIfLeader`) + group-leader (`maxLeaders=2`) 코드 예제
     - blocking + suspend 코루틴 버전
  4. **R16 — ZooKeeper has no TTL** (전용 섹션):
     - leaseTime/autoExtend 부재 사유
     - sessionTimeoutMs로 failover latency 조율
     - T8 시나리오 다이어그램 또는 시퀀스 (선택)
  5. **Configuration options**: `leader.zookeeper.*` 프로퍼티 전체 표
  6. **Production Considerations**: ACL/TLS/SASL 권장사항 + sessionTimeoutMs 튜닝
  7. **Dependency instructions**: 카탈로그 alias 사용법, BOM 참조
  8. Cross-link: `leader/leader-election/` (Redis 비교)
  9. **Spring Boot compatibility**: "Verified with Spring Boot 4.0.6. Spring Boot 3.x users must update `spring.factories` / `AutoConfiguration.imports` paths." — explicit compat statement required in README header.
- **검증**: `git diff --check`, README 컨텐츠 영문 일관성 (CLAUDE.md 정책)
- **의존**: T-MOD-1 (모듈 디렉터리 존재)

#### T-DOC-2: README.ko.md (Korean) — complexity: low
- **파일**: `leader/leader-zookeeper/README.ko.md`
- **내용**: T-DOC-1 한국어 번역본 (CLAUDE.md README locale 정책 — 멀티링구얼 허용)
  - 동일 구조, 동일 이미지 자산 참조
  - R16 callout, side-by-side 코드 예제, ACL/TLS 권장사항 모두 포함
- **검증**: README.md와 섹션 1:1 대응 확인
- **의존**: T-DOC-1

#### T-DOC-3: docs/lessons 문서 작성 — complexity: low
- **파일**: `docs/lessons/2026-05-25-leader-zookeeper.md` (feature worktree 내부, 브랜치에 커밋)
- **내용** (Korean OK per CLAUDE.md `docs/lessons/` 언어 정책):
  - R16 계약 시연 및 `autoExtend=true` WARN 처리
  - `AbstractLeaderZookeeperTest` 단일 shared curator 패턴 (InterProcessMutex Thread-keyed 근거)
  - T8: 클라이언트 측 세션 강제 종료 방식 채택 이유 (컨테이너 재시작 포트 문제)
  - T4/T5 CountDownLatch 핸드쉐이크 패턴 (MultithreadingTester.run() blocking)
  - T7 ListAppender 패턴: `detachAppender()` 필수 이유
  - T-T6 extension function `options` vs `groupOptions` 오류 사례
- **검증**: 파일 커밋 완료 (`git log` 확인)
- **의존**: T-VRF-1 (구현 완료 후 작성)

### Phase 5 — 검증 (DoD)

#### T-VRF-1: 전체 테스트 실행 + DoD 체크 — complexity: medium
- **명령**:
  ```bash
  ./gradlew :leader:leader-zookeeper:compileKotlin
  ./gradlew :leader:leader-zookeeper:test
  ```
- **DoD 통과 기준** (스펙 §7):
  - [ ] T0~T9 10개 테스트 모두 통과 (T9: LeaderZookeeperPropertiesValidationTest 포함)
  - [ ] `compileKotlin` 0 errors, 0 unresolved deprecation
  - [ ] IDE diagnostics 0 errors (해당 시 `ide_diagnostics` 결과 첨부)
  - [ ] HIGH/CRITICAL code review 0 (`/oh-my-claudecode:code-reviewer` 실행)
  - [ ] README.md + README.ko.md (R16 callout, side-by-side, ACL/TLS 포함)
  - [ ] English KDoc on all public types
  - [ ] T4/T5 CountDownLatch 핸드쉐이크 + `peakConcurrent` 검증
  - [ ] T7 Logback ListAppender WARN 캡처
  - [ ] T8 세션 만료 시나리오 통과
  - [ ] `@Scheduled` wrapper CancellationException 재throw 적용 (SVC-2, SVC-4) — 직접 호출 시 CancellationException 전파 확인 (단위 테스트)
- **의존**: T-T0~T-T8, T-DOC-1, T-DOC-2

#### T-VRF-2: 코드 리뷰 — complexity: medium
- **명령**: `/oh-my-claudecode:code-reviewer` (CRITICAL/HIGH 0 통과 시까지 반복)
- **체크리스트**:
  - CLAUDE.md 정책 준수: KDoc 영문, 한국어 로그 없음, `!!` 없음, `runCatching` 동기만, `CancellationException` 재throw, `@Synchronized` 없음
  - bluetape4k 패턴: `requireNotBlank` 등 사용, `KLogging`/`KLoggingChannel` companion, `@TestInstance(PER_CLASS)`, `bluetape4k-assertions` 매처
  - Spring Boot 4 패턴: `@Bean(destroyMethod = "close")`, `@DynamicPropertySource`, `@ConfigurationProperties` 어노테이션 프로세서
  - Testcontainers: `ZooKeeperServer.Launcher.zookeeper` 싱글턴, `@Testcontainers` 미사용
- **의존**: T-VRF-1

---

## 4. 의존성 그래프 (요약)

```
T-CAT-1 ─┐
         ├─ T-MOD-1 ─ T-MOD-2 ─ T-APP-1 ─ T-PROPS-1 ─ T-CFG-1 ─┬─ T-SVC-1 ─┐
         │                                                      ├─ T-SVC-2 ─┤
         │                                                      ├─ T-SVC-3 ─┤
         │                                                      └─ T-SVC-4 ─┤
         │                                                                  │
         │                                       T-RES-1 ──────────────────┤
         │                                       T-RES-2 ──────────────────┤
         │                                                                  │
         └─ T-TEST-RES-1 ─ T-TEST-BASE-1 ─┬─ T-T0 ←─────────────────────────┤
                                          ├─ T-T1                            │
                                          ├─ T-T2                            │
                                          ├─ T-T3                            │
                                          ├─ T-T4                            │
                                          ├─ T-T5                            │
                                          ├─ T-T6                            │
                                          ├─ T-T7                            │
                                          └─ T-T8                            │
                                                                             │
                          T-DOC-1 ─ T-DOC-2 ──────────────────────────────── │
                                                                             │
                          T-VRF-1 (모든 위 작업) ─ T-VRF-2 ─────────────────┘
```

---

## 5. 우선순위 실행 순서 (Ordered Task List)

| Order | Task ID | Complexity | 비고 |
|---|---|---|---|
| 1 | T-CAT-1 | low | 카탈로그 alias |
| 2 | T-MOD-1 | low | 디렉터리 골격 |
| 3 | T-MOD-2 | high | build.gradle.kts |
| 4 | T-APP-1 | low | 메인 App |
| 5 | T-PROPS-1 | medium | Properties (R16 KDoc) |
| 6 | T-CFG-1 | high | Config 빈 (ConnectionStateListener, 자원 누수 방지) |
| 7 | T-SVC-1 | medium | BlockingLeaderService |
| 8 | T-SVC-2 | medium | SuspendLeaderZkService (Cancellation rethrow) |
| 9 | T-SVC-3 | medium | GroupLeaderService |
| 10 | T-SVC-4 | medium | SuspendGroupLeaderService (Cancellation rethrow) |
| 11 | T-RES-1 | low | application.yml |
| 12 | T-RES-2 | low | logback-spring.xml |
| 13 | T-TEST-RES-1 | low | junit-platform.properties + logback-test.xml |
| 14 | T-TEST-BASE-1 | high | AbstractLeaderZookeeperTest (lazy shared curator) |
| 15 | T-T0 | medium | Spring context |
| 16 | T-T1 | medium | runIfLeader + runAsyncIfLeader |
| 17 | T-T2 | high | MultithreadingTester 동시성 |
| 18 | T-T3 | high | SuspendedJobTester |
| 19 | T-T4 | high | Group CountDownLatch 핸드쉐이크 |
| 20 | T-T5 | high | Suspend Group |
| 21 | T-T6 | medium | Extension 함수 |
| 22 | T-T7 | high | Logback ListAppender (R16 WARN) |
| 23 | T-T8 | high | 격리 ZK 컨테이너, 세션 만료 (클라이언트 측 세션 강제 종료) |
| 24 | T-T9 | low | Properties 검증 (requireNotBlank, requirePositiveNumber) |
| 25 | T-DOC-1 | medium | README.md (영) |
| 26 | T-DOC-2 | low | README.ko.md |
| 27 | T-DOC-3 | low | docs/lessons 문서 |
| 28 | T-VRF-1 | medium | DoD 체크 |
| 29 | T-VRF-2 | medium | code-reviewer HIGH/CRITICAL 0 |

---

## 6. 위험 요소 및 미해결 항목

| ID | 위험 | 대응 |
|---|---|---|
| R-1 | ~~T7 WARN 로그 캡처 실패~~ | **퇴역** — 라이브러리 소스 확인: `ZooKeeperLeaderElector.kt:110-115` WARN 레벨 확인. Korean 메시지에 ASCII `autoExtend` 포함 확인. |
| R-2 | T6 라이브러리 extension 함수 시그니처 변경 (basePath default) | 컴파일 에러 시 라이브러리 소스 조회 후 인자 명시 |
| R-3 | T8 세션 만료 타이밍 (CI 환경) | `sessionTimeoutMs=3000` (테스트용 단축), `Awaitility.atMost(30.seconds)` 사용. 클라이언트 측 세션 강제 종료 방식 채택으로 포트 remapping 문제 제거됨. |
| R-4 | `bluetape4k-leader-zookeeper` BOM 미포함 (1.1.3 검증) | T-CAT-1 직후 `./gradlew :leader:leader-zookeeper:dependencies` 로 0.2.1 해석 확인 |
| R-5 | T-T0 `@DynamicPropertySource` 와 nested `zookeeper.connect-string` 바인딩 | 키는 `leader.zookeeper.zookeeper.connect-string` (이중 zookeeper) — application.yml과 동일 구조 |
| R-6 | T7 ListAppender가 `logback-test.xml`의 ROOT logger 설정과 충돌 | `addAppender`만 사용하고 ROOT level 변경 금지; `@AfterEach`에서 반드시 `detachAppender` |

---

## 7. 참고 자료

- 스펙: `docs/superpowers/specs/2026-05-25-leader-zookeeper-design.md`
- 참조 모듈: `leader/leader-election/` (Redis/Lettuce)
- 라이브러리 소스: `/Users/debop/work/bluetape4k/bluetape4k-leader/leader-zookeeper/`
- 라이브러리 테스트 베이스: `AbstractZooKeeperLeaderTest` (단일 shared curator 패턴 출처)
- CLAUDE.md (워크숍 루트): 로깅 영문, KDoc 영문, CancellationException 재throw, Testcontainers Launcher 싱글턴
