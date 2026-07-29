package io.bluetape4k.workshop.lock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Distributed Lock Workshop application 입니다.
 *
 * Redis/Redisson 으로 네 가지 concurrency control strategy 를 보여줍니다.
 *
 * 1. **Unsafe** — synchronization 이 없는 race condition demo 입니다.
 * 2. **Locked** — [org.redisson.api.RLock] 기반 mutual exclusion 입니다.
 * 3. **Fenced** — [org.redisson.api.RFencedLock] 기반 fencing token guard 입니다(blocking).
 * 4. **Suspending Fenced** — `NonCancellable` unlock 을 사용하는 coroutine-safe fenced lock 입니다.
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
