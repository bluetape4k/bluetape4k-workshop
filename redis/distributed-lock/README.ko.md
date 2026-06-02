# redis-distributed-lock

[English](README.md) | 한국어

## 예제 시나리오

이 예제는 **redis-distributed-lock** 모듈을 실행 가능한 Redis 기반 조정 예제로 보여줍니다. 개발자가 먼저 확인할 경로인 모듈 설정, 샘플 또는 테스트 실행, 반복적인 인프라 코드를 줄이는 라이브러리 또는 프레임워크 API 사용 방식을 중심으로 설명합니다.

## 흐름 다이어그램

1. `redis-distributed-lock` 예제에 필요한 로컬 런타임을 준비합니다.
2. 예제 시나리오를 담당하는 애플리케이션, 컨트롤러, 서비스 또는 테스트 픽스처를 실행합니다.
3. 반복적인 인프라 처리는 bluetape4k 유틸리티 또는 Spring/Kotlin 통합 기능에 위임합니다.
4. 샘플 출력, HTTP 응답, 저장소 상태, metric, trace 또는 테스트 기대값으로 결과를 검증합니다.

## 시퀀스 다이어그램

핵심 시퀀스는 호출자 또는 테스트 픽스처 -> 워크샵 어댑터 -> bluetape4k 헬퍼/API -> 외부 런타임 또는 인메모리 백엔드 -> 검증/응답 순서입니다. 전용 시퀀스 이미지가 있는 모듈은 아래 이미지가 상호작용 순서를 보여주며, 없는 경우 소스 테스트가 실행 가능한 시퀀스의 기준입니다.

Redisson을 사용한 분산 락 전략을 단계별로 시연합니다.
비안전 공유 상태(oversell 발생)부터 스레드-안전 락, 펜싱 토큰 기반 락, 코루틴 네이티브 구현까지 다룹니다.

---

## 아키텍처

![distributed lock Architecture diagram](../../docs/images/readme-diagrams/redis-distributed-lock-architecture-01.png)

---

## 핵심 기능

| 기능 | 클래스 | 핵심 API |
|---|---|---|
| 비안전 (race 데모) | `UnsafeInventoryService` | 락 없음; oversell 시연 |
| 분산 락 | `LockedInventoryService` | `RLock.tryLock(wait, lease, unit)` |
| 펜싱 락 (블로킹) | `FencedInventoryService` | `RFencedLock.tryLockAndGetToken(wait, lease, unit)` |
| 펜싱 락 (코루틴) | `SuspendingFencedInventoryService` | `tryLockAsync` + `tokenAsync` + `NonCancellable` unlock |
| 토큰 가드 | `FencedResource` | CAS `lastSeenToken`; stale holder 쓰기 거부 |

---

## 모듈 구조

```
redis/distributed-lock/
├── src/main/kotlin/io/bluetape4k/workshop/lock/
│   ├── DistributedLockApp.kt          # Spring Boot 진입점
│   ├── domain/
│   │   ├── DeductionResult.kt         # sealed interface: Success / InsufficientStock / LockTimeout / Error
│   │   ├── Inventory.kt               # data class: id, name, stock
│   │   └── InventoryStore.kt          # 인메모리 동시성 저장소
│   ├── fenced/
│   │   ├── FencedResource.kt          # 단일 리소스 CAS 토큰 게이트
│   │   └── FencedResources.kt         # ConcurrentHashMap 레지스트리
│   └── service/
│       ├── UnsafeInventoryService.kt
│       ├── LockedInventoryService.kt
│       ├── FencedInventoryService.kt
│       └── SuspendingFencedInventoryService.kt
└── src/test/kotlin/io/bluetape4k/workshop/lock/
    ├── AbstractDistributedLockTest.kt  # 공통 픽스처 (Testcontainers Redis)
    ├── BaselineRaceTest.kt             # 락 없이 oversell 발생 증명
    ├── DistributedLockTest.kt          # RLock이 oversell 방지
    ├── FencedLockTest.kt               # 펜싱 가드; stale holder 거부
    ├── SuspendFencedLockTest.kt        # 코루틴 안전성 + 취소 안전성
    ├── FencedStaleHolderTest.kt        # [smoke] 리스 만료 재락 시나리오
    └── LockFailureTest.kt              # [smoke] tryLock 타임아웃 동작
```

---

## 사용 예시

### 1. 비안전 — race condition 증명

```kotlin
// stock=100, qty=10, 동시 20개 코루틴
// → oversell 발생: successCount > 10
val result = unsafeService.deduct(inventoryId, 10)
```

### 2. 분산 락 (블로킹)

```kotlin
val result = lockedService.deduct(inventoryId, qty = 10, waitMs = 3000L, leaseMs = 3000L)
when (result) {
    is DeductionResult.Success           -> println("차감 완료 token=${result.token}")
    is DeductionResult.InsufficientStock -> println("재고 부족")
    is DeductionResult.LockTimeout       -> println("락 타임아웃")
    is DeductionResult.Error             -> println("에러: ${result.message}")
}
```

### 3. 펜싱 락 — stale holder 방지

`FencedResource` CAS 게이트는 현재 `lastSeenToken`보다 오래된 토큰의 쓰기를 거부합니다.
느리게 재개된 이전 홀더가 새 홀더가 작성한 데이터를 덮어쓰는 것을 방지합니다.

```kotlin
// FencedInventoryService 내부 동작:
val token: Long = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)
val result: DeductionResult? = resource.apply(token) {
    store.deduct(inventoryId, qty)
}
// null → stale holder가 거부됨
```

### 4. 코루틴 네이티브 펜싱 락

```kotlin
// 코루틴 안전: NonCancellable unlock이 Job.cancel() 후에도 락 해제 보장
val result = suspendingService.deduct(inventoryId, qty = 10, waitMs = 3000L, leaseMs = 3000L)
```

#### 취소 안전성

`SuspendingFencedInventoryService`는 `finally` 블록에서 `withContext(NonCancellable)`을 사용해
코루틴이 취소되는 중에도 `unlockAsync(lockId).await()`가 반드시 완료되도록 보장합니다.

```kotlin
// 내부 패턴 요약
val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
if (!acquired) return LockTimeout
val token = fLock.tokenAsync.await()
try {
    /* 작업 수행 */
} finally {
    withContext(NonCancellable) {
        fLock.unlockAsync(lockId).await()
    }
}
```

---

## 테스트 실행

```bash
# 기본 (smoke 제외)
./gradlew :redis-distributed-lock:test

# 강제 재실행
./gradlew :redis-distributed-lock:test --rerun-tasks

# smoke 포함 (리스 만료 타이밍 테스트)
./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=

# 특정 테스트 클래스
./gradlew :redis-distributed-lock:test --tests "io.bluetape4k.workshop.lock.SuspendFencedLockTest"
```

> **Smoke 테스트** (`@Tag("smoke")`)는 리스 만료 및 타임아웃 시나리오를 실제 벽시계 딜레이로 검증합니다.
> 기본 CI에서는 flakiness 방지를 위해 제외됩니다.

---

## 핵심 개념

### 펜싱 토큰 프로토콜

펜싱 토큰은 락 서버가 성공적인 락 획득마다 발행하는 단조 증가 번호입니다.
리소스 업데이트는 반드시 토큰을 지참해야 하며, 리소스 가드(CAS `lastSeenToken`)는
오래된 토큰의 쓰기를 거부합니다.

이것은 "락 획득 후 GC 정지/네트워크 지연으로 리스가 만료된 후 쓰기 시도" 시나리오를 방지합니다:

```
클라이언트 A가 락 획득 → token=1
클라이언트 A가 정지 (GC, 네트워크, 리스 만료)
클라이언트 B가 락 획득 → token=2, token=2로 쓰기
클라이언트 A가 재개 → FencedResource가 쓰기 거부 (1 < 2) ✅
```

### SuspendedJobTester 의미론

`SuspendedJobTester.workers(N).rounds(R)`는 `N`개의 동시 워커가 협력하여 각 `add { }` 블록을 `R`라운드 실행합니다.
`totalUnits = R × blockCount`.

stock=100/qty=10 테스트의 경우:
- `workers(20).rounds(20)` → 총 20번 시도 → 정확히 10번 성공

### Smoke vs 기본 테스트

| 태그 | 목적 | 타이밍 포함? | 기본 CI |
|---|---|---|---|
| _(없음)_ | 빠른 정확성 검증 | 아니오 (로직만) | ✅ 포함 |
| `smoke` | 리스 만료, 타임아웃 | 예 (실제 벽시계) | ❌ 제외 |

---

## 의존성

- **Redisson** — `RLock`, `RFencedLock`, 비동기 API
- **bluetape4k-redisson** — `redissonClient {}` DSL
- **bluetape4k-redis** — `getLockId()` (Snowflake ID)
- **bluetape4k-coroutines** — `awaitSuspending()`, `io.bluetape4k.logging.coroutines`
- **bluetape4k-testcontainers** — `RedisServer.Launcher.redis` Testcontainers 싱글턴
- **bluetape4k-junit5** — `SuspendedJobTester`, `MultithreadingTester`
- **kotlinx.atomicfu** — `atomic(0L)` for `FencedResource.lastSeenToken`

## 사용된 bluetape4k 기능

| 기능 | 아티팩트 | 코드 위치 | 이점 |
|---|---|---|---|
| `RedisServer.Launcher.redis` | `bluetape4k-testcontainers` | `AbstractDistributedLockTest` companion | Testcontainers Redis 싱글턴으로 `@DynamicPropertySource` 없이 테스트 Redis 구동 |
| `redissonClient {}` DSL | `bluetape4k-redisson` | `AbstractDistributedLockTest.redisson` | `RedissonClient` 설정을 Kotlin DSL로 구성 |
| `getLockId(lockName)` | `bluetape4k-redis` | `SuspendingFencedInventoryService.deduct()` | 코루틴 안전 RFencedLock identity 생성 |
| `requirePositiveNumber` | `bluetape4k-core` | `SuspendingFencedInventoryService.deduct()` | 입력 검증 실패를 명확한 `IllegalArgumentException`으로 표현 |
| `SuspendedJobTester` | `bluetape4k-junit5` | `SuspendFencedLockTest` | 재현 가능한 코루틴 경쟁 조건 검증 |
| `MultithreadingTester` | `bluetape4k-junit5` | `DistributedLockTest` | OS thread 기반 분산 락 동시성 검증 |
