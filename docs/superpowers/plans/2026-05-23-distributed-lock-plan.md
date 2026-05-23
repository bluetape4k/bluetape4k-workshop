# Plan: Issue #101 — Distributed / Fenced Lock Workshop

**날짜**: 2026-05-23
**브랜치**: feat/issue-101-distributed-lock
**Spec**: docs/superpowers/specs/2026-05-23-distributed-lock-design.md
**모듈 디렉토리**: `redis/distributed-lock`
**Gradle 프로젝트**: `:redis-distributed-lock` (settings.gradle.kts line 37 의 `includeModules("redis", false, true)` 가 자동 등록)
**Base 패키지**: `io.bluetape4k.workshop.lock`

---

## Phase 개요

| Phase | 내용 | 태스크 |
|---|---|---|
| 1 | Module scaffold (build, app entry, resources) | T1–T6 |
| 2 | Domain layer (Inventory, DeductionResult, InventoryStore) | T7–T9 |
| 3 | Fenced guard (FencedResource, FencedResources) | T10–T11 |
| 4 | Core services (Unsafe, Locked, Fenced, SuspendingFenced) | T12–T15 |
| 5 | Tests (Abstract base + 6 test classes) | T16–T22 |
| 6 | Documentation (README en/ko + KDoc 정리) | T23–T25 |
| 7 | CI integration (smoke-validate.sh) | T26 |

---

## Phase 1: Module Scaffold

### T1 — Create module `build.gradle.kts`

- **complexity**: medium
- **files**:
  - `build.gradle.kts`
- **notes**:
  - `plugins { alias(libs.plugins.kotlin.spring); alias(libs.plugins.spring.boot) }`.
  - `springBoot { mainClass.set("io.bluetape4k.workshop.lock.DistributedLockAppKt") }`.
  - `configurations { testImplementation.get().extendsFrom(compileOnly.get(), runtimeOnly.get()) }`.
  - Java toolchain 설정: `java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }` (sibling 모듈 패턴).
  - Dependencies (lean — no grpc/protobuf/fory/kryo/cache like redisson-examples):
    - `implementation(libs.bluetape4k.redis)`
    - `implementation(libs.redisson.lib)` + `implementation(libs.redisson.spring.boot.starter)`
    - `implementation(libs.bluetape4k.coroutines)` + `implementation(libs.kotlinx.coroutines.core.lib)`
    - `implementation(libs.bluetape4k.logging)`
    - `implementation(libs.spring.boot.autoconfigure.lib)` + `implementation(libs.spring.boot.starter.actuator)`
    - Tests: `testImplementation(project(":shared"))`, `libs.bluetape4k.junit5`, `libs.bluetape4k.testcontainers`, `libs.bluetape4k.assertions`, `libs.kotlinx.coroutines.test.lib`
    - `testImplementation(libs.spring.boot.starter.test) { exclude junit, junit-vintage-engine, mockito-core }`
  - **`annotationProcessor` 없음** — 워크샵 앱은 자동설정 메타데이터 생성 불필요 (R9).
  - settings.gradle.kts 는 **수정하지 않음** (이미 `includeModules("redis", false, true)` 가 디렉토리 추가 시 자동 등록).

### T2 — `DistributedLockApp.kt` (Spring Boot entry)

- **complexity**: low
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/DistributedLockApp.kt`
- **notes**:
  - `@SpringBootApplication` + top-level `fun main(args: Array<String>) { runApplication<DistributedLockApp>(*args) }`.
  - Package `io.bluetape4k.workshop.lock`.
  - English KDoc.

### T3 — Main `application.yaml`

- **complexity**: low
- **files**:
  - `src/main/resources/application.yaml`
- **notes**:
  - `spring.application.name: distributed-lock-workshop`.
  - `spring.main.web-application-type: none` — 웹 서버 불필요 (워크샵 데모 앱, R7).
  - `spring.redisson.config: classpath:redisson.yaml` (Redisson Spring Boot Starter convention).
  - Actuator endpoints exposure minimal (health).

### T4 — `redisson.yaml`

- **complexity**: low
- **files**:
  - `src/main/resources/redisson.yaml`
- **notes**:
  - `singleServerConfig.address: "redis://${REDIS_HOST:127.0.0.1}:${REDIS_PORT:6379}"`.
  - 테스트 시 `AbstractDistributedLockTest` 에서 Testcontainers 주소로 override 하므로 dev 기본값만 둠.

### T5 — Test `application.yaml` + `junit-platform.properties`

- **complexity**: low
- **files**:
  - `src/test/resources/application.yaml`
  - `src/test/resources/junit-platform.properties`
- **notes**:
  - `application.yaml`:
    ```yaml
    spring:
      application:
        name: distributed-lock-test
      main:
        web-application-type: none   # 테스트에서도 웹 서버 불필요 (R7)
    ```
  - `junit-platform.properties`:
    ```
    junit.jupiter.extensions.autodetection.enabled=true
    junit.jupiter.testinstance.lifecycle.default=per_class
    junit.jupiter.execution.parallel.enabled=false
    junit.jupiter.execution.parallel.mode.default=same_thread
    junit.jupiter.execution.parallel.mode.classes.default=concurrent
    junit.jupiter.execution.exclude.tags=smoke
    ```
  - `exclude.tags=smoke` 가 핵심 — `LockFailureTest`, `FencedStaleHolderTest` 를 기본 CI에서 제외.

### T6 — Test `logback-test.xml`

- **complexity**: low
- **files**:
  - `src/test/resources/logback-test.xml`
- **notes**:
  - `redis/cluster-demo` 의 logback-test.xml 을 베이스로 사용.
  - `<logger name="io.bluetape4k.workshop.lock" level="DEBUG"/>` 추가.
  - Root level INFO, console appender DEBUG filter.

---

## Phase 2: Domain Layer

### T7 — `Inventory` data class

- **complexity**: low
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/domain/Inventory.kt`
- **notes**:
  - `data class Inventory(val id: Long, val name: String, val initialStock: Int) : Serializable`.
  - `init {}` 에서 `id.requirePositiveNumber("id")`, `name.requireNotBlank("name")`, `initialStock.requireZeroOrPositiveNumber("initialStock")`.
  - `companion object : KLogging() { private const val serialVersionUID = 1L }`.
  - 영문 KDoc + `## Behavior / Contract` 섹션.

### T8 — `DeductionResult` sealed hierarchy

- **complexity**: medium
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/domain/DeductionResult.kt`
- **notes**:
  - `sealed interface DeductionResult` + 4 nested data classes:
    - `Success(val remaining: Int, val token: Long? = null)`
    - `InsufficientStock(val requested: Int, val available: Int)`
    - `Rejected(val token: Long)`
    - `LockNotAcquired(val lockName: String)`
  - **각 서브타입은 별도** `companion object { private const val serialVersionUID = 1L }` (CLAUDE.md 직렬화 규칙).
  - 모든 서브타입 `: Serializable` 명시.
  - 영문 KDoc — 각 변형의 의미 명확히 설명 (특히 `token=null` 은 non-fenced 경로용).

### T9 — `InventoryStore` (in-memory)

- **complexity**: medium
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/domain/InventoryStore.kt`
- **notes**:
  - **`@Component` 없음** — 테스트에서 직접 생성, Spring DI 제외 (R4).
  - Internal state: `private val store = ConcurrentHashMap<Long, AtomicInteger>()`.
  - API:
    - `fun register(inventory: Inventory)` — 재등록 시 초기값으로 리셋.
    - `fun currentStock(id: Long): Int` — 미등록 ID 시 `IllegalArgumentException`.
    - `fun applyChange(id: Long, delta: Int): Int` — `addAndGet(delta)` 반환 (atomic, race 데모용).
    - `fun reset(id: Long, value: Int)` — 단일 ID 리셋.
    - `fun resetAll()` — 전체 스토어 클리어 (`store.clear()`). **@BeforeEach 격리용 (R5).**
  - `companion object : KLogging()`.

---

## Phase 3: Fenced Guard

> **⚠️ Phase 3 가 Phase 4 보다 먼저여야 함** — `FencedInventoryService`(T14) 와 `SuspendingFencedInventoryService`(T15) 가 `FencedResources`(T11) 에 의존. 순서 바뀌면 컴파일 실패.

### T10 — `FencedResource` (CAS guard)

- **complexity**: high
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/fenced/FencedResource.kt`
- **notes**:
  - `class FencedResource(val resourceId: Long)`, internal `private val lastSeenToken = atomic(0L)` (kotlinx.atomicfu — 클래스 프로퍼티이므로 `atomic()` 사용 가능; R7 결정).
  - `fun <T : Any> apply(token: Long, work: () -> T): T?`:
    ```kotlin
    while (true) {
        val current = lastSeenToken.value
        if (token < current) return null  // strict less-than → equal token 허용 (재진입)
        if (lastSeenToken.compareAndSet(current, maxOf(current, token))) {
            return work()
        }
        // CAS lost, retry
    }
    ```
  - **핵심 결정**: `token < current` (strict less-than). `token == current` 는 동일 임차자 재진입으로 허용 (`work()` 멱등성 가정).
  - 영문 KDoc — equal-token 정책 명시 + JVM 재시작 시 상태 리셋 (워크샵 한계).
  - `companion object : KLogging()`.

### T11 — `FencedResources` registry

- **complexity**: low
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/fenced/FencedResources.kt`
- **notes**:
  - **`@Component` 없음** — 테스트에서 직접 생성 (R4).
  - `private val map = ConcurrentHashMap<Long, FencedResource>()`.
  - `fun forResource(id: Long): FencedResource = map.computeIfAbsent(id) { FencedResource(it) }`.
  - `fun reset(id: Long) { map.remove(id) }`.
  - `fun resetAll() { map.clear() }` — **@BeforeEach 격리용 (R5).**
  - KDoc 에 워크샵 범위 한계 (unbounded, in-memory, JVM 재시작 시 리셋) 명시.

---

## Phase 4: Core Services

### T12 — `UnsafeInventoryService` (race demo)

- **complexity**: medium
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/service/UnsafeInventoryService.kt`
- **notes**:
  - 생성자: `(private val store: InventoryStore)`. **`@Service` 없음 — 테스트에서 직접 생성 (R4).**
  - `fun deduct(id: Long, qty: Int): DeductionResult`:
    1. `qty.requirePositiveNumber("qty")` ← **반드시 첫 줄**.
    2. `val current = store.currentStock(id)` (READ).
    3. `if (current < qty) return InsufficientStock(qty, current)`.
    4. **`Thread.sleep(1)`** — 강제 race window (spec Risk 1).
    5. `val remaining = store.applyChange(id, -qty)` (WRITE).
    6. `return Success(remaining)`.
  - `companion object : KLogging()`.
  - KDoc: "이 클래스는 의도적으로 race condition 을 시연한다. 운영에 사용 금지."

### T13 — `LockedInventoryService` (RLock)

- **complexity**: medium
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/service/LockedInventoryService.kt`
- **notes**:
  - 생성자: `(private val redisson: RedissonClient, private val store: InventoryStore)`. **`@Service` 없음 (R4).**
  - 시그니처: `fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult`.
  - 흐름:
    1. `qty.requirePositiveNumber("qty")`.
    2. `val lockName = "inventory:lock:$id"` + `val lock = redisson.getLock(lockName)`.
    3. `val acquired = lock.tryLock(waitMs, leaseMs, MILLISECONDS)` — **3인수 형태 필수** (watchdog 비활성화, spec Risk 3).
    4. 실패 시 `LockNotAcquired(lockName)` 반환, warn 로그.
    5. `try { ... } finally { if (lock.isHeldByCurrentThread) lock.unlock() }`.
  - `companion object : KLogging()`.

### T14 — `FencedInventoryService` (RFencedLock, blocking)

- **complexity**: high
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/service/FencedInventoryService.kt`
- **notes**:
  - 생성자: `(private val redisson: RedissonClient, private val store: InventoryStore, private val fencedResources: FencedResources)`. **`@Service` 없음 (R4).**
  - 흐름:
    1. `qty.requirePositiveNumber("qty")`.
    2. `val lockName = "inventory:fenced:$id"` + `val fLock = redisson.getFencedLock(lockName)`.
    3. `val token = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)` (blocking 경로는 원자적 메서드 OK — threadId 문제 없음).
    4. `token == null` → `LockNotAcquired`.
    5. `fencedResources.forResource(id).apply(token) { ... } ?: Rejected(token)`.
    6. `finally { runCatching { fLock.unlock() }.onFailure { log.warn(it) { "Fenced unlock failed (lease may have expired)" } } }` (lease 만료 시 IllegalMonitorStateException 흡수).
  - `companion object : KLogging()`.

### T15 — `SuspendingFencedInventoryService` (coroutine + fenced)

- **complexity**: high
- **files**:
  - `src/main/kotlin/io/bluetape4k/workshop/lock/service/SuspendingFencedInventoryService.kt`
- **notes**:
  - 생성자: `(private val redisson: RedissonClient, private val store: InventoryStore, private val fencedResources: FencedResources, private val beforeWork: suspend () -> Unit = {})`.
  - **`beforeWork` 파라미터 = 테스트 시임(seam)** — 기본값 `{}` (no-op). 취소 테스트 시 `beforeWork = { delay(500) }` 주입으로 취소 창 확보 (R2). **`@Service` 없음 (R4).**
  - `companion object : KLoggingChannel()` ← **suspend service 전용 로거**.
  - `suspend fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult`.
  - **상세 흐름 (spec §6.4 계약 — 위반 시 락 리크):**
    1. `qty.requirePositiveNumber("qty")`.
    2. `val lockName = "inventory:sfenced:$id"`.
    3. **`val lockId = redisson.getLockId(lockName)`** — 한 번만 결정, acquire/release 동일 값 사용 (수명주기 계약).
    4. `val fLock = redisson.getFencedLock(lockName)`.
    5. **두 단계 (Redisson 4.4.0 에는 `tryLockAndGetTokenAsync(..., lockId)` 변형 없음):**
       - `val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()`.
       - `if (!acquired) return LockNotAcquired(lockName)`.
       - `val token: Long = fLock.getTokenAsync().await()`.
    6. `try { beforeWork(); fencedResources.forResource(id).apply(token) { ... } ?: Rejected(token) }`.
    7. **`finally { withContext(NonCancellable) { try { fLock.unlockAsync(lockId).await() } catch (e: Exception) { log.warn(e) { "..." } } } }`** — `NonCancellable` 없이 `await()` 만 쓰면 취소 즉시 CancellationException 으로 unlock 디스패치 실패 → 락 리크 (P0).
  - `tryLockAndGetTokenAsync` 사용 금지 (threadId/lockId 파라미터 미지원).

---

## Phase 5: Tests

### T16 — `AbstractDistributedLockTest` (test base)

- **complexity**: medium
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/AbstractDistributedLockTest.kt`
- **notes**:
  - `abstract class AbstractDistributedLockTest`.
  - `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` 명시.
  - **`companion object : KLoggingChannel()`** — 코루틴 테스트를 호스팅하는 추상 베이스에는 `KLoggingChannel` 사용 (R7).
  - Testcontainers 싱글톤:
    ```kotlin
    companion object : KLoggingChannel() {
        val redis = RedisServer.Launcher.redis
        val redisUrl: String get() = redis.url
    }
    ```
  - **수동 픽스처 (Spring DI 없음, R4):**
    ```kotlin
    protected val store = InventoryStore()
    protected val fencedResources = FencedResources()
    protected val redisson: RedissonClient by lazy {
        redissonClient { useSingleServer { address = redisUrl } }
    }
    protected val unsafeService: UnsafeInventoryService by lazy { UnsafeInventoryService(store) }
    protected val lockedService: LockedInventoryService by lazy { LockedInventoryService(redisson, store) }
    protected val fencedService: FencedInventoryService by lazy {
        FencedInventoryService(redisson, store, fencedResources)
    }
    protected val suspendingService: SuspendingFencedInventoryService by lazy {
        SuspendingFencedInventoryService(redisson, store, fencedResources)
    }
    ```
  - **`@BeforeEach fun resetState()`** — `store.resetAll()` + `fencedResources.resetAll()` (R5). `@RepeatedTest` 격리 보장.
  - `@AfterAll fun shutdown()` — `redisson.shutdown()`.
  - `protected fun randomName(prefix: String) = "$prefix-${UUID.randomUUID()}"` 헬퍼.

### T17 — `BaselineRaceTest` (race demo)

- **complexity**: high
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/BaselineRaceTest.kt`
- **notes**:
  - `class BaselineRaceTest : AbstractDistributedLockTest()`.
  - **`@RepeatedTest(3)`** (결정성 확보).
  - `@BeforeEach` 에서 `store.register(Inventory(1L, "사과", 100))` — `resetState()` 가 `resetAll()` 하므로 재등록 필요.
  - `MultithreadingTester().workers(20).rounds(1).add { unsafeService.deduct(1L, 10) }.run()`.
  - **단언**: `successCount.get() > 10 || store.currentStock(1L) < 0` (initialStock=100, qty=10 → 정상이면 successCount==10. oversell 시 successCount > 10 또는 stock < 0).
  - 실패 메시지에 actual `successCount`, `currentStock` 포함.

### T18 — `DistributedLockTest` (RLock)

- **complexity**: medium
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/DistributedLockTest.kt`
- **notes**:
  - `: AbstractDistributedLockTest()`.
  - `@RepeatedTest(3)` for 정확성.
  - `@BeforeEach` 에서 `store.register(Inventory(1L, "사과", 100))`.
  - 같은 워크로드 (20 workers × 1 round × qty=10, initialStock=100).
  - 서비스: `lockedService` (베이스 픽스처).
  - **단언 (R8):**
    - `successCount shouldBeEqualTo 10` AND `store.currentStock(1L) shouldBeEqualTo 0`.
  - **추가 테스트 케이스 (R8):**
    - `qty <= 0 시 IllegalArgumentException`: `assertFailsWith<IllegalArgumentException> { lockedService.deduct(1L, 0) }`.
    - `재고 부족 시 InsufficientStock`:
      ```kotlin
      val result = lockedService.deduct(1L, 200)
      result shouldBeInstanceOf InsufficientStock::class
      (result as InsufficientStock).requested shouldBeEqualTo 200
      (result as InsufficientStock).available shouldBeLessThan 200
      ```
  - `waitMs=3000L, leaseMs=5000L` 로 안정성 확보.

### T19 — `FencedLockTest` (RFencedLock + FencedResource)

- **complexity**: medium
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/FencedLockTest.kt`
- **notes**:
  - `: AbstractDistributedLockTest()`.
  - **테스트 케이스 3개:**
    1. `토큰 단조 증가`: 5회 연속 `tryLockAndGetToken → unlock` → `tokens.zipWithNext().forEach { (a, b) -> b shouldBeGreaterThan a }`.
    2. `구 토큰으로 apply 시도 시 null 반환` — **핵심 (strict less-than 검증)**:
       - `resource.apply(5L) { "ok" }.shouldNotBeNull()`
       - `resource.apply(3L) { "should reject" }.shouldBeNull()`
       - `resource.apply(5L) { "same ok" }.shouldNotBeNull()` ← **equal token 허용 (재진입)**
       - `resource.apply(4L) { "also reject" }.shouldBeNull()` ← 이미 5 → 4 는 stale
    3. 동시 차감에서 no oversell (`fencedService` + `MultithreadingTester`).

### T20 — `SuspendFencedLockTest` (coroutine + cancellation)

- **complexity**: high
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/SuspendFencedLockTest.kt`
- **notes**:
  - `: AbstractDistributedLockTest()`.
  - **테스트 케이스 2개:**
    1. `코루틴 차감 — no oversell`: `runSuspendIO { ... }` + `SuspendedJobTester` (bluetape4k-junit5) 로 동시 코루틴 워크로드 → `successCount==10`, `currentStock==0`.
    2. **`코루틴 취소 시 락이 정상 해제됨` (P0 검증, R2):**
       ```kotlin
       @Test
       fun `코루틴 취소 시 락이 정상 해제됨`() = runSuspendIO {
           store.register(Inventory(999L, "취소테스트", 100))
           val lockName = "inventory:sfenced:999"
           val fLock = redisson.getFencedLock(lockName)

           // beforeWork 시임(seam) 으로 취소 창 확보 — delay(50) 이 apply() 호출 전 delay(500) 내부에서 발생
           val slowService = SuspendingFencedInventoryService(
               redisson, store, fencedResources,
               beforeWork = { delay(500) }   // 락 획득 후 500ms 지연 → 취소 창 확보
           )

           val job = launch { slowService.deduct(999L, 10) }
           delay(50)            // deduct 가 락 획득 후 beforeWork() 진행 중
           job.cancel()
           job.join()

           // withContext(NonCancellable) 로 unlock 이 완료되어야 false
           fLock.isLocked shouldBeEqualTo false
       }
       ```
     - 이 테스트가 없으면 `withContext(NonCancellable)` 수정의 효과를 검증 불가.
     - `delay(50)` 이 `beforeWork` 의 `delay(500)` 내부에서 발생 → 취소가 반드시 lock-held section 에 도달 (R2 false-positive 방지).

### T21 — `LockFailureTest` (smoke, lease 만료)

- **complexity**: medium
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/LockFailureTest.kt`
- **notes**:
  - `: AbstractDistributedLockTest()`.
  - **클래스 또는 메서드에 `@Tag("smoke")` — 모든 테스트 메서드에 부착** (junit-platform.properties 의 `exclude.tags=smoke` 로 기본 CI 제외).
  - 케이스: `lease 만료 후 unlock 시도 시 IllegalMonitorStateException`.
    - `lock.tryLock(1000, 200, MILLISECONDS)`, `Thread.sleep(500)`.
    - **`assertFailsWith<IllegalMonitorStateException> { lock.unlock() }`** (R3 — CLAUDE.md 준수: `kotlin.test.assertFailsWith<T>`, `org.junit.jupiter.api.assertThrows` 사용 금지).

### T22 — `FencedStaleHolderTest` (smoke, 완전 시나리오)

- **complexity**: high
- **files**:
  - `src/test/kotlin/io/bluetape4k/workshop/lock/FencedStaleHolderTest.kt`
- **notes**:
  - `: AbstractDistributedLockTest()`.
  - **모든 테스트 `@Tag("smoke")`** — 타이밍 의존 (lease=200ms, sleep=500ms).
  - 완전한 stale-holder 시나리오 (spec §7.4):
    1. A 가 `tryLockAndGetToken(1000, 200, MILLISECONDS)` → token1.
    2. `Thread.sleep(500)` → lease 만료.
    3. B 가 `tryLockAndGetToken(1000, 5000, MILLISECONDS)` → token2; `token2 shouldBeGreaterThan token1`.
    4. `resource.apply(token2) { "B wins" }.shouldNotBeNull()`.
    5. `resource.apply(token1) { "A is stale" }.shouldBeNull()` ← 거부 확인.
    6. **`assertFailsWith<IllegalMonitorStateException> { fLock1.unlock() }`** (R3 — CLAUDE.md 준수).
    7. `fLock2.unlock()` 정상.

---

## Phase 6: Documentation

### T23 — `README.md` (English, primary)

- **complexity**: medium
- **files**:
  - `README.md`
- **notes**:
  - 구조 (CLAUDE.md 권장):
    1. Architecture (Mermaid diagram — 4 단계 학습 경로 시각화).
    2. Core features (RLock, RFencedLock, coroutine-safe variant, race demo).
    3. Usage examples (각 서비스의 deduct() 호출 예).
    4. Configuration options (waitMs, leaseMs, `redisson.yaml`).
    5. Dependency instructions (`./gradlew :redis-distributed-lock:test`).
  - 학습 경로 표 (spec §0) 포함.
  - 영어 작성.

### T24 — `README.ko.md` (Korean, locale)

- **complexity**: medium
- **files**:
  - `README.ko.md`
- **notes**:
  - `README.md` 구조 1:1 미러링, 한국어 본문.
  - 학습 경로 + 코드 예제 + 명령어는 영어 그대로 (코드/명령어는 비번역).

### T25 — KDoc 마감 점검 (touch-pass on public API)

- **complexity**: low
- **files**:
  - 모든 `src/main/kotlin/io/bluetape4k/workshop/lock/**/*.kt` (touch-pass)
- **notes**:
  - 모든 공개 클래스/인터페이스/함수에 영문 KDoc 보장.
  - 각 KDoc: 한 줄 요약 + `## Behavior / Contract` (특히 `FencedResource.apply`, `SuspendingFencedInventoryService.deduct`).
  - 멱등성 가정 (`FencedResource`), `getLockId` 수명주기 (`SuspendingFencedInventoryService`), `NonCancellable` 보장 명시.
  - `beforeWork` 파라미터 — "test seam, default no-op; inject `delay(ms)` in tests to create cancellation window" 명시.
  - IDE diagnostics 정리 (오류 0, 미해결 deprecation 0) — Kotlin Editing Workflow 준수.

---

## Phase 7: CI Integration

### T26 — smoke-validate.sh 등록

- **complexity**: low
- **files**:
  - `smoke-validate.sh` (워크샵 루트, 존재 시)
- **notes**:
  - `rg ":redis-" smoke-validate.sh` 로 기존 redis 그룹 확인.
  - redis 그룹에 `:redis-distributed-lock:test` 추가.
  - 총 모듈 카운트 stale-check 줄 수정 (기존 카운트 +1).
  - **선행 조건**: `smoke-validate.sh` 파일이 워크샵 루트에 존재할 때만 적용. 없으면 T26 는 N/A 처리.

---

## Validation / DoD 체크리스트 (spec §10 매핑)

- [ ] `./gradlew :redis-distributed-lock:test` → 스모크 제외 전체 통과 (T17–T20).
- [ ] `./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=` → 스모크 포함 통과 (T21, T22).
- [ ] IDE diagnostics: 오류 0, deprecation 0 (T25).
- [ ] `BaselineRaceTest` 가 oversell 을 실제로 관찰 (T17).
- [ ] `SuspendFencedLockTest` 의 취소 케이스에서 `fLock.isLocked == false` (T20, P0).
- [ ] `README.md` + `README.ko.md` 둘 다 존재 (T23, T24).
- [ ] 모든 공개 API 영문 KDoc (T25).
- [ ] bluetape4k-patterns 체크리스트 통과.

---

## 결정 기록

1. **settings.gradle.kts 수정 없음.** Line 37 `includeModules("redis", false, true)` 가 디렉토리 추가 시 `:redis-distributed-lock` 자동 등록 (spec 명시).
2. **build.gradle.kts 는 lean.** 인접 `redisson-examples` 의 grpc/protobuf/fory/kryo/cache/h2 의존성은 이 모듈에 불필요 → 제외.
3. **BaselineRaceTest 는 `@RepeatedTest(3)`** (결정성 ↑).
4. **`FencedResource.apply`: strict less-than (`token < current`).** equal token 은 재진입으로 허용 (spec §6.3, FencedLockTest 케이스 2가 검증).
5. **`SuspendingFencedInventoryService`: 두 단계 acquire (`tryLockAsync` + `getTokenAsync`).** Redisson 4.4.0 에는 `tryLockAndGetTokenAsync(..., lockId)` 변형이 없음. 추가 RTT 1회는 코루틴 안전성을 위한 필수 비용.
6. **`finally { withContext(NonCancellable) { unlockAsync.await() } }` 필수.** 누락 시 코루틴 취소 → CancellationException → unlock 디스패치 실패 → 락 리크 (P0).
7. **모든 `deduct()` 첫 줄 `qty.requirePositiveNumber("qty")`.** 음수/0 qty 는 스톡을 증가시키므로 반드시 거부.
8. **`LockFailureTest`, `FencedStaleHolderTest` 는 `@Tag("smoke")`.** `junit-platform.properties` 의 `exclude.tags=smoke` 로 기본 실행 제외.
9. **`KLogging` (non-suspend) vs `KLoggingChannel` (suspend).** 4개 서비스 중 `SuspendingFencedInventoryService` 만 `KLoggingChannel`. 추상 테스트 베이스도 코루틴 테스트 호스팅 → `KLoggingChannel` (R7).
10. **`assertFailsWith<T> { }` 전용 (R3).** CLAUDE.md 는 `kotlin.test.assertFailsWith<T>` 를 금지하나, CLAUDE.md 프로젝트 CLAUDE.md 는 `assertFailsWith<T>{}` 를 사용하라고 명시. 혼동을 막기 위해 `import kotlin.test.assertFailsWith` 를 명확히 사용. `org.junit.jupiter.api.assertThrows` 는 금지 (JUnit API, Kotlin 관례에 맞지 않음).
11. **Spring DI 없음 (R4).** `@Component`, `@Service` 제거. 모든 픽스처는 `AbstractDistributedLockTest` 에서 수동 생성. 워크샵 예제에서 Spring context 는 불필요한 복잡성.
12. **`beforeWork` 테스트 시임 (R2).** `SuspendingFencedInventoryService` 생성자에 `beforeWork: suspend () -> Unit = {}` 추가. 취소 테스트에서 `beforeWork = { delay(500) }` 주입으로 lock-held section 내 취소 창 확보. delay(50) 단순 타이밍에 의존하는 false-positive 테스트 방지.
13. **`FencedResource.lastSeenToken` → `atomicfu atomic()` (R7).** 클래스 프로퍼티이므로 `atomic()` 사용 가능 (CLAUDE.md: "atomicfu `atomic()` must only be used as class properties").
14. **`annotationProcessor` 제거 (R9).** 워크샵 앱은 자동설정 메타데이터 생성 목적이 없음 → `annotationProcessor(libs.spring.boot.autoconfigure.processor)` 등 불필요.

---

## Task Summary Table

| Task | Title | Phase | Complexity | Files |
|------|-------|-------|------------|-------|
| T1 | Module `build.gradle.kts` (lean deps, toolchain 21) | 1 | medium | `build.gradle.kts` |
| T2 | `DistributedLockApp.kt` Spring Boot entry | 1 | low | `src/main/kotlin/.../DistributedLockApp.kt` |
| T3 | Main `application.yaml` (web-type=none) | 1 | low | `src/main/resources/application.yaml` |
| T4 | `redisson.yaml` | 1 | low | `src/main/resources/redisson.yaml` |
| T5 | Test `application.yaml` (web-type=none) + `junit-platform.properties` (smoke exclude) | 1 | low | `src/test/resources/application.yaml`, `src/test/resources/junit-platform.properties` |
| T6 | Test `logback-test.xml` | 1 | low | `src/test/resources/logback-test.xml` |
| T7 | `Inventory` data class | 2 | low | `src/main/kotlin/.../domain/Inventory.kt` |
| T8 | `DeductionResult` sealed hierarchy (per-subtype serialVersionUID) | 2 | medium | `src/main/kotlin/.../domain/DeductionResult.kt` |
| T9 | `InventoryStore` (in-memory, no @Component, resetAll()) | 2 | medium | `src/main/kotlin/.../domain/InventoryStore.kt` |
| T10 | `FencedResource` (CAS guard, strict less-than, atomicfu) | 3 | high | `src/main/kotlin/.../fenced/FencedResource.kt` |
| T11 | `FencedResources` registry (no @Component, resetAll()) | 3 | low | `src/main/kotlin/.../fenced/FencedResources.kt` |
| T12 | `UnsafeInventoryService` (race demo, no @Service) | 4 | medium | `src/main/kotlin/.../service/UnsafeInventoryService.kt` |
| T13 | `LockedInventoryService` (RLock, 3-arg tryLock, no @Service) | 4 | medium | `src/main/kotlin/.../service/LockedInventoryService.kt` |
| T14 | `FencedInventoryService` (RFencedLock blocking, no @Service) | 4 | high | `src/main/kotlin/.../service/FencedInventoryService.kt` |
| T15 | `SuspendingFencedInventoryService` (lockId + NonCancellable + beforeWork seam, no @Service) | 4 | high | `src/main/kotlin/.../service/SuspendingFencedInventoryService.kt` |
| T16 | `AbstractDistributedLockTest` (manual wiring, KLoggingChannel, @BeforeEach resetState) | 5 | medium | `src/test/kotlin/.../AbstractDistributedLockTest.kt` |
| T17 | `BaselineRaceTest` (@RepeatedTest(3), oversell assertion) | 5 | high | `src/test/kotlin/.../BaselineRaceTest.kt` |
| T18 | `DistributedLockTest` (RLock correctness + qty validation + InsufficientStock assertion) | 5 | medium | `src/test/kotlin/.../DistributedLockTest.kt` |
| T19 | `FencedLockTest` (monotonic tokens + strict-less-than + concurrency) | 5 | medium | `src/test/kotlin/.../FencedLockTest.kt` |
| T20 | `SuspendFencedLockTest` (no-oversell + cancellation P0 via beforeWork seam) | 5 | high | `src/test/kotlin/.../SuspendFencedLockTest.kt` |
| T21 | `LockFailureTest` (@Tag("smoke"), lease expiry, assertFailsWith) | 5 | medium | `src/test/kotlin/.../LockFailureTest.kt` |
| T22 | `FencedStaleHolderTest` (@Tag("smoke"), full stale-holder flow, assertFailsWith) | 5 | high | `src/test/kotlin/.../FencedStaleHolderTest.kt` |
| T23 | `README.md` (English, Mermaid diagram, learning path) | 6 | medium | `README.md` |
| T24 | `README.ko.md` (Korean mirror) | 6 | medium | `README.ko.md` |
| T25 | KDoc touch-pass on public API + IDE diagnostics | 6 | low | all `src/main/kotlin/.../**/*.kt` |
| T26 | smoke-validate.sh redis group registration (if file exists) | 7 | low | `smoke-validate.sh` |

**Total: 26 tasks.**

**Complexity breakdown:**

- **high (6)**: T10, T14, T15, T17, T20, T22.
- **medium (11)**: T1, T8, T9, T12, T13, T16, T18, T19, T21, T23, T24.
- **low (9)**: T2, T3, T4, T5, T6, T7, T11, T25, T26.

Total: **26 tasks** (high 6 + medium 11 + low 9).

---

## Step 3-R Revision Log

| Round | P0 | P1 | P2 | P3 | Applied |
|-------|----|----|----|----|---------|
| R1 (initial) | 4 | 4 | 2 | 1 | → |
| R2 (this) | 0 | 0 | 0 | 0 | 2026-05-23 |

**R1-R9 수정 내역 (2026-05-23):**
- R1 (P0): Phase 3 ↔ Phase 4 순서 교환 — FencedResource/FencedResources 가 서비스보다 먼저 (T10/T11 ← 구 T14/T15, T12-T15 ← 구 T10-T13).
- R2 (P0): T20 취소 테스트 재설계 — `beforeWork: suspend () -> Unit = {}` 시임 추가 (T15). `delay(50)` 단순 타이밍 false-positive 제거.
- R3 (P0): T21/T22 `assertThrows` → `assertFailsWith<T> { }` 교체. 결정 기록 #10 추가.
- R4 (P0): `@Component`/`@Service` 전면 제거. T16 수동 픽스처 4종 + redisson lazy. 결정 기록 #11 추가.
- R5 (P1): T16 `@BeforeEach resetState()` — `store.resetAll()` + `fencedResources.resetAll()`. T9/T11 에 `resetAll()` 메서드 추가.
- R6 (P1): T26 추가 — smoke-validate.sh redis 그룹 등록.
- R7 (P2): T3/T5 `spring.main.web-application-type: none`. T16 `KLoggingChannel`. T10 `atomicfu atomic()` 결정 기록 #13.
- R8 (P2): T18 qty<=0 validation + InsufficientStock 필드 단언 추가.
- R9 (P3): T1 `annotationProcessor` 제거 + java toolchain 21 추가. 결정 기록 #14.
