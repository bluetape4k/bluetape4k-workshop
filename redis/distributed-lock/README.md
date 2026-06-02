# redis-distributed-lock

[한국어](README.ko.md) | English

## Example Scenario

This example exercises **redis-distributed-lock** as a runnable Redis-backed coordination workshop slice. It focuses on the path a developer would inspect first: configure the module, run the sample or tests, and observe the library or framework APIs that remove repetitive infrastructure code.

## Flow Diagram

1. Prepare the local runtime required by `redis-distributed-lock`.
2. Execute the application, controller, service, or test fixture that owns the example scenario.
3. Delegate repetitive infrastructure work to bluetape4k utilities or Spring/Kotlin integrations.
4. Assert the visible result through the sample output, HTTP response, repository state, metric, trace, or test expectation.

## Sequence Diagram

The core sequence is: caller or test fixture -> workshop adapter -> bluetape4k helper/API -> external runtime or in-memory backend -> assertion/response. When this module has a dedicated sequence asset, the image below shows that interaction order; otherwise the source tests are the authoritative executable sequence.

Demonstrates distributed locking strategies using Redisson, from unsafe shared mutable state through
thread-safe locks to fenced (token-based) locks and their coroutine-native counterpart.

---

## Architecture

![redis-distributed-lock Graphviz architecture diagram](../../docs/images/readme-diagrams/redis-distributed-lock-readme-architecture-01.png)

![distributed lock Architecture diagram](../../docs/images/readme-diagrams/redis-distributed-lock-architecture-01.png)

---

## Core Features

| Feature | Class | Key API |
|---|---|---|
| Unsafe (race demo) | `UnsafeInventoryService` | No lock; shows oversell |
| Distributed lock | `LockedInventoryService` | `RLock.tryLock(wait, lease, unit)` |
| Fenced lock (blocking) | `FencedInventoryService` | `RFencedLock.tryLockAndGetToken(wait, lease, unit)` |
| Fenced lock (coroutine) | `SuspendingFencedInventoryService` | `tryLockAsync` + `tokenAsync` + `NonCancellable` unlock |
| Token guard | `FencedResource` | CAS `lastSeenToken`; rejects stale-holder writes |

---

## Module Structure

```
redis/distributed-lock/
├── src/main/kotlin/io/bluetape4k/workshop/lock/
│   ├── DistributedLockApp.kt          # Spring Boot entry point
│   ├── domain/
│   │   ├── DeductionResult.kt         # sealed interface: Success / InsufficientStock / LockTimeout / Error
│   │   ├── Inventory.kt               # data class: id, name, stock
│   │   └── InventoryStore.kt          # in-memory concurrent store
│   ├── fenced/
│   │   ├── FencedResource.kt          # CAS token gate for one resource
│   │   └── FencedResources.kt         # ConcurrentHashMap registry
│   └── service/
│       ├── UnsafeInventoryService.kt
│       ├── LockedInventoryService.kt
│       ├── FencedInventoryService.kt
│       └── SuspendingFencedInventoryService.kt
└── src/test/kotlin/io/bluetape4k/workshop/lock/
    ├── AbstractDistributedLockTest.kt  # shared fixtures (Testcontainers Redis)
    ├── BaselineRaceTest.kt             # proves oversell without lock
    ├── DistributedLockTest.kt          # RLock prevents oversell
    ├── FencedLockTest.kt               # fenced guard; stale-holder rejection
    ├── SuspendFencedLockTest.kt        # coroutine safety + cancel-safety
    ├── FencedStaleHolderTest.kt        # [smoke] lease expiry re-lock scenario
    └── LockFailureTest.kt              # [smoke] tryLock timeout behaviour
```

---

## Usage Examples

### 1. Unsafe — proves race condition

```kotlin
// Stock = 100, qty = 10, 20 concurrent goroutines
// → oversell: successCount > 10
val result = unsafeService.deduct(inventoryId, 10)
```

### 2. Distributed Lock (blocking)

```kotlin
// RLock ensures exactly one deduction at a time
val result = lockedService.deduct(inventoryId, qty = 10, waitMs = 3000L, leaseMs = 3000L)
when (result) {
    is DeductionResult.Success          -> println("deducted token=${result.token}")
    is DeductionResult.InsufficientStock -> println("out of stock")
    is DeductionResult.LockTimeout      -> println("lock timed out")
    is DeductionResult.Error            -> println("error: ${result.message}")
}
```

### 3. Fenced Lock — guards against stale holders

The `FencedResource` CAS gate rejects any write whose fencing token is older than
the current `lastSeenToken`, preventing a slow/restarted lock-holder from
overwriting data written by a newer holder.

```kotlin
// FencedInventoryService internally:
val token: Long = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)
val result: DeductionResult? = resource.apply(token) {
    store.deduct(inventoryId, qty)
}
// null result → stale holder rejected
```

### 4. Coroutine-Native Fenced Lock

```kotlin
// Coroutine-safe: NonCancellable unlock prevents lock leak on Job.cancel()
val result = suspendingService.deduct(inventoryId, qty = 10, waitMs = 3000L, leaseMs = 3000L)
```

#### Cancellation Safety

The `SuspendingFencedInventoryService` uses a `withContext(NonCancellable)` block in its
`finally` clause so that `unlockAsync(lockId).await()` always completes even when the
calling coroutine is cancelled mid-flight.

```kotlin
// Simplified internal pattern
val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
if (!acquired) return LockTimeout
val token = fLock.tokenAsync.await()
try {
    /* work */
} finally {
    withContext(NonCancellable) {
        fLock.unlockAsync(lockId).await()
    }
}
```

---

## Running Tests

```bash
# All non-smoke tests (default)
./gradlew :redis-distributed-lock:test

# Force fresh run
./gradlew :redis-distributed-lock:test --rerun-tasks

# Include smoke tests (timing-sensitive: lease expiry scenarios)
./gradlew :redis-distributed-lock:test -Djunit.jupiter.execution.exclude.tags=

# Specific test class
./gradlew :redis-distributed-lock:test --tests "io.bluetape4k.workshop.lock.SuspendFencedLockTest"
```

> **Smoke tests** (`@Tag("smoke")`) test lease-expiry and timeout scenarios with real
> wall-clock delays. They are excluded from the default CI run to avoid flakiness.

---

## Key Concepts

### Fencing Token Protocol

A fencing token is a monotonically increasing number issued by the lock server on
each successful lock acquisition. Any resource update must carry the token; the resource
guard (CAS on `lastSeenToken`) rejects writes from older tokens.

This prevents the classic "process paused after lock acquired but before write" race:

```
Client A acquires lock → token=1
Client A pauses (GC, network delay, lease expires)
Client B acquires lock → token=2, writes with token=2
Client A resumes → FencedResource rejects write (1 < 2) ✅
```

### SuspendedJobTester Semantics

`SuspendedJobTester.workers(N).rounds(R)` spawns `N` concurrent workers that
cooperatively execute `R` rounds of each `add { }` block. `totalUnits = R × blockCount`.

For a stock-100/qty-10 test:
- `workers(20).rounds(20)` → 20 total attempts → exactly 10 succeed

### Smoke vs Default Tests

| Tag | Purpose | Includes timing? | Default CI |
|---|---|---|---|
| _(none)_ | Fast correctness checks | No (logic only) | ✅ included |
| `smoke` | Lease expiry, timeout | Yes (real wall-clock) | ❌ excluded |

---

## Used bluetape4k Features

| Feature | Artifact | Code Location | Benefit |
|---|---|---|---|
| `RedisServer.Launcher.redis` | `bluetape4k-testcontainers` | `AbstractDistributedLockTest` companion | Testcontainers Redis singleton — one-line setup, no `@DynamicPropertySource` |
| `redissonClient {}` DSL | `bluetape4k-redisson` | `AbstractDistributedLockTest.redisson` | Kotlin DSL for `RedissonClient` — eliminates `Config()` boilerplate |
| `getLockId(lockName)` | `bluetape4k-redis` (`coroutines` package) | `SuspendingFencedInventoryService.deduct()` | Coroutine-safe Snowflake ID for `RFencedLock` identity — required for two-step async acquire |
| `KLoggingChannel` | `bluetape4k-logging` | All companion objects | Coroutine-context-aware structured logging |
| `requirePositiveNumber` | `bluetape4k-core` | `SuspendingFencedInventoryService.deduct()` | Inline argument validation — throws `IllegalArgumentException` with a clear message |
| `SuspendedJobTester` | `bluetape4k-junit5` | `SuspendFencedLockTest` | Reproducible coroutine concurrency harness — deterministic race verification |
| `MultithreadingTester` | `bluetape4k-junit5` | `DistributedLockTest` | Fixed-thread-pool concurrency verification for OS-thread lock scenarios |

---

## bluetape4k Before / After

### `RedisServer.Launcher` vs Manual Testcontainers Redis

```kotlin
// Before — manual container + @DynamicPropertySource
@SpringBootTest
class LockTest {
    companion object {
        @Container
        val redis = GenericContainer("redis:7").withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.redis.url") { "redis://${redis.host}:${redis.getMappedPort(6379)}" }
        }
    }
}

// After — bluetape4k singleton (one line in AbstractDistributedLockTest)
abstract class AbstractDistributedLockTest {
    companion object : KLoggingChannel() {
        val redis = RedisServer.Launcher.redis          // auto-started, auto-cleaned up
        val redisUrl: String get() = redis.url
    }

    protected val redisson: RedissonClient by lazy {
        redissonClient {                                // bluetape4k DSL
            useSingleServer().setAddress(redisUrl)
        }
    }
}
```

### Coroutine-Native Fenced Lock — Two-Step Acquire + `NonCancellable` Unlock

```kotlin
// Before — blocking tryLockAndGetToken (blocks the calling thread)
val token: Long = fLock.tryLockAndGetToken(waitMs, leaseMs, MILLISECONDS)
try {
    // work
} finally {
    fLock.unlock()  // may throw if coroutine was cancelled before this line
}

// After — suspend-friendly two-step acquire with NonCancellable unlock guard
val lockId = redisson.getLockId(lockName)           // bluetape4k: Snowflake ID for coroutine identity
val acquired = fLock.tryLockAsync(waitMs, leaseMs, MILLISECONDS, lockId).await()
if (!acquired) return LockNotAcquired(lockName)
val token: Long = fLock.tokenAsync.await()          // separate token step (no combined overload in Redisson 4.x)
try {
    /* work */
} finally {
    // CRITICAL: without NonCancellable, a cancelled coroutine never completes unlockAsync
    // and the lock leaks until lease expiry
    withContext(NonCancellable) {
        fLock.unlockAsync(lockId).await()
    }
}
```

### `SuspendedJobTester` — Reproducible Coroutine Race Verification

```kotlin
// Before — manual coroutine launch (non-deterministic, hard to tune worker count)
val results = (1..20).map {
    async { suspendingService.deduct(inventoryId, qty = 10) }
}.awaitAll()

// After — bluetape4k SuspendedJobTester (fixed rounds × blockCount = total attempts)
SuspendedJobTester()
    .workers(20)    // 20 coroutine workers
    .rounds(20)     // 20 total attempts, exactly 10 succeed (stock=100, qty=10)
    .add {
        suspendingService.deduct(inventoryId, qty = 10, waitMs = 3000L, leaseMs = 5000L)
    }
    .run()
```

---

## Dependencies

- **Redisson** — `RLock`, `RFencedLock`, async API
- **bluetape4k-redisson** — `redissonClient {}` DSL
- **bluetape4k-redis** — `getLockId()` coroutines extension (`io.bluetape4k.redis.redisson.coroutines`)
- **bluetape4k-coroutines** — `awaitSuspending()`, `io.bluetape4k.logging.coroutines`
- **bluetape4k-testcontainers** — `RedisServer.Launcher.redis` Testcontainers singleton
- **bluetape4k-junit5** — `SuspendedJobTester`, `MultithreadingTester`
- **kotlinx.atomicfu** — `atomic(0L)` for `FencedResource.lastSeenToken`
