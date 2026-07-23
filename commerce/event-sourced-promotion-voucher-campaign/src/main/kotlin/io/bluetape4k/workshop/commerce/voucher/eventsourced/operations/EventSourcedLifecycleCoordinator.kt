package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class EventSourcedShutdownEvent {
    READINESS_DOWN,
    REJECT_ADMISSION,
    STOP_PROJECTION,
    STOP_REBUILD,
    STOP_MAINTENANCE,
    AWAIT_DRAIN,
    RELEASE_FENCED_LEASES,
}

internal data class EventSourcedWorkerShutdown(
    val projection: () -> Unit,
    val rebuild: () -> Unit,
    val maintenance: () -> Unit,
    val releaseFencedLeases: () -> Unit,
)

/** Coordinates graceful shutdown without letting stale workers release a newer lease. */
internal class EventSourcedLifecycleCoordinator(
    private val state: EventSourcedOperationalState,
    private val gate: EventSourcedDatabasePermitGate,
    private val workers: EventSourcedWorkerShutdown,
    private val graceDeadline: Duration = DEFAULT_GRACE_DEADLINE,
) {
    private val shuttingDown = AtomicBoolean()
    private val eventLock = ReentrantLock()
    private val recordedEvents = mutableListOf<EventSourcedShutdownEvent>()

    @Volatile
    private var drainForced = false

    init {
        require(!graceDeadline.isNegative && !graceDeadline.isZero) { "graceDeadline must be positive" }
    }

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + graceDeadline.toNanos()
        step(EventSourcedShutdownEvent.READINESS_DOWN, state::beginShutdown)
        step(EventSourcedShutdownEvent.REJECT_ADMISSION, gate::beginShutdown)
        step(EventSourcedShutdownEvent.STOP_PROJECTION, workers.projection)
        step(EventSourcedShutdownEvent.STOP_REBUILD, workers.rebuild)
        step(EventSourcedShutdownEvent.STOP_MAINTENANCE, workers.maintenance)
        record(EventSourcedShutdownEvent.AWAIT_DRAIN)
        if (!gate.awaitDrained(remaining(deadline))) {
            drainForced = true
            log.warn { "event_sourced_shutdown_drain_deadline_exceeded" }
        }
        step(EventSourcedShutdownEvent.RELEASE_FENCED_LEASES, workers.releaseFencedLeases)
    }

    fun events(): List<EventSourcedShutdownEvent> = eventLock.withLock { recordedEvents.toList() }

    fun forcedDrain(): Boolean = drainForced

    private fun step(
        event: EventSourcedShutdownEvent,
        action: () -> Unit,
    ) {
        record(event)
        action()
    }

    private fun record(event: EventSourcedShutdownEvent) {
        eventLock.withLock { recordedEvents += event }
    }

    private fun remaining(deadline: Long): Duration = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(0))

    private companion object : KLogging() {
        private val DEFAULT_GRACE_DEADLINE: Duration = Duration.ofSeconds(10)
    }
}
