package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** 이 process가 traffic을 받기 전에 PostgreSQL authority를 검증합니다. */
internal fun interface EventSourcedStartupProbe {
    fun verify()
}

internal interface EventSourcedRuntimeWorkers {
    fun start()

    fun stopProjection()

    fun stopRebuild()

    fun stopMaintenance()

    fun releaseFencedLeases()
}

/**
 * application readiness transition을 소유하고,
 * Spring이 shared infrastructure를 destroy하기 전에 graceful coordinator를 호출합니다.
 */
@Component
internal class EventSourcedApplicationLifecycle(
    private val applicationContext: ApplicationContext,
    private val state: EventSourcedOperationalState,
    gate: EventSourcedDatabasePermitGate,
    private val probe: EventSourcedStartupProbe,
    private val workers: EventSourcedRuntimeWorkers,
) : SmartLifecycle {
    private val running = AtomicBoolean()
    private val coordinator =
        EventSourcedLifecycleCoordinator(
            state,
            gate,
            EventSourcedWorkerShutdown(
                projection = workers::stopProjection,
                rebuild = workers::stopRebuild,
                maintenance = workers::stopMaintenance,
                releaseFencedLeases = workers::releaseFencedLeases,
            ),
        )

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        runCatching(probe::verify)
            .onSuccess {
                workers.start()
                state.markReady()
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC)
            }.onFailure { failure ->
                state.markAuthorityFailure()
                AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
                log.warn { "event_sourced_startup_authority_probe_failed cause=${failure.javaClass.simpleName}" }
            }
    }

    override fun stop(callback: Runnable) {
        if (running.compareAndSet(true, false)) {
            state.beginShutdown()
            AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC)
            coordinator.shutdown()
        }
        callback.run()
    }

    override fun stop() = stop {}

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - LIFECYCLE_PHASE_OFFSET

    private companion object : KLogging() {
        private const val LIFECYCLE_PHASE_OFFSET = 100
    }
}
