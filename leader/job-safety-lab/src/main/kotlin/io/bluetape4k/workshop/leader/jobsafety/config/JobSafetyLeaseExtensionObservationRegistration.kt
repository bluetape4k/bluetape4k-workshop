package io.bluetape4k.workshop.leader.jobsafety.config

import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.MicrometerObservationLeaderLeaseExtensionObserver
import io.micrometer.observation.ObservationRegistry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * job-safety가 소유하는 lease-extension observation 수명입니다.
 *
 * 현재 `2.0.0-SNAPSHOT` consumer artifact가 공개한 global observer만 사용합니다.
 * disabled/NOOP에서는 observer registration이 없습니다.
 */
class JobSafetyLeaseExtensionObservation(
    registry: ObservationRegistry,
    options: LeaderObservationOptions,
    enabled: Boolean = true,
) : AutoCloseable {

    private val registration =
        if (enabled && !registry.isNoop) {
            LeaderLeaseExtensionObservers.addObserver(
                MicrometerObservationLeaderLeaseExtensionObserver(registry, options),
            )
        } else {
            null
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            registration?.close()
        }
    }

    private val closed = AtomicBoolean(false)

    internal val isClosed: Boolean
        get() = closed.get()
}
