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
4. 9개 테스트 클래스 (T0~T8) — DoD 통과 기준

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
  - `init { basePath.requireNotBlank("basePath"); groupMaxLeaders.requirePositiveNumber("groupMaxLeaders"); zookeeper.connectString.requireNotBlank("connectString") }`
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
    - `@Bean fun leaderGroupElector(curator, props): ZooKeeperLeaderGroupElector` (basePath: `${props.basePath}/group`, options: `LeaderGroupElectionOptions(props.groupMaxLeaders, waitTime = ...)`)
    - `@Bean fun suspendLeaderGroupElector(curator, props): ZooKeeperSuspendLeaderGroupElector` (basePath: `${props.basePath}/group-suspend`)
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
- **검증**: 컴파일 + T3 통과
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
- **검증**: 컴파일 + T5 통과
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
    - `val curator: CuratorFramework by lazy { ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also { it.start(); it.blockUntilConnected(10, TimeUnit.SECONDS); ShutdownQueue.register { it.close() } } }`
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
    - `peakConcurrent.get() shouldBeLessOrEqualTo 1`; `executed.get() shouldBeGreaterThan 0`
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
    - Separate orchestrator thread (또는 별도 `CompletableFuture.runAsync`)에서: `enteredLatch.await(3, SECONDS)` 성공 시 `releaseLatch.countDown()`
    - assert: `peakConcurrent.get() shouldBeEqualTo maxLeaders` (== 2)
- **English KDoc**: `CountDownLatch` 핸드쉐이크 정당성 (6T1 해결); `MultithreadingTester.run()` 외부에서 sampling 불가능한 이유
- **검증**: 통과
- **의존**: T-TEST-BASE-1

#### T-T5: SuspendGroupLeaderTest — complexity: high
- **파일**: `.../SuspendGroupLeaderTest.kt`
- **내용**: T4와 동일 패턴, `SuspendedJobTester(coroutines=4)` 사용:
  - `delay(600.milliseconds)` 사용
  - `enteredLatch`/`releaseLatch`는 `java.util.concurrent.CountDownLatch` (동작 동일)
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
  - `@Test runIfLeaderGroup extension admits leader`: `curator.runIfLeaderGroup(randomLockName(), basePath = "/test/ext-group", groupOptions = LeaderGroupElectionOptions(2, waitTime = 500.ms)) { "done" } shouldBeEqualTo "done"`
  - (선택 5th) `@Test suspendRunIfLeaderGroup extension`: 위 코루틴 버전
- **English KDoc**: T6은 라이브러리 extension API 매끄러움 검증
- **검증**: 통과
- **의존**: T-TEST-BASE-1
- **참고**: 라이브러리 실제 시그니처는 `inline fun <T> CuratorFramework.runIfLeader(lockName, basePath, options, action)` 형태. 구현자는 컴파일러 안내에 따라 인자 이름/순서 맞춤.

#### T-T7: R16AutoExtendIgnoredTest — complexity: high
- **파일**: `.../R16AutoExtendIgnoredTest.kt`
- **내용**: 스펙 §5.5 T7 Logback ListAppender 패턴
  - extends `AbstractLeaderZookeeperTest`
  - field: `private lateinit var appender: ListAppender<ILoggingEvent>`
  - `@BeforeEach setupLogCapture()`: `LoggerFactory.getLogger("io.bluetape4k.leader.zookeeper") as Logger` → `ListAppender<ILoggingEvent>().also { it.start() }` 첨부
  - `@AfterEach teardownLogCapture()`: appender.stop() + detach (테스트 격리)
  - `@Test autoExtend option is silently ignored with WARN log`:
    - `val elector = ZooKeeperLeaderElector(curator, "/test/r16", LeaderElectionOptions(autoExtend = true, waitTime = 500.milliseconds))`
    - `val result = elector.runIfLeader(randomLockName()) { "r16-done" }`
    - `result shouldBeEqualTo "r16-done"`
    - `val warnEvents = appender.list.filter { it.level == Level.WARN && "autoExtend" in it.formattedMessage }`
    - `warnEvents.shouldNotBeEmpty()`
- **English KDoc**: T7은 R16 계약의 관찰 가능한 시연; ListAppender 패턴 이유 (Logback 의존 — Spring Boot test가 제공)
- **검증**: 통과; `LeaderElectionOptions.autoExtend` 시그니처 라이브러리 확인 후 인자 이름 조정
- **의존**: T-TEST-BASE-1
- **참고**: 만약 라이브러리가 INFO 레벨로 로그 시 자동 차감을 알린다면 spec과 합의된 WARN 레벨 어설션 실패 가능 — 구현 시 실제 라이브러리 로그 레벨 확인 후 spec 업데이트 권고 (advisor consult)

#### T-T8: SessionLossFailoverTest — complexity: high
- **파일**: `.../SessionLossFailoverTest.kt`
- **내용**: 스펙 §5.4 T8 격리 컨테이너 패턴
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`
  - **격리된 ZK 인스턴스 사용** — `ZooKeeperServer.Launcher.zookeeper` 절대 사용 금지
  - fields:
    - `private lateinit var isolatedZk: ZooKeeperServer`
    - `private lateinit var clientA: CuratorFramework`
    - `private lateinit var clientB: CuratorFramework`
  - `@BeforeAll fun startIsolatedZk()`: `ZooKeeperServer().also { it.start() }`; 2개 클라이언트 생성 (`ZooKeeperServer.Launcher.getCuratorFramework(isolatedZk)`) → 각각 `start()` + `blockUntilConnected(10, SECONDS)`
  - `@AfterAll fun stopIsolatedZk()`: `runCatching { clientA.close() }`; `runCatching { clientB.close() }`; `runCatching { isolatedZk.stop() }`
  - `@Test session expiry causes leadership failover`:
    1. workerA: `clientA.runIfLeader(lockName, basePath = "/test/t8") { Thread.sleep(...) }` 또는 명시적 `InterProcessMutex` 획득
    2. `isolatedZk.stop()` — 세션 만료 시뮬레이션
    3. 일정 대기 (예: `Awaitility`로 ConnectionState.LOST 관측)
    4. `isolatedZk.start()` — 재기동
    5. workerB: `clientB.runIfLeader(lockName, ...) { "B-acquired" }` 성공 (Ephemeral 사라짐 → 경쟁자 자동 선출)
    6. assert: workerB가 leadership 획득
  - **타이밍**: ZK session 만료는 sessionTimeoutMs 기반 — 테스트용 `sessionTimeoutMs` 짧게 설정 (예: 3초) + `Awaitility.atMost(15.seconds)` 대기
- **English KDoc**: T8 isolation 정당성 (싱글턴 파괴 방지); R16 시연 의도
- **검증**: 통과; 타이밍 flake 시 Awaitility/timeout 조정
- **의존**: T-TEST-BASE-1 (코드는 extends하지만 companion curator는 사용 안 함)
- **참고**: 시간 정밀도 높음 — `@Tag("smoke")` 부여 후 `excludeTags("smoke")`로 기본 빌드에서 제외할지 결정 (DoD에서 "T8 통과 필수"이므로 기본 포함; CI가 flaky하면 spec 업데이트 후 smoke 처리)

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
- **검증**: `git diff --check`, README 컨텐츠 영문 일관성 (CLAUDE.md 정책)
- **의존**: T-MOD-1 (모듈 디렉터리 존재)

#### T-DOC-2: README.ko.md (Korean) — complexity: low
- **파일**: `leader/leader-zookeeper/README.ko.md`
- **내용**: T-DOC-1 한국어 번역본 (CLAUDE.md README locale 정책 — 멀티링구얼 허용)
  - 동일 구조, 동일 이미지 자산 참조
  - R16 callout, side-by-side 코드 예제, ACL/TLS 권장사항 모두 포함
- **검증**: README.md와 섹션 1:1 대응 확인
- **의존**: T-DOC-1

### Phase 5 — 검증 (DoD)

#### T-VRF-1: 전체 테스트 실행 + DoD 체크 — complexity: medium
- **명령**:
  ```bash
  ./gradlew :leader:leader-zookeeper:compileKotlin
  ./gradlew :leader:leader-zookeeper:test
  ```
- **DoD 통과 기준** (스펙 §7):
  - [ ] T0~T8 9개 테스트 모두 통과
  - [ ] `compileKotlin` 0 errors, 0 unresolved deprecation
  - [ ] IDE diagnostics 0 errors (해당 시 `ide_diagnostics` 결과 첨부)
  - [ ] HIGH/CRITICAL code review 0 (`/oh-my-claudecode:code-reviewer` 실행)
  - [ ] README.md + README.ko.md (R16 callout, side-by-side, ACL/TLS 포함)
  - [ ] English KDoc on all public types
  - [ ] T4/T5 CountDownLatch 핸드쉐이크 + `peakConcurrent` 검증
  - [ ] T7 Logback ListAppender WARN 캡처
  - [ ] T8 세션 만료 시나리오 통과
  - [ ] `@Scheduled` wrapper CancellationException 재throw 적용 (SVC-2, SVC-4)
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
| 23 | T-T8 | high | 격리 ZK 컨테이너, 세션 만료 |
| 24 | T-DOC-1 | medium | README.md (영) |
| 25 | T-DOC-2 | low | README.ko.md |
| 26 | T-VRF-1 | medium | DoD 체크 |
| 27 | T-VRF-2 | medium | code-reviewer HIGH/CRITICAL 0 |

---

## 6. 위험 요소 및 미해결 항목

| ID | 위험 | 대응 |
|---|---|---|
| R-1 | T7 WARN 로그 캡처 실패 (실제 로그 레벨이 INFO일 가능성) | 구현 후 라이브러리 실제 로그 확인; 다를 경우 spec 업데이트 advisor 호출 |
| R-2 | T6 라이브러리 extension 함수 시그니처 변경 (basePath default) | 컴파일 에러 시 라이브러리 소스 조회 후 인자 명시 |
| R-3 | T8 세션 만료 타이밍 flake (CI 환경) | `sessionTimeoutMs=3000`, `Awaitility.atMost(15.seconds)` 시작값; flake 시 `@Tag("smoke")` 부여 검토 |
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
