package io.bluetape4k.workshop.lock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Distributed Lock Workshop application.
 *
 * Demonstrates four concurrency control strategies using Redis/Redisson:
 *
 * 1. **Unsafe** — no synchronization; race condition demo
 * 2. **Locked** — mutual exclusion via [org.redisson.api.RLock]
 * 3. **Fenced** — fencing token guard via [org.redisson.api.RFencedLock] (blocking)
 * 4. **Suspending Fenced** — coroutine-safe fenced lock with `NonCancellable` unlock
 *
 * ## Running
 * ```
 * ./gradlew :redis-distributed-lock:bootRun
 * ./gradlew :redis-distributed-lock:test
 * ```
 */
@SpringBootApplication
class DistributedLockApp

fun main(args: Array<String>) {
    runApplication<DistributedLockApp>(*args)
}
