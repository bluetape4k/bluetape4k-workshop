package io.bluetape4k.workshop.resilience

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration

/**
 * Resilience4j timeout 예제를 위해 blocking backend latency 를 simulate 합니다.
 */
internal fun simulateBlockingLatency(duration: Duration) {
    LockSupport.parkNanos(duration.inWholeNanoseconds)
}
