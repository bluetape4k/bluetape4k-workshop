package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration as JavaDuration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_FOREGROUND_CAPACITY = 11
private const val DEFAULT_WORKER_CAPACITY = 1
private const val DEFAULT_SSE_CAPACITY = 3
private const val DEFAULT_FOREGROUND_WAIT_MILLIS = 200
private const val RESERVED_UNGATED_CONNECTIONS = 1

internal enum class PermitLane {
    FOREGROUND,
    WORKER,
    SSE,
}

internal data class PermitLaneConfig(
    val capacity: Int,
    val wait: Duration,
)

internal data class PermitSnapshot(
    val foregroundInUse: Int,
    val workerInUse: Int,
    val sseInUse: Int,
    val capacities: Map<PermitLane, Int>,
    val waits: Map<PermitLane, Duration>,
    val observedWaitMax: Map<PermitLane, Duration>,
    val observedWaitSamples: Map<PermitLane, Long>,
)

internal class PoolBusyException(
    val lane: PermitLane,
    val interrupted: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException("database permit unavailable for lane=$lane", cause)

internal class NestedPermitException(
    val activeLane: PermitLane,
    val requestedLane: PermitLane,
) : IllegalStateException("nested database permit acquisition is forbidden: $activeLane -> $requestedLane")

/** Keeps virtual-thread concurrency within the bounded PostgreSQL connection budget. */
@Suppress("TooManyFunctions")
internal class DatabasePermitGate(
    hikariMaximumPoolSize: Int,
    configs: Map<PermitLane, PermitLaneConfig>,
) {
    private val configs: Map<PermitLane, PermitLaneConfig> = configs.toMap()
    private val permits: Map<PermitLane, Semaphore>
    private val activeLane = ThreadLocal<PermitLane>()
    private val activeThreads = ConcurrentHashMap.newKeySet<Thread>()
    private val waitSamples = PermitLane.entries.associateWith { AtomicLong() }
    private val waitMaxNanos = PermitLane.entries.associateWith { AtomicLong() }
    private val accepting = AtomicBoolean(true)
    private val drainLock = ReentrantLock()
    private val drained = drainLock.newCondition()

    init {
        require(hikariMaximumPoolSize > 0) { "hikariMaximumPoolSize must be positive" }
        require(this.configs.keys == PermitLane.entries.toSet()) { "all permit lanes must be configured" }
        require(this.configs.values.all { it.capacity > 0 }) { "permit capacities must be positive" }
        require(this.configs.values.all { it.wait > Duration.ZERO }) { "permit waits must be positive" }
        val admittedCapacity = hikariMaximumPoolSize - RESERVED_UNGATED_CONNECTIONS
        require(this.configs.values.sumOf(PermitLaneConfig::capacity) <= admittedCapacity) {
            "permit capacity must leave one Hikari connection for readiness and lifecycle probes"
        }
        permits = this.configs.mapValues { (_, config) -> Semaphore(config.capacity, true) }
    }

    fun <T> withForegroundPermit(block: () -> T): T = withPermit(PermitLane.FOREGROUND, block)

    fun <T> withWorkerPermit(block: () -> T): T = withPermit(PermitLane.WORKER, block)

    fun <T> withSsePermit(block: () -> T): T = withPermit(PermitLane.SSE, block)

    fun snapshot(): PermitSnapshot =
        PermitSnapshot(
            foregroundInUse = inUse(PermitLane.FOREGROUND),
            workerInUse = inUse(PermitLane.WORKER),
            sseInUse = inUse(PermitLane.SSE),
            capacities = configs.mapValues { (_, config) -> config.capacity },
            waits = configs.mapValues { (_, config) -> config.wait },
            observedWaitMax = waitMaxNanos.mapValues { (_, value) -> value.get().nanoseconds },
            observedWaitSamples = waitSamples.mapValues { (_, value) -> value.get() },
        )

    fun resetWaitObservations() {
        check(isDrained()) { "permit wait observations can only be reset while the gate is drained" }
        waitSamples.values.forEach { it.set(0L) }
        waitMaxNanos.values.forEach { it.set(0L) }
    }

    fun beginShutdown() {
        accepting.set(false)
        signalIfDrained()
    }

    fun isAccepting(): Boolean = accepting.get()

    fun isDrained(): Boolean = inUseTotal() == 0

    fun cancelActiveTransactions(): Int {
        val threads = activeThreads.toList()
        threads.forEach(Thread::interrupt)
        return threads.size
    }

    fun awaitDrained(timeout: JavaDuration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        var remaining = timeout.toNanos()
        drainLock.withLock {
            while (inUseTotal() > 0 && remaining > 0L) {
                remaining = drained.awaitNanos(remaining)
            }
            return inUseTotal() == 0
        }
    }

    private fun <T> withPermit(
        lane: PermitLane,
        block: () -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "database permit must be acquired before starting a transaction"
        }
        if (!accepting.get()) throw PoolBusyException(lane)
        activeLane.get()?.let { held -> throw NestedPermitException(held, lane) }
        val semaphore = permits.getValue(lane)
        val wait = configs.getValue(lane).wait
        acquirePermit(lane, semaphore, wait)

        activeLane.set(lane)
        activeThreads += Thread.currentThread()
        return try {
            block()
        } finally {
            activeThreads -= Thread.currentThread()
            activeLane.remove()
            semaphore.release()
            signalIfDrained()
            log.debug { "voucher_pool_db_permit_released lane=$lane" }
        }
    }

    @Suppress("ThrowsCount")
    private fun acquirePermit(
        lane: PermitLane,
        semaphore: Semaphore,
        wait: Duration,
    ) {
        val startedAt = System.nanoTime()
        val acquired =
            try {
                semaphore.tryAcquire(wait.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                log.debug { "voucher_pool_db_permit_interrupted lane=$lane" }
                throw PoolBusyException(lane = lane, interrupted = true, cause = interrupted)
            } finally {
                recordWait(lane, System.nanoTime() - startedAt)
            }
        if (!acquired) {
            log.debug { "voucher_pool_db_permit_timed_out lane=$lane" }
            throw PoolBusyException(lane)
        }
        if (!accepting.get()) {
            semaphore.release()
            signalIfDrained()
            throw PoolBusyException(lane)
        }
    }

    private fun recordWait(lane: PermitLane, elapsedNanos: Long) {
        waitSamples.getValue(lane).incrementAndGet()
        waitMaxNanos.getValue(lane).accumulateAndGet(elapsedNanos.coerceAtLeast(0L), ::maxOf)
    }

    private fun inUse(lane: PermitLane): Int =
        configs.getValue(lane).capacity - permits.getValue(lane).availablePermits()

    private fun inUseTotal(): Int = PermitLane.entries.sumOf(::inUse)

    private fun signalIfDrained() {
        if (inUseTotal() == 0) {
            drainLock.withLock { drained.signalAll() }
        }
    }

    companion object : KLogging() {
        fun default(hikariMaximumPoolSize: Int): DatabasePermitGate =
            DatabasePermitGate(
                hikariMaximumPoolSize = hikariMaximumPoolSize,
                configs =
                    mapOf(
                        PermitLane.FOREGROUND to
                            PermitLaneConfig(DEFAULT_FOREGROUND_CAPACITY, DEFAULT_FOREGROUND_WAIT_MILLIS.milliseconds),
                        PermitLane.WORKER to PermitLaneConfig(DEFAULT_WORKER_CAPACITY, 1.seconds),
                        PermitLane.SSE to PermitLaneConfig(DEFAULT_SSE_CAPACITY, 1.seconds),
                    ),
            )
    }
}
