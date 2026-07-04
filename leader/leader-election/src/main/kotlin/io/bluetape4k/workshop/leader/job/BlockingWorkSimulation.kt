package io.bluetape4k.workshop.leader.job

import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/**
 * Parks the current blocking worker to simulate leader-only work.
 */
internal fun simulateBlockingWork(duration: Duration) {
    val nanoseconds = duration.toNanos()
    nanoseconds.requirePositiveNumber("duration.nanos")
    LockSupport.parkNanos(nanoseconds)
}
