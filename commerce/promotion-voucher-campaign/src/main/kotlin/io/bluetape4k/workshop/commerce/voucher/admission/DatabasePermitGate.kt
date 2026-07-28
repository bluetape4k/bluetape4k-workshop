package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class DatabaseLane {
    FOREGROUND,
    WORKER,
    SSE_MAINTENANCE,
}

internal class DatabasePermitRejected(
    val retryAfter: Duration,
    cause: Throwable? = null,
) : RuntimeException("database permit unavailable", cause)

internal fun interface DatabasePermitMetrics {
    fun rejected(lane: DatabaseLane)

    companion object {
        val NONE = DatabasePermitMetrics {}
    }
}

/**
 * virtual-thread concurrency를 bounded JDBC connection pool 바깥에서 제어합니다.
 *
 * 각 lane은 fair semaphore를 소유하므로 interactive traffic, reconciliation worker, SSE maintenance가
 * 서로의 예약된 database capacity를 소비하지 못합니다.
 */
internal class DatabasePermitGate(
    foregroundPermits: Int = 12,
    workerPermits: Int = 1,
    sseMaintenancePermits: Int = 3,
    private val acquireTimeout: Duration = Duration.ofMillis(250),
    private val metrics: DatabasePermitMetrics = DatabasePermitMetrics.NONE,
) {
    private val permitCapacities =
        mapOf(
            DatabaseLane.FOREGROUND to foregroundPermits.requirePositiveNumber("foregroundPermits"),
            DatabaseLane.WORKER to workerPermits.requirePositiveNumber("workerPermits"),
            DatabaseLane.SSE_MAINTENANCE to sseMaintenancePermits.requirePositiveNumber("sseMaintenancePermits"),
        )
    private val semaphores =
        permitCapacities.mapValues { (_, permits) -> Semaphore(permits, true) }
    private val heldLane = ThreadLocal<DatabaseLane?>()
    private val lifecycleLock = ReentrantLock()
    private val drained = lifecycleLock.newCondition()

    @Volatile
    private var accepting = true

    private var activePermits = 0

    init {
        require(!acquireTimeout.isNegative && !acquireTimeout.isZero) {
            "acquireTimeout must be positive"
        }
    }

    fun <T> withPermit(
        lane: DatabaseLane,
        block: () -> T,
    ): T {
        if (!accepting) throw DatabasePermitRejected(RETRY_AFTER)
        check(heldLane.get() == null) { "nested database permit acquisition is forbidden" }
        val semaphore = semaphores.getValue(lane)
        val acquired =
            try {
                semaphore.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw DatabasePermitRejected(RETRY_AFTER, interrupted)
            }
        if (!acquired) {
            metrics.rejected(lane)
            log.debug { "voucher_db_permit_rejected lane=$lane" }
            throw DatabasePermitRejected(RETRY_AFTER)
        }

        lifecycleLock.withLock {
            if (!accepting) {
                semaphore.release()
                metrics.rejected(lane)
                throw DatabasePermitRejected(RETRY_AFTER)
            }
            activePermits++
        }

        heldLane.set(lane)
        return try {
            block()
        } finally {
            heldLane.remove()
            semaphore.release()
            lifecycleLock.withLock {
                activePermits--
                if (activePermits == 0) drained.signalAll()
            }
            log.debug { "voucher_db_permit_released lane=$lane" }
        }
    }

    /** 이미 admission된 work는 drain되도록 두면서 이후 acquisition을 atomic하게 거부합니다. */
    fun beginShutdown() {
        lifecycleLock.withLock {
            accepting = false
            if (activePermits == 0) drained.signalAll()
        }
    }

    fun awaitDrained(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        var remaining = timeout.toNanos()
        lifecycleLock.withLock {
            while (activePermits > 0 && remaining > 0) {
                remaining = drained.awaitNanos(remaining)
            }
            return activePermits == 0
        }
    }

    fun inUsePermits(): Int = lifecycleLock.withLock { activePermits }

    fun requireHeld() {
        check(heldLane.get() != null) { "JDBC access requires a database permit" }
    }

    /** transaction facade가 permit을 다시 얻지 않고 자신의 foreground boundary에 참여할 수 있게 합니다. */
    fun isHeld(lane: DatabaseLane): Boolean = heldLane.get() == lane

    /** health probe와 결정적 leak test를 위해 lane-local capacity를 노출합니다. */
    fun availablePermits(lane: DatabaseLane): Int = semaphores.getValue(lane).availablePermits()

    /** permit을 얻지 않고 lane-local occupancy를 노출합니다. */
    fun inUsePermits(lane: DatabaseLane): Int = permitCapacities.getValue(lane) - availablePermits(lane)

    /** bounded operational sampling을 위해 fair semaphore queue depth를 노출합니다. */
    fun waitingThreads(lane: DatabaseLane): Int = semaphores.getValue(lane).queueLength

    companion object : KLogging() {
        private val RETRY_AFTER: Duration = Duration.ofSeconds(1)
    }
}
