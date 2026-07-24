package io.bluetape4k.workshop.commerce.voucher.eventsourced.operations

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class EventSourcedDatabaseLane {
    FOREGROUND,
    PROJECTION,
    REBUILD,
    MAINTENANCE,
    READINESS,
}

internal class DatabaseBulkheadRejected(
    val retryAfter: Duration = RETRY_AFTER,
    cause: Throwable? = null,
) : RuntimeException("DATABASE_BULKHEAD_REJECTED", cause) {
    companion object {
        private val RETRY_AFTER: Duration = Duration.ofSeconds(1)
    }
}

internal fun interface EventSourcedDatabasePermitMetrics {
    fun rejected(lane: EventSourcedDatabaseLane)

    companion object {
        val NONE = EventSourcedDatabasePermitMetrics {}
    }
}

@ConsistentCopyVisibility
internal data class EventSourcedDatabasePermitBudget private constructor(
    val foreground: Int,
    val projection: Int,
    val rebuild: Int,
    val maintenance: Int,
    val readiness: Int,
) {
    companion object {
        private const val HIKARI_CONNECTION_BUDGET = 20
        internal const val MAX_FOREGROUND_PARTICIPANTS = 128

        operator fun invoke(
            foreground: Int = 14,
            projection: Int = 3,
            rebuild: Int = 1,
            maintenance: Int = 1,
            readiness: Int = 1,
        ): EventSourcedDatabasePermitBudget {
            val validForeground = foreground.requirePositiveNumber("foreground")
            val validProjection = projection.requirePositiveNumber("projection")
            val validRebuild = rebuild.requirePositiveNumber("rebuild")
            val validMaintenance = maintenance.requirePositiveNumber("maintenance")
            val validReadiness = readiness.requirePositiveNumber("readiness")
            (validForeground + validProjection + validRebuild + validMaintenance + validReadiness)
                .requireEquals(HIKARI_CONNECTION_BUDGET, "permitCapacity.sum")
            return EventSourcedDatabasePermitBudget(
                validForeground,
                validProjection,
                validRebuild,
                validMaintenance,
                validReadiness,
            )
        }
    }
}

internal data class EventSourcedDatabasePermitSnapshot(
    val available: Int,
    val active: Int,
    val queued: Int,
    val rejected: Long,
)

/**
 * Reserves the Hikari 20-connection budget before a virtual thread can request JDBC access.
 * The independent readiness lane remains available while foreground traffic is saturated.
 */
internal class EventSourcedDatabasePermitGate(
    private val budget: EventSourcedDatabasePermitBudget = EventSourcedDatabasePermitBudget(),
    acquireTimeout: Duration = DEFAULT_ACQUIRE_TIMEOUT,
    private val metrics: EventSourcedDatabasePermitMetrics = EventSourcedDatabasePermitMetrics.NONE,
) {
    private val acquireTimeout = acquireTimeout.requireGt(Duration.ZERO, "acquireTimeout")
    private val capacities =
        mapOf(
            EventSourcedDatabaseLane.FOREGROUND to budget.foreground,
            EventSourcedDatabaseLane.PROJECTION to budget.projection,
            EventSourcedDatabaseLane.REBUILD to budget.rebuild,
            EventSourcedDatabaseLane.MAINTENANCE to budget.maintenance,
            EventSourcedDatabaseLane.READINESS to budget.readiness,
        )
    private val semaphores = capacities.mapValues { (_, permits) -> Semaphore(permits, true) }
    private val heldLane = ThreadLocal<EventSourcedDatabaseLane?>()
    private val lifecycleLock = ReentrantLock()
    private val drained = lifecycleLock.newCondition()
    private val foregroundParticipants = AtomicInteger()
    private val rejectionCounts = EventSourcedDatabaseLane.entries.associateWith { AtomicLong() }

    @Volatile
    private var accepting = true

    private var activePermits = 0

    fun <T> withPermit(
        lane: EventSourcedDatabaseLane,
        block: () -> T,
    ): T {
        val lease = acquire(lane)
        return try {
            block()
        } finally {
            lease.release()
        }
    }

    fun beginShutdown() {
        lifecycleLock.withLock {
            accepting = false
            if (activePermits == 0) drained.signalAll()
        }
    }

    fun awaitDrained(timeout: Duration): Boolean {
        val validTimeout = timeout.requireGe(Duration.ZERO, "timeout")
        var remaining = validTimeout.toNanos()
        lifecycleLock.withLock {
            while (activePermits > 0 && remaining > 0) {
                remaining = drained.awaitNanos(remaining)
            }
            return activePermits == 0
        }
    }

    fun snapshot(lane: EventSourcedDatabaseLane): EventSourcedDatabasePermitSnapshot {
        val available = semaphores.getValue(lane).availablePermits()
        return EventSourcedDatabasePermitSnapshot(
            available = available,
            active = capacities.getValue(lane) - available,
            queued = semaphores.getValue(lane).queueLength,
            rejected = rejectionCounts.getValue(lane).get(),
        )
    }

    private fun acquire(lane: EventSourcedDatabaseLane): PermitLease {
        ensureAccepting(lane)
        check(heldLane.get() == null) { "nested database permit acquisition is forbidden" }
        val registeredForeground = registerForegroundParticipant(lane)
        val semaphore = semaphores.getValue(lane)
        val acquired = acquireSemaphore(lane, semaphore)
        if (!acquired) {
            unregisterForegroundParticipant(registeredForeground)
            throw reject(lane)
        }
        lifecycleLock.withLock {
            if (!accepting) {
                semaphore.release()
                unregisterForegroundParticipant(registeredForeground)
                throw reject(lane)
            }
            activePermits++
        }
        heldLane.set(lane)
        return PermitLease(lane, semaphore, registeredForeground)
    }

    private fun acquireSemaphore(lane: EventSourcedDatabaseLane, semaphore: Semaphore): Boolean =
        try {
            semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw reject(lane, interrupted)
        }

    private fun registerForegroundParticipant(lane: EventSourcedDatabaseLane): Boolean {
        if (lane != EventSourcedDatabaseLane.FOREGROUND) return false
        if (foregroundParticipants.incrementAndGet() <=
            EventSourcedDatabasePermitBudget.MAX_FOREGROUND_PARTICIPANTS
        ) {
            return true
        }
        foregroundParticipants.decrementAndGet()
        throw reject(lane)
    }

    private fun unregisterForegroundParticipant(registered: Boolean) {
        if (registered) foregroundParticipants.decrementAndGet()
    }

    private fun ensureAccepting(lane: EventSourcedDatabaseLane) {
        if (!accepting) throw reject(lane)
    }

    private fun reject(
        lane: EventSourcedDatabaseLane,
        cause: Throwable? = null,
    ): DatabaseBulkheadRejected {
        rejectionCounts.getValue(lane).incrementAndGet()
        metrics.rejected(lane)
        log.debug { "event_sourced_db_permit_rejected lane=$lane" }
        return DatabaseBulkheadRejected(cause = cause)
    }

    private inner class PermitLease(
        private val lane: EventSourcedDatabaseLane,
        private val semaphore: Semaphore,
        private val registeredForeground: Boolean,
    ) {
        fun release() {
            heldLane.remove()
            semaphore.release()
            unregisterForegroundParticipant(registeredForeground)
            lifecycleLock.withLock {
                activePermits--
                if (activePermits == 0) drained.signalAll()
            }
            log.debug { "event_sourced_db_permit_released lane=$lane" }
        }
    }

    private companion object : KLogging() {
        private val DEFAULT_ACQUIRE_TIMEOUT: Duration = Duration.ofMillis(250)
    }
}
