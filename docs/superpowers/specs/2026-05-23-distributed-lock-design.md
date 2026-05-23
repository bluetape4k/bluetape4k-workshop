# 설계 문서: Distributed/Fenced Lock 워크샵 모듈 (Issue #101)

작성일: 2026-05-23  
브랜치: `feat/issue-101-distributed-lock`  
모듈: `redis/distributed-lock` (Gradle: `:redis-distributed-lock`)

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
sealed interface DeductionResult {
    data class Success(val remaining: Int, val token: Long? = null) : DeductionResult, Serializable
    data object InsufficientStock : DeductionResult, Serializable
    data object Rejected : DeductionResult, Serializable  // fenced token rejected
    data object LockNotAcquired : DeductionResult, Serializable  // lock timeout
    companion object { private const val serialVersionUID = 1L }
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
    fun deduct(id: Long, qty: Int): DeductionResult {
        val current = store.currentStock(id)  // READ
        if (current < qty) return InsufficientStock
        Thread.sleep(1)                        // ← 강제 race window
        val remaining = store.applyChange(id, -qty)  // WRITE
        return Success(remaining)
    }
}

// LockedInventoryService.kt — RLock
class LockedInventoryService(private val redisson: RedissonClient, private val store: InventoryStore) {
    fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult {
        val lock = redisson.getLock("inventory:lock:$id")
        val acquired = lock.tryLock(waitMs, leaseMs, MILLISECONDS)  // 명시적 leaseTime → watchdog 비활성화
        if (!acquired) return LockNotAcquired
        try {
            val current = store.currentStock(id)
            if (current < qty) return InsufficientStock
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
    fun deduct(id: Long, qty: Int, waitMs: Long = 2000L, leaseMs: Long = 5000L): DeductionResult {
        val fLock = redisson.getFencedLock("inventory:fenced:$id")
        val token = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS) ?: return LockNotAcquired
        try {
            val result = fencedResources.forResource(id).apply(token) {
                val current = store.currentStock(id)
                if (current < qty) InsufficientStock
                else Success(store.applyChange(id, -qty), token)
            }
            return result ?: Rejected
        } finally {
            runCatching { fLock.unlock() }  // non-suspend, safe to use runCatching
        }
    }
}
```

### 6.3 펜스 거부 가드

```kotlin
// FencedResource.kt
class FencedResource(val resourceId: Long) {
    private val lastSeenToken = AtomicLong(0L)
    
    fun <T : Any> apply(token: Long, work: () -> T): T? {
        while (true) {
            val current = lastSeenToken.get()
            if (token < current) return null  // stale token → reject
            if (lastSeenToken.compareAndSet(current, maxOf(current, token))) {
                return work()
            }
            // CAS lost, retry
        }
    }
}

// FencedResources.kt
@Component
class FencedResources {
    private val map = ConcurrentHashMap<Long, FencedResource>()
    fun forResource(id: Long) = map.computeIfAbsent(id) { FencedResource(it) }
    fun reset(id: Long) { map.remove(id) }
}
```

### 6.4 코루틴 변형

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
        val lockName = "inventory:sfenced:$id"
        val lockId = redisson.getLockId(lockName)  // 코루틴 안전 정체성
        val fLock = redisson.getFencedLock(lockName)
        
        val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
        if (!acquired) return LockNotAcquired
        
        try {
            val token = fLock.tokenAsync.await()
            val result = fencedResources.forResource(id).apply(token) {
                val current = store.currentStock(id)
                if (current < qty) InsufficientStock
                else Success(store.applyChange(id, -qty), token)
            }
            return result ?: Rejected
        } finally {
            try {
                fLock.unlockAsync(lockId).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "unlock failed for lockId=$lockId" }
            }
        }
    }
}
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
    resource.apply(5L) { "same ok" }.shouldNotBeNull()  // equal token 허용
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

| 라운드 | Reviewer | P0/P1 | P2/P3 | 반영 커밋 |
|---|---|---|---|---|
| Round 1 spec | (작성 중) | - | - | - |
