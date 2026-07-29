package io.bluetape4k.workshop.leader.job

import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.locks.LockSupport

/**
 * leader-only work를 simulation하기 위해 현재 blocking worker를 대기시킵니다.
 */
internal fun simulateBlockingWork(duration: Duration) {
    val nanoseconds = duration.toNanos()
    nanoseconds.requirePositiveNumber("duration.nanos")
    LockSupport.parkNanos(nanoseconds)
}
