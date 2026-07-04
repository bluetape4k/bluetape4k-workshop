package io.bluetape4k.workshop.resilience

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration

/**
 * Simulates blocking backend latency for Resilience4j timeout examples.
 */
internal fun simulateBlockingLatency(duration: Duration) {
    LockSupport.parkNanos(duration.inWholeNanoseconds)
}
