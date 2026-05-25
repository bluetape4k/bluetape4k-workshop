# leader/leader-zookeeper 워크숍 모듈 설계 스펙

> 작성일: 2026-05-25  
> 작업 브랜치: feat/leader-zookeeper  
> 관련 이슈: #10 (bluetape4k-leader 예제 제작 Epic) — PR-B

---

## 1. 요약

Apache Curator 기반 `bluetape4k-leader-zookeeper` 라이브러리를 활용한 분산 리더 선출 워크숍 예제 모듈을 신규 생성한다.
기존 `leader/leader-election/` 모듈 (Lettuce/Redis 기반)과 동일한 Spring Boot 앱 구조를 따르면서,
ZooKeeper 고유 특성(R16: 세션 기반, TTL 없음)을 명시적으로 시연하는 교육용 코드를 제공한다.

---

## 2. 배경 및 제약 사항

### 2.1 라이브러리 가용성
- `bluetape4k-leader-zookeeper:0.2.1` Maven Central 게시 확인 ✅
- `bluetape4k-dependencies 1.1.3` BOM 포함 → 버전은 BOM 관리, 카탈로그에 버전 핀 금지
- 워크숍 `gradle/libs.versions.toml`에 `bluetape4k-leader-zookeeper` alias 미등록 → **추가 필요**

### 2.2 R16 — ZooKeeper has no TTL
ZooKeeper는 세션 기반 Ephemeral Node를 사용한다. TTL 개념이 없으므로:
- `LeaderElectionOptions.autoExtend = true` 설정 시 WARN 로그 후 무시
- `LeaderLeaseAutoExtender`는 항상 `enabled=false`로 강제 실행
- 모듈 설계에서 `leaseTime`/`autoExtend` 프로퍼티를 **의도적으로 노출하지 않음**

### 2.3 의존 스택
| 항목 | 값 |
|---|---|
| Kotlin | 2.3.21 |
| Java | 25 (ZGC, `--enable-preview`) |
| Spring Boot | 4.0.x |
| bluetape4k-leader-zookeeper | 0.2.1 (BOM 관리) |
| Apache Curator | 5.9.0 (transitive via leader-zookeeper) |
| ZooKeeper Docker image | zookeeper:3.9 |
| Testcontainer launcher | `ZooKeeperServer.Launcher.zookeeper` |

---

## 3. 설계 접근 방식

### 3.1 비교 검토 (Brainstorming)

**Option A: 단일 통합 서비스 (거절)**  
모든 Elector를 하나의 서비스에서 노출하는 패턴.  
- 장점: 파일 수 감소  
- 단점: 각 Elector 타입별 API 차이(`suspend` vs blocking, group vs single)가 희석되어 교육 가치 손실. 거절.

**Option B: 4개 전용 서비스 (채택)**  
`ZooKeeperLeaderElector`, `ZooKeeperSuspendLeaderElector`, `ZooKeeperLeaderGroupElector`, `ZooKeeperSuspendLeaderGroupElector` 각각에 전용 서비스를 배치.  
- 장점: API 타입별 특성을 명확히 시연; 각 서비스가 1가지 패턴에 집중  
- 단점: 4개 서비스 파일 → 보일러플레이트 증가  
- 결론: 워크숍 목적(展示)에 최적. **채택.**

**Option C: ListeningLeaderElector 포함 (거절)**  
`ListeningLeaderElector` 래퍼를 ZK 모듈에 포함.  
- 라이브러리가 ZK 리스너 래퍼를 제공하지 않음. `leader-election`(Redis)에서만 가능. 거절. README에서 크로스링크만 제공.

### 3.2 설계 위험 요소

| 위험 | 설명 | 대응 |
|---|---|---|
| Curator thread-owner constraint | InterProcessMutex는 acquire/release가 **같은 스레드**여야 함 | `ZooKeeperSuspendLeaderElector`는 per-call single-thread dispatcher 사용 (라이브러리 내부에서 처리) |
| 동시성 테스트 CuratorFramework 공유 | **InterProcessMutex는 Thread ID 기반 소유권** (ZK session ID 기반 아님). 따라서 하나의 CuratorFramework를 다른 스레드/코루틴에서 공유해도 상호 경쟁 정상 작동. 라이브러리 자체 `AbstractZooKeeperLeaderTest`도 단일 `curator` 공유 확인. | 테스트 기반 클래스에서 `companion object val curator by lazy { ... }` 단일 인스턴스 사용 (불필요한 per-worker 연결 생성 금지) |
| blockUntilConnected 실패 시 리소스 누수 | `curator.start()` 호출 후 `blockUntilConnected` 실패 시 JVM에 background thread가 잔류할 수 있음 | timeout 전에 `client.close()` 명시적 호출 후 예외 throw |
| ZK 세션 만료 (R16 핵심) | 세션 만료 시 Ephemeral Node가 사라지고 경쟁자가 자동 선출됨. `SUSPENDED`/`LOST` 상태에서 진행 중인 작업은 stale leadership 상태가 될 수 있음 | `ConnectionStateListener` 등록으로 SUSPENDED/LOST 상태 로깅 (교육 목적); T8 세션 손실 시나리오 테스트로 시연 |
| ZK 연결 타임아웃 | 테스트 컨테이너 시작 지연 | `blockUntilConnected(10, SECONDS)` 사용; `ZooKeeperServer.Launcher` 싱글턴이 JVM 시작 시 한 번만 초기화 |
| R16 autoExtend 노출 | 프로퍼티에 노출 시 사용자 혼란 | Properties 클래스에 필드 미선언; README R16 callout 명시 |

---

## 4. 모듈 구조

```
leader/leader-zookeeper/
├── build.gradle.kts
├── README.md
├── README.ko.md
└── src/
    ├── main/
    │   ├── kotlin/io/bluetape4k/workshop/leader/zookeeper/
    │   │   ├── LeaderZookeeperApp.kt
    │   │   ├── config/
    │   │   │   ├── LeaderZookeeperProperties.kt
    │   │   │   └── LeaderZookeeperConfig.kt
    │   │   └── service/
    │   │       ├── BlockingLeaderService.kt
    │   │       ├── SuspendLeaderZkService.kt
    │   │       ├── GroupLeaderService.kt
    │   │       └── SuspendGroupLeaderService.kt
    │   └── resources/
    │       ├── application.yml
    │       └── logback-spring.xml
    └── test/
        ├── kotlin/io/bluetape4k/workshop/leader/zookeeper/
        │   ├── AbstractLeaderZookeeperTest.kt
        │   ├── LeaderZookeeperContextTest.kt         (T0 - Spring context)
        │   ├── BlockingSingleLeaderTest.kt            (T1 - runIfLeader + runAsyncIfLeader)
        │   ├── ConcurrentBlockingLeaderTest.kt        (T2)
        │   ├── SuspendSingleLeaderTest.kt             (T3)
        │   ├── GroupLeaderTest.kt                     (T4)
        │   ├── SuspendGroupLeaderTest.kt              (T5)
        │   ├── ExtensionFunctionTest.kt               (T6)
        │   ├── R16AutoExtendIgnoredTest.kt            (T7)
        │   └── SessionLossFailoverTest.kt             (T8 - R16 session expiry demo)
        └── resources/
            ├── junit-platform.properties
            └── logback-test.xml
```

---

## 5. 컴포넌트 상세 설계

### 5.1 LeaderZookeeperProperties

```kotlin
/**
 * Configuration properties for the ZooKeeper-based leader election workshop module.
 *
 * ## Behavior / Contract (R16 — ZooKeeper has no TTL)
 * ZooKeeper uses session-bound ephemeral znodes for leader election.
 * Unlike Redis-based election:
 * - There is no lease TTL; leadership is held until the ZooKeeper session expires or is explicitly closed.
 * - `leaseTime` and `autoExtend` are intentionally absent from this class.
 *   Setting `LeaderElectionOptions.autoExtend = true` emits a WARN log and is silently ignored.
 * - Session expiry (e.g., due to network partition or process crash) automatically removes
 *   the ephemeral election node, triggering re-election among competing candidates.
 * - Tune [ZooKeeperConfig.sessionTimeoutMs] relative to your job interval for acceptable failover latency.
 *
 * ## Production Considerations
 * - Default [ZooKeeperConfig.connectString] is `localhost:2181` (development only).
 * - Default [ZooKeeperConfig.sessionTimeoutMs] is 60 000 ms — the failover window on hard crash.
 * - ACL and TLS are omitted in this workshop; production deployments should configure an ACLProvider
 *   and SASL/TLS via `CuratorFrameworkFactory.builder()`.
 */
@ConfigurationProperties(prefix = "leader.zookeeper")
data class LeaderZookeeperProperties(
    val zookeeper: ZooKeeperConfig = ZooKeeperConfig(),
    val basePath: String = "/workshop/leader-zookeeper",
    val waitTime: java.time.Duration = java.time.Duration.ofSeconds(2),
    val groupMaxLeaders: Int = 2,
    val jobFixedDelay: String = "PT10S",
    val suspendJobFixedDelay: String = "PT12S",
    val groupJobFixedDelay: String = "PT15S",
    val suspendGroupJobFixedDelay: String = "PT18S",
    // NOTE: leaseTime / autoExtend intentionally absent — R16: ZooKeeper has no TTL
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
    init {
        basePath.requireNotBlank("basePath")
        groupMaxLeaders.requirePositiveNumber("groupMaxLeaders")
    }
    
    data class ZooKeeperConfig(
        val connectString: String = "localhost:2181",
        val sessionTimeoutMs: Int = 60_000,
        val connectionTimeoutMs: Int = 15_000,
        val blockUntilConnectedSeconds: Long = 10,
    ) : Serializable {
        companion object { private const val serialVersionUID = 1L }
        init { connectString.requireNotBlank("connectString") }
    }
}
```

> **R16**: `leaseTime`, `autoExtend` 필드 없음 — ZK는 TTL 없는 세션 기반.  
> KDoc의 `## Behavior / Contract` 섹션이 R16 계약을 API 사용자에게 명시함.

### 5.2 LeaderZookeeperConfig

CuratorFramework 빈의 destroy method는 `"close"` (CuratorFramework implements Closeable).

**주의 (C2)**: `start()` 호출 후 `blockUntilConnected` 실패 시 반드시 `close()` 명시적 호출 후 throw.
Spring `destroyMethod`는 빈 등록 성공 시에만 호출되므로 팩토리 내에서 실패 경로를 직접 처리해야 함.

```kotlin
@Bean(destroyMethod = "close")
fun curatorFramework(props: LeaderZookeeperProperties): CuratorFramework {
    val cfg = props.zookeeper
    val client = CuratorFrameworkFactory.newClient(
        cfg.connectString,
        cfg.sessionTimeoutMs,
        cfg.connectionTimeoutMs,
        ExponentialBackoffRetry(1000, 3)
    )
    client.start()
    // Add a ConnectionStateListener to observe SUSPENDED / LOST for operational awareness
    client.connectionStateListenable.addListener { _, newState ->
        when (newState) {
            ConnectionState.SUSPENDED -> log.warn { "ZooKeeper connection SUSPENDED — leadership uncertain" }
            ConnectionState.LOST -> log.error { "ZooKeeper session LOST — ephemeral nodes removed; re-election will occur" }
            ConnectionState.RECONNECTED -> log.info { "ZooKeeper session RECONNECTED" }
            else -> log.debug { "ZooKeeper connection state: $newState" }
        }
    }
    if (!client.blockUntilConnected(cfg.blockUntilConnectedSeconds, TimeUnit.SECONDS)) {
        client.close()  // explicit close to prevent background thread leak
        error("ZooKeeper connection timeout after ${cfg.blockUntilConnectedSeconds}s (connectString=${cfg.connectString}). " +
              "Check that ZooKeeper is running and accessible.")
    }
    return client
    // NOTE: OPEN_ACL_UNSAFE default — production deployments should use
    // CuratorFrameworkFactory.builder().aclProvider(DigestACLProvider(...)) for access control.
}
```
```

basePath 분리:
- single: `${basePath}/single`
- single-suspend: `${basePath}/single-suspend`
- group: `${basePath}/group`
- group-suspend: `${basePath}/group-suspend`

### 5.3 Services

각 서비스 공통 패턴:
- `companion object : KLogging()` (coroutine 서비스는 `KLoggingChannel()`)
- `executionCount: AtomicInteger` for test verification
- `@Scheduled(fixedDelayString = "...")` entry point
- runBlocking은 coroutine 서비스의 `@Scheduled` 진입점에서만 사용

#### BlockingLeaderService
```kotlin
@Service
class BlockingLeaderService(
    private val elector: ZooKeeperLeaderElector,
    private val props: LeaderZookeeperProperties,
) {
    companion object : KLogging()
    val executionCount = AtomicInteger()
    
    fun runLeaderWork(lockName: String = "workshop:blocking-job"): String? =
        elector.runIfLeader(lockName) {
            executionCount.incrementAndGet()
            log.debug { "[LEADER] BlockingLeaderService running work" }
            "done"
        }.also {
            if (it == null) log.debug { "[SKIPPED] BlockingLeaderService not the elected leader" }
        }
    
    @Scheduled(fixedDelayString = "\${leader.zookeeper.job-fixed-delay:PT10S}")
    fun runScheduled() {
        try {
            runLeaderWork()
        } catch (e: Exception) {
            log.warn(e) { "Scheduled leader work failed" }
        }
    }
}
```

#### SuspendLeaderZkService
- `KLoggingChannel()` companion
- `suspend fun runLeaderWork(lockName: String): String?`
- `@Scheduled` 진입점은 반드시 `CancellationException` 재throw 포함:
```kotlin
@Scheduled(fixedDelayString = "\${leader.zookeeper.suspend-job-fixed-delay:PT12S}")
fun runScheduled() {
    try {
        runBlocking { runLeaderWork() }
    } catch (e: CancellationException) {
        throw e  // MUST rethrow — CLAUDE.md: never swallow CancellationException
    } catch (e: Exception) {
        log.warn(e) { "Scheduled suspend leader work failed" }
    }
}
```

#### GroupLeaderService
- `ZooKeeperLeaderGroupElector` 주입
- `inspectState(lockName): LeaderGroupState` — 테스트에서 `activeCount`/`availableSlots` 검증에 활용
- `@Scheduled` wrapper: `try { runLeaderWork() } catch (e: Exception) { log.warn(e) { "Scheduled group leader work failed" } }`

#### SuspendGroupLeaderService
- `ZooKeeperSuspendLeaderGroupElector` 주입
- suspend version
- `@Scheduled` wrapper: 반드시 `CancellationException` rethrow 포함 (SuspendLeaderZkService와 동일 패턴)

### 5.4 AbstractLeaderZookeeperTest

**설계 근거**: `InterProcessMutex`는 ZK session ID가 아닌 `Thread.currentThread()`를 소유권 키로 사용한다.
따라서 여러 스레드/코루틴이 동일한 `CuratorFramework`를 공유해도 상호 배제가 정상 작동한다.
라이브러리 자체 `AbstractZooKeeperLeaderTest`도 단일 `companion object val curator by lazy {...}` 패턴을 사용함.
→ per-call `newCurator()` 팩토리는 불필요한 ZK 세션/스레드풀 생성이므로 사용 금지.

```kotlin
/**
 * Abstract base for library-lane ZooKeeper leader election tests.
 *
 * Uses a single shared [CuratorFramework] in the companion object (initialized lazily once per JVM).
 * InterProcessMutex ownership is keyed on Thread, not ZooKeeper session, so all workers
 * competing via the same [curator] instance will correctly contend for the lock.
 *
 * Lazy initialization ensures the ZooKeeper container is started before the first test accesses it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractLeaderZookeeperTest {
    companion object : KLogging() {
        val zookeeper: ZooKeeperServer = ZooKeeperServer.Launcher.zookeeper
        
        /** Shared CuratorFramework — safe to share across threads/coroutines (InterProcessMutex is Thread-keyed). */
        val curator: CuratorFramework by lazy {
            ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also {
                it.start()
                it.blockUntilConnected(10, TimeUnit.SECONDS)
                ShutdownQueue.register { it.close() }
            }
        }
    }
    
    // waitTime = 500ms: provides margin for CI timing jitter (200ms was too tight)
    fun newElector(basePath: String = "/test/single") =
        ZooKeeperLeaderElector(curator, basePath, LeaderElectionOptions(waitTime = 500.milliseconds))
    
    fun newSuspendElector(basePath: String = "/test/single-suspend") =
        ZooKeeperSuspendLeaderElector(curator, basePath, LeaderElectionOptions(waitTime = 500.milliseconds))
    
    fun newGroupElector(maxLeaders: Int = 2, basePath: String = "/test/group") =
        ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders, waitTime = 500.milliseconds), basePath)
    
    fun newSuspendGroupElector(maxLeaders: Int = 2, basePath: String = "/test/group-suspend") =
        ZooKeeperSuspendLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders, waitTime = 500.milliseconds), basePath)
    
    fun randomLockName(prefix: String = "t") = "$prefix:${UUID.randomUUID()}"
}
```

**T8 `SessionLossFailoverTest`용 독립 curator**: 세션 만료 시나리오(T8)에서는 ZK 컨테이너를 일시 중단하므로
별도의 독립 `CuratorFramework` 인스턴스를 사용해야 한다. T8 테스트 클래스에서 `newIndependentCurator()` 헬퍼 제공:
```kotlin
// Used only in T8 — T8 needs an isolated session to simulate expiry
fun newIndependentCurator(): CuratorFramework =
    ZooKeeperServer.Launcher.getCuratorFramework(zookeeper).also {
        it.start()
        it.blockUntilConnected(10, TimeUnit.SECONDS)
        // Caller is responsible for close() in @AfterEach
    }
```

### 5.5 테스트 커버리지 매트릭스

| ID | 테스트 클래스 | 커버 사항 | 핵심 assertion |
|---|---|---|---|
| T0 | LeaderZookeeperContextTest | Spring 빈 와이어링 | 4 elector beans + 4 service beans not null |
| T1 | BlockingSingleLeaderTest | `runIfLeader` 단일 실행 + `runAsyncIfLeader` CompletableFuture | `runIfLeader` result == "done"; `runAsyncIfLeader` future.join() == 42 |
| T2 | ConcurrentBlockingLeaderTest | 8-worker `MultithreadingTester`, shared curator, peakConcurrent ≤ 1 | `peakConcurrent.get() shouldBeLessOrEqualTo 1`; `executed.get() > 0` |
| T3 | SuspendSingleLeaderTest | `SuspendedJobTester` 8-coroutine 경쟁, shared curator | exactly 1 winner per lock round; `peakConcurrent ≤ 1` |
| T4 | GroupLeaderTest | `maxLeaders=2`, `MultithreadingTester` 4-worker, shared curator | action body calls `Thread.sleep(600ms)` (> waitTime=500ms) to hold slot; `CountDownLatch(maxLeaders)` entered before sampling; `peakConcurrent.get() shouldBeEqualTo 2` |
| T5 | SuspendGroupLeaderTest | `SuspendedJobTester` 4-coroutine group | same as T4 with `delay(600.milliseconds)`; `CountDownLatch` + `peakConcurrent == 2` |
| T6 | ExtensionFunctionTest | `CuratorFramework` extension functions: `leaderElector(basePath)`, `suspendLeaderElector(basePath)`, `leaderGroupElector(basePath, options)`, `suspendLeaderGroupElector(basePath, options)` | each extension returns non-null elector; `runIfLeader` / `suspend runIfLeader` returns result successfully |
| T7 | R16AutoExtendIgnoredTest | `LeaderElectionOptions(autoExtend=true)` 전달 시 WARN 발생 + action 정상 실행 | Logback `ListAppender<ILoggingEvent>` attached to `io.bluetape4k.leader.zookeeper` logger; assert `appender.list.any { it.level == Level.WARN && "autoExtend" in it.message }`; action result == "r16-done" |
| T8 | SessionLossFailoverTest | R16 세션 만료 시 Ephemeral Node 소멸 + 경쟁자 자동 선출 | workerA acquires leadership via `newIndependentCurator()`; ZK Testcontainer restarted (or session forcibly closed); workerB (separate curator) acquires within `sessionTimeoutMs + waitTime`; assert workerB result non-null |

**T4/T5 CountDownLatch 패턴 (6T1 해결)**:
```kotlin
// T4 GroupLeaderTest 핵심 패턴
val enteredLatch = CountDownLatch(maxLeaders)  // 2 workers entered simultaneously
val releaseLatch = CountDownLatch(1)            // signal to release all
val peakConcurrent = AtomicInteger(0)
val current = AtomicInteger(0)

MultithreadingTester().workers(4).add {
    groupElector.runIfLeader(lockName) {
        val c = current.incrementAndGet()
        peakConcurrent.updateAndGet { max(it, c) }
        enteredLatch.countDown()
        releaseLatch.await(3, TimeUnit.SECONDS)  // hold slot while sampling
        current.decrementAndGet()
    }
}
// Sampling happens INSIDE the action, not after MultithreadingTester.run() completes.
// Assert: peakConcurrent.get() shouldBeEqualTo maxLeaders
```

**T7 Logback ListAppender 패턴 (D4/6T2 해결)**:
```kotlin
// T7 R16AutoExtendIgnoredTest
@BeforeEach
fun setupLogCapture() {
    val logger = LoggerFactory.getLogger("io.bluetape4k.leader.zookeeper") as ch.qos.logback.classic.Logger
    appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.addAppender(appender)
}
@AfterEach fun teardownLogCapture() { appender.stop() }

@Test fun `autoExtend option is silently ignored with WARN log`() {
    val elector = ZooKeeperLeaderElector(
        curator, "/test/r16",
        LeaderElectionOptions(autoExtend = true, waitTime = 500.milliseconds)
    )
    val result = elector.runIfLeader(randomLockName()) { "r16-done" }
    result shouldBeEqualTo "r16-done"
    val warnEvents = appender.list.filter { it.level == Level.WARN && "autoExtend" in it.formattedMessage }
    warnEvents shouldHaveSize 1  // exactly 1 WARN emitted on construction/first use
}
```

---

## 6. 카탈로그 변경사항

`gradle/libs.versions.toml` line 208 이후에 추가:
```toml
bluetape4k-leader-zookeeper = { module = "io.github.bluetape4k.leader:bluetape4k-leader-zookeeper" }
```

버전은 `bluetape4k-dependencies` BOM 1.1.3이 관리. 별도 버전 핀 금지.

---

## 7. DoD (Definition of Done)

- [ ] 모든 9개 테스트 (T0~T8) 통과
- [ ] `./gradlew :leader:leader-zookeeper:compileKotlin` 오류 없음
- [ ] IDE diagnostics 0 errors
- [ ] Step 6-R Tier 1-6 code review 완료; HIGH/CRITICAL 0
- [ ] README.md (영어) + README.ko.md 작성 완료
  - [ ] `## R16 — ZooKeeper has no TTL` callout 포함
  - [ ] Single-leader (`runIfLeader`) 와 group-leader (`maxLeaders=2`) side-by-side 코드 예제 포함
  - [ ] ACL/TLS `## Production Considerations` 섹션 포함
- [ ] English KDoc on all public classes/services/config
  - [ ] `LeaderZookeeperProperties`: R16 `## Behavior / Contract` KDoc ✅ (§5.1에 설계됨)
  - [ ] `LeaderZookeeperConfig.curatorFramework`: ConnectionStateListener + ACL 주석 ✅ (§5.2에 설계됨)
  - [ ] 4개 service 클래스 English KDoc
- [ ] T4/T5 `CountDownLatch` 핸드쉐이크 패턴으로 `peakConcurrent` 검증 ✅ (§5.5에 설계됨)
- [ ] T7 Logback `ListAppender` WARN 로그 캡처 ✅ (§5.5에 설계됨)
- [ ] T8 세션 만료 → Ephemeral Node 소멸 → 경쟁자 선출 시나리오 시연 ✅ (§5.5에 설계됨)
- [ ] `@Scheduled` wrapper에 `CancellationException` rethrow ✅ (§5.3에 설계됨)

---

## Appendix: 리뷰 이터레이션 로그

| Round | Reviewer | CRITICAL/HIGH/MEDIUM/LOW | 적용 Commit |
|---|---|---|---|
| 1 | 6-tier advisor | 0/2/4/2 | - |
| 1 | User/Caller perspective | 0/3/2/0 | - |
| 1 | Developer perspective (a2babd54) | 0/2/3/0 | - |
| 1 | Developer perspective (a4a5456) | 0/2/3/0 → CRITICAL C1/C2 발굴 | - |
| 1 | Security perspective | 0/0/2/1 | - |
| 1 | Ops/SRE perspective | 0/3/3/0 | - |
| 1 | Phase-2 Opus Critic | 통합: 0/10/8/1 → disposition 후 0/9/8/1 | - |
| 1 | 통합 적용 후 최종 | D1 reject(라이브러리 증거), 나머지 HIGH 모두 spec에 반영 | (pending commit) |

**Round 1 주요 발견 및 해결:**

| ID | 심각도 | 내용 | 해결 방법 |
|---|---|---|---|
| D1 | HIGH → **REJECT** | per-worker 독립 CuratorFramework 필요 주장 | 라이브러리 소스 확인: InterProcessMutex는 Thread-keyed. 단일 shared curator 사용으로 수정 (§5.4) |
| C1 | CRITICAL | `newCurator()` per-call 팩토리가 JVM 종료까지 연결 누수 | §5.4: companion object `curator by lazy` 패턴으로 교체 |
| C2 | CRITICAL | `blockUntilConnected` 실패 시 `start()`된 client 누수 | §5.2: `client.close()` 명시적 호출 후 throw |
| D2 | HIGH | Korean log messages (CLAUDE.md 위반) | §5.3: 모든 로그 English로 수정 |
| O6 | HIGH | `@Scheduled` wrapper에서 CancellationException 삼킴 | §5.3: SuspendLeaderZkService + SuspendGroupLeaderService에 rethrow 추가 |
| U1 | HIGH | LeaderZookeeperProperties R16 KDoc 없음 | §5.1: `## Behavior / Contract` KDoc 추가 |
| O2 | HIGH→MEDIUM | ConnectionStateListener 없음 | §5.2: SUSPENDED/LOST/RECONNECTED 로깅 리스너 추가 (교육 목적) |
| 6T1 | HIGH | T4/T5 activeCount peak 검증 race condition | §5.5: CountDownLatch 핸드쉐이크 패턴 명시 |
| D4/6T2 | HIGH | T7 WARN log 캡처 메커니즘 미정의 | §5.5: Logback ListAppender 패턴 명시 |
| O1 | HIGH | 세션 만료 시나리오 미테스트 | §5.5: T8 `SessionLossFailoverTest` 추가 |
| U4/T1 | HIGH | `runAsyncIfLeader` 미커버 | §5.5 T1: `runAsyncIfLeader` CompletableFuture 검증 추가 |
| 6T6 | HIGH | T6 extension 함수 범위 미정의 | §5.5 T6: 4개 extension 함수 명시적 열거 |
| U2 | HIGH | README side-by-side 예제 미정의 | §7 DoD: README 요구사항에 명시 |
