package io.bluetape4k.workshop.commerce.voucherpool.admission

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_FOREGROUND_CAPACITY = 12
private const val DEFAULT_WORKER_CAPACITY = 1
private const val DEFAULT_SSE_CAPACITY = 3
private const val DEFAULT_FOREGROUND_WAIT_MILLIS = 250

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
internal class DatabasePermitGate(
    hikariMaximumPoolSize: Int,
    configs: Map<PermitLane, PermitLaneConfig>,
) {
    private val configs: Map<PermitLane, PermitLaneConfig> = configs.toMap()
    private val permits: Map<PermitLane, Semaphore>
    private val activeLane = ThreadLocal<PermitLane>()

    init {
        require(hikariMaximumPoolSize > 0) { "hikariMaximumPoolSize must be positive" }
        require(this.configs.keys == PermitLane.entries.toSet()) { "all permit lanes must be configured" }
        require(this.configs.values.all { it.capacity > 0 }) { "permit capacities must be positive" }
        require(this.configs.values.all { it.wait > Duration.ZERO }) { "permit waits must be positive" }
        require(this.configs.values.sumOf(PermitLaneConfig::capacity) <= hikariMaximumPoolSize) {
            "permit capacity must not exceed the Hikari pool"
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
        )

    private fun <T> withPermit(
        lane: PermitLane,
        block: () -> T,
    ): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "database permit must be acquired before starting a transaction"
        }
        activeLane.get()?.let { held -> throw NestedPermitException(held, lane) }
        val semaphore = permits.getValue(lane)
        val wait = configs.getValue(lane).wait
        acquirePermit(lane, semaphore, wait)

        activeLane.set(lane)
        return try {
            block()
        } finally {
            activeLane.remove()
            semaphore.release()
            log.debug { "voucher_pool_db_permit_released lane=$lane" }
        }
    }

    private fun acquirePermit(
        lane: PermitLane,
        semaphore: Semaphore,
        wait: Duration,
    ) {
        val acquired =
            try {
                semaphore.tryAcquire(wait.inWholeNanoseconds, TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                log.debug { "voucher_pool_db_permit_interrupted lane=$lane" }
                throw PoolBusyException(lane = lane, interrupted = true, cause = interrupted)
            }
        if (!acquired) {
            log.debug { "voucher_pool_db_permit_timed_out lane=$lane" }
            throw PoolBusyException(lane)
        }
    }

    private fun inUse(lane: PermitLane): Int =
        configs.getValue(lane).capacity - permits.getValue(lane).availablePermits()

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
