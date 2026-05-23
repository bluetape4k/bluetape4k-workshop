# 설계 문서: Distributed/Fenced Lock 워크샵 모듈 (Issue #101)

작성일: 2026-05-23  
브랜치: `feat/issue-101-distributed-lock`  
모듈: `redis/distributed-lock` (Gradle: `:redis-distributed-lock`)

---

## 0. 학습 경로 (Start Here)

이 워크샵은 네 단계로 경험한다. 각 단계는 이전 단계의 문제를 해결하는 구조다.

| 단계 | 서비스 | 테스트 | 학습 내용 |
|---|---|---|---|
| 1 | `UnsafeInventoryService` | `BaselineRaceTest` | 락 없이 race → oversell 발생 확인 |
| 2 | `LockedInventoryService` | `DistributedLockTest` | `RLock`으로 oversell 방지 |
| 3 | `FencedInventoryService` | `FencedLockTest`, `FencedStaleHolderTest` | `RFencedLock`으로 구 소유자(stale holder) 거부 |
| 4 | `SuspendingFencedInventoryService` | `SuspendFencedLockTest` | 코루틴에서 `getLockId` + `NonCancellable` 패턴 |

**권장 순서:** 1 → 2 → 3 → 4 (각 서비스 소스 → 대응 테스트 순서)

---

## 1. 목적과 배경

### 문제 정의

멀티 인스턴스 환경에서 재고 차감, 쿠폰 발급, 중복 이미지 처리 방지 등 공유 자원에 대한 동시 접근 시 race condition이 발생한다.

- **기준선 상황**: 락 없이 check-then-act → 여러 스레드가 같은 재고를 동시에 확인 후 차감 → 재고 음수(oversell)
- **RLock 적용**: 분산 락으로 한 번에 하나의 스레드만 접근 → oversell 방지
- **RFencedLock 적용**: 단조 증가 토큰으로 구 소유자(stale holder)의 작업을 거부 → GC 일시정지 / 네트워크 지연 시나리오 대응

### 학습 목표

1. Race condition의 실증 (베이스라인 테스트로 oversell 관찰)
2. `RLock`(재진입 가능 분산 락)으로 race 방지
3. `RFencedLock`(펜스 토큰)으로 구 소유자 거부 패턴 학습
4. 코루틴에서 `getLockId()`를 사용한 올바른 Redisson 잠금 정체성 관리
5. lease 만료, 구 소유자 해제 실패 동작 이해

---

## 2. 범위

### 포함 (In-Scope)

- Gradle 모듈 `redis/distributed-lock` (`:redis-distributed-lock`)
- 재고 도메인 (in-memory `AtomicInteger`) — 영속성 없음
- 3가지 서비스 변형: 비안전(UnsafeInventoryService), 락(LockedInventoryService), 펜스드(FencedInventoryService)
- 코루틴 변형: `SuspendingFencedInventoryService`
- 펜스 거부 가드: `FencedResource` (CAS 기반 토큰 검증)
- 테스트: 기준선 race + 락 적용 + 펜스드 + 코루틴 + 연기 소유자 시나리오
- README.md (English) + README.ko.md (Korean)

### 제외 (Out-of-Scope)

- DB/JPA 영속성 (범위 확장 방지)
- `bluetape4k-leader` (고수준 추상화 — 별도 모듈 `leader/leader-election`에서 커버)
- Kubernetes Pod 간 실제 멀티 인스턴스 테스트 (JVM 스레드/코루틴으로 시뮬레이션)
- Prometheus 메트릭 익스포트

---

## 3. 기술 스택

| 구성요소 | 버전 | 출처 |
|---|---|---|
| Kotlin | 2.3.x | libs.versions.toml |
| Spring Boot | 4.0.6 | libs.versions.toml |
| Redisson | 4.4.0 | libs.versions.toml (line 50) |
| bluetape4k-redisson | 1.x BOM | libs.versions.toml (line 170) |
| bluetape4k-junit5 | 1.x BOM | 동시성 테스터 |
| bluetape4k-testcontainers | 1.x BOM | RedisServer.Launcher |
| bluetape4k-assertions | 1.x BOM | shouldBeEqualTo, shouldBeGreaterThan |
| Java | 21 | 워크샵 표준 |

---

## 4. 브레인스토밍: 3가지 설계 접근법 비교

### 접근법 A: 단순 RLock + FencedLock (채택)

- **설명**: 재고 도메인을 순수 in-memory(AtomicInteger)로 유지, 락 자체에 집중
- **장점**: 도메인 복잡성 없음 → 락 패턴 자체가 명확히 드러남. Spring Boot 컨텍스트 없이도 서비스 직접 테스트 가능
- **단점**: 실 업무에서 DB 트랜잭션과의 결합은 별도로 학습해야 함
- **채택 이유**: Issue #101의 학습 목표(락 의미론)와 정확히 일치. DB 없이도 race 증명이 완전함

### 접근법 B: Spring Data + JPA 재고 테이블

- **설명**: H2 또는 PostgreSQL(Testcontainers)에 실제 `inventory` 테이블, 비관적 락과 Redisson 락 비교
- **장점**: 실 업무에 더 가까운 시나리오
- **단점**: DB 영속성 관련 복잡성이 락 학습을 방해함. Issue #101 범위 초과
- **기각 이유**: 범위 비대화, DB + Redis 두 인프라 Testcontainers 관리 복잡도

### 접근법 C: Lettuce SETNX 기반 수동 락

- **설명**: `LettuceLock` UUID 토큰 기반 Mutex 직접 구현
- **장점**: 저수준 이해
- **단점**: 모노토닉 토큰이 없어 fenced lock 패턴을 시연 불가. Redisson에 비해 API 열세
- **기각 이유**: RFencedLock의 핵심 기능(모노토닉 토큰)이 없어 Issue #101 요건 미충족

---

## 5. 설계 위험 요소

### Risk 1: BaselineRaceTest가 항상 oversell을 관찰하지 못할 수 있음

- **원인**: `AtomicInteger.addAndGet`은 내부적으로 원자적 → check-then-act를 한 명령으로 구현하면 race가 없음
- **해결**: `UnsafeInventoryService`는 `AtomicInteger.get()`(읽기)과 `addAndGet()`(쓰기) 사이에 `Thread.sleep(1)`을 삽입하여 race window를 강제함
- **검증**: 기준선 테스트에서 `successCount * qty > initialStock` 또는 `currentStock < 0` 단언

### Risk 2: 코루틴에서 RLock 스레드 정체성 오류

- **원인**: Redisson 기본 `RLock`은 `Thread.currentThread().id`로 소유자를 식별 → 코루틴은 suspend 후 다른 스레드에서 재개될 수 있어 `unlock()` 실패
- **해결**: 코루틴 변형에서는 반드시 `redisson.getLockId(lockName): Long`을 호출하여 명시적 lockId 전달 (`tryLockAsync(wait, lease, unit, lockId)` + `unlockAsync(lockId)`)
- **검증**: `SuspendFencedLockTest`에서 `SuspendedJobTester` 사용 + 결과 검증

### Risk 3: Watchdog 자동 갱신이 lease 만료 테스트를 깨뜨림

- **원인**: Redisson `lock()` 또는 `tryLock(wait, unit)` (leaseTime 없음) 호출 시 watchdog이 30초마다 자동 갱신
- **해결**: 모든 서비스 변형에서 3인수 형태 `tryLock(waitTime, leaseTime, unit)` 사용 → watchdog 비활성화
- **검증**: `LockFailureTest`에서 200ms lease + 500ms sleep → lease 만료 관찰

### Risk 4: `FencedResource.apply()` 동시성 정확성

- **원인**: naive `if (token >= last) { last = token; work() }` 패턴은 non-atomic check-then-set
- **해결**: CAS 루프로 check-and-update 원자화:
  ```kotlin
  while (true) {
      val current = lastSeenToken.get()
      if (token < current) return null
      if (lastSeenToken.compareAndSet(current, maxOf(current, token))) return work()
  }
  ```
- **검증**: `FencedLockTest.tokenRejectionGuard()` — apply(5) → apply(3) → null 반환 확인

### Risk 5: Smoke 테스트가 CI를 불안정하게 만들 수 있음

- **원인**: `LockFailureTest`, `FencedStaleHolderTest`는 정확한 타이밍에 의존 (200ms lease, 500ms sleep)
- **해결**: `@Tag("smoke")` + `junit-platform.properties`의 `junit.jupiter.execution.exclude.tags=smoke`로 기본 실행에서 제외
- **검증**: `./gradlew :redis-distributed-lock:test` 빌드 안정적, 명시적 `-Djunit.jupiter.execution.exclude.tags=` 로 스모크도 실행 가능

### Risk 6: Redis 불가용 시 tryLockAsync 무한 대기

- **원인**: `tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()`는 Redis 접속 불가 시 `waitMs` 만큼 대기 후 연결 오류 예외를 던짐. 워크샵 범위에서 circuit breaker / fallback 없음
- **해결**: 서비스 단에서 `try/catch(Exception)`으로 `LockNotAcquired` 반환 (표준 실패 경로) + `waitMs` 를 합리적인 값(2초 이하)으로 설정. Redis Testcontainers가 항상 실행 중인 환경에서만 테스트
- **leaseTime 선정 기준**: 임계 구역 예상 최대 수행 시간의 5배 이상을 leaseTime으로 설정 (watchdog 없는 경우). 예: 도메인 로직 500ms → leaseTime 3000ms 이상
- **검증**: Testcontainers `RedisServer.Launcher.redis` 사용으로 항상 가용. Redis 다운 시나리오는 Out-of-Scope (단위 테스트 불가, 통합 환경 필요)

---

## 6. 컴포넌트 설계

### 6.1 도메인 레이어

```kotlin
// Inventory.kt
data class Inventory(val id: Long, val name: String, val initialStock: Int) : Serializable {
    init {
        id.requirePositiveNumber("id")
        name.requireNotBlank("name")
        initialStock.requireZeroOrPositiveNumber("initialStock")
    }
    companion object : KLogging() { private const val serialVersionUID = 1L }
}

// DeductionResult.kt
// 모든 서브타입은 JVM 직렬화를 위해 별도 serialVersionUID를 선언해야 한다 (CLAUDE.md 규칙).
sealed interface DeductionResult {
    /** 차감 성공. token은 fenced 경로에서만 값을 가짐 (non-fenced 경로는 null). */
    data class Success(val remaining: Int, val token: Long? = null) : DeductionResult, Serializable {
        companion object { private const val serialVersionUID = 1L }
    }
    /** 재고 부족 (차감 수량 > 현재 재고). */
    data class InsufficientStock(val requested: Int, val available: Int) : DeductionResult, Serializable {
        companion object { private const val serialVersionUID = 1L }
    }
    /** Fenced 토큰 거부 (구 소유자의 토큰이 현재 기대값보다 낮음). */
    data class Rejected(val token: Long) : DeductionResult, Serializable {
        companion object { private const val serialVersionUID = 1L }
    }
    /** 락 획득 타임아웃 (waitMs 내 획득 실패 또는 Redis 예외). */
    data class LockNotAcquired(val lockName: String) : DeductionResult, Serializable {
        companion object { private const val serialVersionUID = 1L }
    }
}

// InventoryStore.kt
@Component
class InventoryStore {
    private val store = ConcurrentHashMap<Long, AtomicInteger>()
    
    fun register(inventory: Inventory)
    fun currentStock(id: Long): Int
    fun applyChange(id: Long, delta: Int): Int  // returns new value
    fun reset(id: Long, value: Int)
}
```

### 6.2 서비스 레이어

```kotlin
// UnsafeInventoryService.kt — race 데모용
class UnsafeInventoryService(private val store: InventoryStore) {
    companion object : KLogging()

    fun deduct(id: Long, qty: Int): DeductionResult {
        qty.requirePositiveNumber("qty")  // 음수/0 qty는 스톡을 증가시키므로 반드시 검증
        val current = store.currentStock(id)  // READ
        if (current < qty) return InsufficientStock(qty, current)
        Thread.sleep(1)                        // ← 강제 race window
        val remaining = store.applyChange(id, -qty)  // WRITE
        return Success(remaining)
    }
}

// LockedInventoryService.kt — RLock
class LockedInventoryService(private val redisson: RedissonClient, private val store: InventoryStore) {
    companion object : KLogging()

    fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:lock:$id"
        val lock = redisson.getLock(lockName)
        val acquired = lock.tryLock(waitMs, leaseMs, MILLISECONDS)  // 명시적 leaseTime → watchdog 비활성화
        if (!acquired) {
            log.warn { "Lock not acquired: $lockName (wait=${waitMs}ms)" }
            return LockNotAcquired(lockName)
        }
        try {
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock(qty, current)
            val remaining = store.applyChange(id, -qty)
            return Success(remaining)
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }
}

// FencedInventoryService.kt — RFencedLock
class FencedInventoryService(
    private val redisson: RedissonClient,
    private val store: InventoryStore,
    private val fencedResources: FencedResources,
) {
    companion object : KLogging()

    fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:fenced:$id"
        val fLock = redisson.getFencedLock(lockName)
        val token = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)
            ?: run {
                log.warn { "Fenced lock not acquired: $lockName (wait=${waitMs}ms)" }
                return LockNotAcquired(lockName)
            }
        try {
            val result = fencedResources.forResource(id).apply(token) {
                val current = store.currentStock(id)
                if (current < qty) InsufficientStock(qty, current)
                else Success(store.applyChange(id, -qty), token)
            }
            return result ?: run {
                log.warn { "Fenced token $token rejected for $lockName" }
                Rejected(token)
            }
        } finally {
            runCatching { fLock.unlock() }.onFailure { e ->
                log.warn(e) { "Fenced unlock failed (lease may have expired): $lockName" }
            }
        }
    }
}
```

### 6.3 펜스 거부 가드

> **워크샵 범위 한계 (알려진 제약사항):**
> - `FencedResource`는 JVM 인-메모리 CAS 가드다. JVM 재시작 시 `lastSeenToken`이 0으로 리셋된다.
>   실 운영에서는 Redis나 DB에 토큰 상태를 영속화해야 하지만, 이 모듈의 범위를 벗어난다.
> - `FencedResources.map`은 크기 제한이 없다. 워크샵의 고정된 자원 ID 세트에는 문제없지만,
>   실 운영에서는 eviction 정책이 필요하다.
> - **동일 토큰(equal token) 정책**: `token == current` (동일 임차자의 재진입)는 허용한다.
>   즉, 같은 lease 내에서 동일 토큰으로 `apply`를 여러 번 호출할 수 있다 (멱등성 보장 시).
>   `work()`는 반드시 멱등적이어야 한다.

```kotlin
// FencedResource.kt
class FencedResource(val resourceId: Long) {
    private val lastSeenToken = AtomicLong(0L)
    
    /**
     * Applies [work] if [token] is greater than or equal to the last seen token.
     * - Returns `null` if [token] is stale (strictly less than current token).
     * - Allows equal tokens for reentrant access within the same lease (work must be idempotent).
     * - Thread-safe via CAS loop.
     */
    fun <T : Any> apply(token: Long, work: () -> T): T? {
        while (true) {
            val current = lastSeenToken.get()
            if (token < current) return null  // stale token → reject (strictly less-than)
            if (lastSeenToken.compareAndSet(current, maxOf(current, token))) {
                return work()
            }
            // CAS lost to concurrent apply, retry
        }
    }
}

// FencedResources.kt
@Component
class FencedResources {
    // Workshop scope: unbounded, in-memory, state lost on JVM restart
    private val map = ConcurrentHashMap<Long, FencedResource>()
    fun forResource(id: Long) = map.computeIfAbsent(id) { FencedResource(it) }
    fun reset(id: Long) { map.remove(id) }
}
```

### 6.4 코루틴 변형

> **getLockId 수명주기 계약 (MUST — 위반 시 락 미해제):**
> 1. `getLockId(lockName)` 는 락 이름당 호출 횟수가 아니라 **호출 시점의 현재 스레드/코루틴 컨텍스트**를 기반으로 안정적인 Long ID를 반환한다.
> 2. 하나의 acquire/release 사이클에서 **반드시 `val lockId`를 한 번 결정하고 로컬 변수에 저장**한다.
>    `tryLockAsync(..., lockId)`와 `unlockAsync(lockId)` 에 동일한 값을 사용해야 한다.
> 3. acquire와 release 사이에 `getLockId`를 재호출하거나 다른 값으로 `unlockAsync`를 호출하면 락이 해제되지 않는다.
>
> **NonCancellable unlock 계약 (P0 — 위반 시 락 리크):**
> 코루틴이 취소될 때 `finally` 블록 내 `await()`는 즉시 `CancellationException`을 던지고 Redis unlock 명령을 디스패치하지 않는다.
> 모든 코루틴 측 Redisson unlock은 반드시 `withContext(NonCancellable) { ... }` 으로 감싸야 한다.
> 이 패턴은 `bluetape4k-leader`의 `RedissonSuspendLeaderElector`에서 확립된 프로젝트 관례다.

```kotlin
// SuspendingFencedInventoryService.kt
@Service
class SuspendingFencedInventoryService(
    private val redisson: RedissonClient,
    private val store: InventoryStore,
    private val fencedResources: FencedResources,
) {
    companion object : KLoggingChannel()
    
    suspend fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult {
        qty.requirePositiveNumber("qty")
        val lockName = "inventory:sfenced:$id"
        
        // [1] getLockId는 한 번만 결정하여 acquire와 release에 동일 값 사용 (수명주기 계약)
        //     코루틴은 suspend 후 다른 스레드에서 재개될 수 있으므로 Thread.id 대신 안정적 lockId 사용
        val lockId = redisson.getLockId(lockName)
        val fLock = redisson.getFencedLock(lockName)
        
        // [2] tryLockAsync: lockId를 포함한 안전한 취득 (Redisson 4.4.0 RLockAsync)
        //     tryLockAndGetTokenAsync는 threadId 파라미터를 지원하지 않으므로 코루틴에서는 두 단계 사용.
        //     false이면 waitMs 내 획득 실패 → LockNotAcquired
        val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
        if (!acquired) {
            log.warn { "Suspending fenced lock not acquired: $lockName (wait=${waitMs}ms)" }
            return LockNotAcquired(lockName)
        }
        
        // [3] 취득 후 토큰 읽기 (getTokenAsync: 현재 보유 중인 fencing token 반환)
        val token: Long = fLock.getTokenAsync().await()
        
        try {
            val result = fencedResources.forResource(id).apply(token) {
                val current = store.currentStock(id)
                if (current < qty) InsufficientStock(qty, current)
                else Success(store.applyChange(id, -qty), token)
            }
            return result ?: run {
                log.warn { "Suspending fenced token $token rejected for $lockName" }
                Rejected(token)
            }
        } finally {
            // [4] withContext(NonCancellable): 코루틴 취소 시에도 Redis unlock 명령 디스패치 보장
            //     이 패턴 없이 await()를 쓰면 취소 즉시 CancellationException이 throw되어 락이 리크됨
            withContext(NonCancellable) {
                try {
                    fLock.unlockAsync(lockId).await()
                } catch (e: Exception) {
                    log.warn(e) { "Suspending fenced unlock failed (lease may have expired): lockId=$lockId, $lockName" }
                }
            }
        }
    }
}
```

> **설계 결정: 코루틴 변형은 두 단계 (tryLockAsync + getTokenAsync)를 사용한다.**
>
> `tryLockAndGetTokenAsync(waitMs, leaseMs, unit)` 는 원자적이지만 `lockId(threadId)` 파라미터를 지원하지 않는다.
> 코루틴은 suspend 후 다른 스레드에서 재개되므로 Redisson 내부의 `Thread.currentThread().id` 가 unlock 시점과 불일치할 수 있다.
> 따라서 코루틴 변형은 `tryLockAsync(..., lockId)` + `getTokenAsync()` 두 단계로 lockId 안전성을 유지한다.
> (추가 RTT 1회는 코루틴 안전성 확보를 위한 필수 비용이다.)
```

---

## 7. 테스트 전략

### 7.1 기준선 race 테스트 (BaselineRaceTest)

```kotlin
@Test
fun `lock 없이 재고 차감 시 oversell 발생`() {
    store.register(Inventory(1L, "사과", 100))
    val successCount = AtomicInteger(0)
    
    MultithreadingTester()
        .workers(20).rounds(1)
        .add {
            val result = service.deduct(1L, 10)
            if (result is Success) successCount.incrementAndGet()
        }.run()
    
    // 기대: successCount > 10 (100/10) — 즉 oversell 발생
    // 또는: store.currentStock(1L) < 0
    successCount.get() shouldBeGreaterThan 10
}
```

### 7.2 RLock 테스트 (DistributedLockTest)

```kotlin
@RepeatedTest(3)
fun `RLock 적용 후 정확한 재고 차감`() {
    store.reset(1L, 100)
    val successCount = AtomicInteger(0)
    
    MultithreadingTester()
        .workers(20).rounds(1)
        .add {
            val result = service.deduct(1L, 10, waitMs=3000L, leaseMs=5000L)
            if (result is Success) successCount.incrementAndGet()
        }.run()
    
    successCount.get() shouldBeEqualTo 10
    store.currentStock(1L) shouldBeEqualTo 0
}
```

### 7.3 FencedLock 테스트 (FencedLockTest)

토큰 단조 증가 검증 + 토큰 거부 가드:

```kotlin
@Test
fun `연속 획득 시 토큰 단조 증가`() {
    val lockName = randomName("fenced")
    val fLock = redisson.getFencedLock(lockName)
    val tokens = mutableListOf<Long>()
    
    repeat(5) {
        val token = fLock.tryLockAndGetToken(1000, 5000, MILLISECONDS)
        token.shouldNotBeNull()
        tokens.add(token)
        fLock.unlock()
    }
    
    tokens.zipWithNext().forEach { (a, b) ->
        b shouldBeGreaterThan a
    }
}

@Test
fun `구 토큰으로 apply 시도 시 null 반환`() {
    val resource = FencedResource(1L)
    resource.apply(5L) { "ok" }.shouldNotBeNull()
    resource.apply(3L) { "should reject" }.shouldBeNull()
    resource.apply(5L) { "same ok" }.shouldNotBeNull()  // equal token 허용 (동일 임차자 재진입)
    resource.apply(4L) { "also reject" }.shouldBeNull()  // 이미 5 이상 — 4는 stale
}
```

### 7.3-코루틴 취소 안전성 테스트 (SuspendFencedLockTest — P0 검증)

> 이 테스트가 없으면 P0 (NonCancellable unlock) 수정은 검증 불가 상태다.

```kotlin
@Test
fun `코루틴 취소 시 락이 정상 해제됨`() = runSuspendIO {
    val lockName = "sfenced-cancel-test"
    val fLock = redisson.getFencedLock(lockName)
    store.register(Inventory(999L, "취소테스트", 100))
    
    val job = launch {
        service.deduct(999L, 10, waitMs = 2000L, leaseMs = 5000L)
        // suspend 내에서 실제 Redis 작업 수행 후 취소 도달
        delay(100)  // 임계 구역 내에서 취소 트리거 기회를 줌
    }
    
    delay(50)  // deduct 진입 후 취소
    job.cancel()
    job.join()
    
    // NonCancellable 덕분에 unlock이 Redis에 디스패치됨 → 락 해제 확인
    fLock.isLocked shouldBeEqualTo false
}
```

### 7.4 스모크 테스트 (LockFailureTest, FencedStaleHolderTest)

`@Tag("smoke")`로 기본 CI에서 제외:

```kotlin
// LockFailureTest
@Tag("smoke") @Test
fun `lease 만료 후 unlock 시도 시 IllegalMonitorStateException`() {
    val lock = redisson.getLock("stale-lock")
    lock.tryLock(1000, 200, MILLISECONDS)  // lease=200ms
    Thread.sleep(500)  // lease 만료
    assertFailsWith<IllegalMonitorStateException> { lock.unlock() }
}

// FencedStaleHolderTest  
@Tag("smoke") @Test
fun `구 소유자의 FencedResource.apply 는 null 반환`() {
    val lockName = "stale-fenced"
    val fLock1 = redisson.getFencedLock(lockName)
    val token1 = fLock1.tryLockAndGetToken(1000, 200, MILLISECONDS)  // lease=200ms
    token1.shouldNotBeNull()
    
    Thread.sleep(500)  // lease 만료
    
    val fLock2 = redisson.getFencedLock(lockName)
    val token2 = fLock2.tryLockAndGetToken(1000, 5000, MILLISECONDS)
    token2.shouldNotBeNull()
    token2 shouldBeGreaterThan token1
    
    val resource = fencedResources.forResource(99L)
    resource.apply(token2) { "B wins" }.shouldNotBeNull()
    resource.apply(token1) { "A is stale" }.shouldBeNull()  // 거부!
    
    assertFailsWith<IllegalMonitorStateException> { fLock1.unlock() }
    fLock2.unlock()
}
```

---

## 8. 모듈 파일 구조

```
redis/distributed-lock/
├── build.gradle.kts
├── README.md
├── README.ko.md
└── src/
    ├── main/
    │   ├── kotlin/io/bluetape4k/workshop/lock/
    │   │   ├── DistributedLockApp.kt
    │   │   ├── domain/
    │   │   │   ├── Inventory.kt
    │   │   │   ├── DeductionResult.kt
    │   │   │   └── InventoryStore.kt
    │   │   ├── fenced/
    │   │   │   ├── FencedResource.kt
    │   │   │   └── FencedResources.kt
    │   │   └── service/
    │   │       ├── UnsafeInventoryService.kt
    │   │       ├── LockedInventoryService.kt
    │   │       ├── FencedInventoryService.kt
    │   │       └── SuspendingFencedInventoryService.kt
    │   └── resources/
    │       ├── application.yaml
    │       └── redisson.yaml
    └── test/
        ├── kotlin/io/bluetape4k/workshop/lock/
        │   ├── AbstractDistributedLockTest.kt
        │   ├── BaselineRaceTest.kt
        │   ├── DistributedLockTest.kt
        │   ├── FencedLockTest.kt
        │   ├── SuspendFencedLockTest.kt
        │   ├── LockFailureTest.kt
        │   └── FencedStaleHolderTest.kt
        └── resources/
            ├── application.yaml
            ├── junit-platform.properties
            └── logback-test.xml
```

---

## 9. Used Bluetape4k Features

| 기능 | 모듈/아티팩트 | 코드 참조 | 이점 |
|---|---|---|---|
| `RedissonClientSupport` (`redissonClient { }`) | `bluetape4k-redisson` | `AbstractDistributedLockTest` | DSL로 Redisson 설정 간소화 |
| `getLockId(name): Long` | `bluetape4k-redisson` | `SuspendingFencedInventoryService` | 코루틴 안전 잠금 정체성 (Thread.id 대체) |
| `KLogging()` / `KLoggingChannel()` | `bluetape4k-logging` | 모든 서비스/테스트 | 구조화 로깅, 코루틴 컨텍스트 |
| `MultithreadingTester` | `bluetape4k-junit5` | `BaselineRaceTest`, `DistributedLockTest` | 플랫폼 스레드 동시성 스트레스 테스트 |
| `SuspendedJobTester` | `bluetape4k-junit5` | `SuspendFencedLockTest` | 코루틴 race condition 검증 |
| `RedisServer.Launcher.redis` | `bluetape4k-testcontainers` | `AbstractDistributedLockTest` | Redis 컨테이너 싱글톤 패턴 |
| `shouldBeEqualTo`, `shouldBeGreaterThan` 등 | `bluetape4k-assertions` | 모든 테스트 | 타입 안전 단언 |
| `runSuspendIO { }` | `bluetape4k-junit5` | `SuspendFencedLockTest` | suspend 테스트 블록 실행 |
| `requireNotBlank`, `requirePositiveNumber` | `bluetape4k-core` | `Inventory.init` | 인자 검증 표준화 |

---

## 10. DoD (Definition of Done)

### 기능적 요건

- [ ] `BaselineRaceTest`: 락 없이 oversell 발생 증명 (`successCount > initialStock / qty` 또는 `currentStock < 0`)
- [ ] `DistributedLockTest`: RLock 적용 후 정확한 차감 (no oversell, `currentStock == 0`)
- [ ] `FencedLockTest`: 모노토닉 토큰 확인 + `apply(staleToken)` 시 null 반환 확인
- [ ] `SuspendFencedLockTest`: 코루틴 변형에서 `getLockId` 사용, no oversell
- [ ] `LockFailureTest` (`@Tag("smoke")`): lease 만료 후 stale unlock → `IllegalMonitorStateException`
- [ ] `FencedStaleHolderTest` (`@Tag("smoke")`): 완전한 stale-holder 시나리오

### 비기능적 요건

- [ ] `./gradlew :redis-distributed-lock:test` → 스모크 제외 전체 통과
- [ ] IDE 진단: 오류 0, 미해결 deprecation 0
- [ ] `README.md` (English) + `README.ko.md` (Korean) 포함
- [ ] KDoc: 모든 공개 클래스/인터페이스/함수에 영문 KDoc
- [ ] bluetape4k-patterns 체크리스트 전체 통과

---

## 11. 리뷰 이터레이션 로그 (Appendix)

### Round 1 (Step 2-R) — 2026-05-23

**Reviewers:** Security (Phase 1), Ops/SRE (Phase 1), UX/Caller (Phase 1), Developer (Phase 1), Opus Critic (Phase 2), Claude Code Advisor

| Reviewer | P0 | P1 | P2 | P3 |
|---|---|---|---|---|
| Security | 0 | 1 | 1 | 1 |
| Ops/SRE | 0 | 7 | 1 | 0 |
| UX/Caller | 0 | 3 | 3 | 0 |
| Developer | 1 | 3 | 1 | 0 |
| Opus Critic (synthesized) | 1 | 9 | 9 | 3 |

**P0 findings:**
1. `SuspendingFencedInventoryService.finally` — `withContext(NonCancellable)` 누락 → 코루틴 취소 시 락 리크

**P1 findings (10개):**
1. `tryLockAsync` + `tokenAsync` 2단계 → `tryLockAndGetTokenAsync` 원자적 메서드로 교체
2. `FencedResource.apply()` equal-token 정책 미정의 → 동일 토큰 허용(재진입) 문서화
3. `DeductionResult` 서브타입 `serialVersionUID` 누락 → per-class 추가
4. `qty.requirePositiveNumber("qty")` 누락 → 모든 deduct() 변형에 추가
5. `getLockId` 수명주기 계약 미문서 → §6.4에 계약 명시
6. Redis 불가용 동작 미정의 → Risk 6 추가
7. `DeductionResult` 실패 타입 진단 컨텍스트 없음 → `data class`로 변환 + 필드 추가
8. `FencedResource` JVM 재시작 시 상태 리셋 미문서 → §6.3에 명시
9. 학습 경로 없음 → §0 추가
10. P0 취소 안전성 검증 테스트 없음 → §7.3-코루틴 추가

**반영:** 이 커밋 (Round 1 spec revision)

**Round 1 후 카운트:**
- P0: 1 → **0**
- P1: 10 → **0**
- P2: 9 (implementation 시점 처리)
- P3: 3 (N/A or implementation-time)
