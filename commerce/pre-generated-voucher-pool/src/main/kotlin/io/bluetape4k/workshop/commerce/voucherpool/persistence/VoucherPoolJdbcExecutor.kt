package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.admission.PoolBusyException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionTimedOutException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.*

private const val DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS = 2L
private const val DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS = 10L
private const val DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS = 5L
private const val DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS = 10L
private const val DEFAULT_SSE_LOCK_TIMEOUT_SECONDS = 5L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_CEILING_OFFSET = MILLIS_PER_SECOND - 1L
private const val MINIMUM_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_CAUSE_DEPTH = 32
private const val POSTGRES_LOCK_NOT_AVAILABLE = "55P03"
private const val POSTGRES_QUERY_CANCELED = "57014"

private fun Throwable.timeoutPhaseInCauseChain(): JdbcTimeoutPhase? {
    val visited: MutableSet<Throwable?> = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    var phase: JdbcTimeoutPhase? = null
    var depth = 0

    while (current != null && depth < MAX_TIMEOUT_CAUSE_DEPTH) {
        if (phase != null || !visited.add(current)) break
        phase = current.directTimeoutPhase()
        current = current.cause
        depth++
    }
    return phase
}

private fun Throwable.directTimeoutPhase(): JdbcTimeoutPhase? =
    when {
        PoolBusyException::class.java.isInstance(this)              -> JdbcTimeoutPhase.PERMIT
        SQLTransientConnectionException::class.java.isInstance(this) -> JdbcTimeoutPhase.ACQUISITION
        SQLException::class.java.isInstance(this) &&
                SQLException::class.java.cast(this)
                    .sqlState == POSTGRES_LOCK_NOT_AVAILABLE        -> JdbcTimeoutPhase.LOCK

        SQLTimeoutException::class.java.isInstance(this)            -> JdbcTimeoutPhase.TRANSACTION
        SQLException::class.java.isInstance(this) &&
                SQLException::class.java
                    .cast(this).sqlState == POSTGRES_QUERY_CANCELED -> JdbcTimeoutPhase.TRANSACTION

        TransactionTimedOutException::class.java.isInstance(this)   -> JdbcTimeoutPhase.TRANSACTION

        else                                                        -> null
    }

internal enum class JdbcExecutionLane {
    FOREGROUND,
    OPERATOR,
    WORKER,
    SSE,
}

internal enum class JdbcTimeoutPhase {
    ACQUISITION,
    PERMIT,
    LOCK,
    TRANSACTION,
}

internal data class VoucherPoolJdbcTimeouts(
    val connectionAcquisition: Duration = DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS.seconds(),
    val foregroundTransaction: Duration = DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    val operatorTransaction: Duration = DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    val workerChunkTransaction: Duration = DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    val sseTransaction: Duration = DEFAULT_SSE_TRANSACTION_TIMEOUT_SECONDS.seconds(),
    val foregroundLock: Duration = DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS.seconds(),
    val operatorLock: Duration = DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS.seconds(),
    val workerLock: Duration = DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS.seconds(),
    val sseLock: Duration = DEFAULT_SSE_LOCK_TIMEOUT_SECONDS.seconds(),
) {
    init {
        require(
            listOf(
                connectionAcquisition,
                foregroundTransaction,
                operatorTransaction,
                workerChunkTransaction,
                sseTransaction,
                foregroundLock,
                operatorLock,
                workerLock,
                sseLock,
            ).all { !it.isNegative && !it.isZero },
        ) { "JDBC timeouts must be positive" }

        require(listOf(foregroundLock, operatorLock, workerLock, sseLock).all { it.toMillis() >= 1L }) {
            "JDBC lock timeouts must be at least one millisecond"
        }
    }

    fun transactionTimeout(lane: JdbcExecutionLane): Duration = when (lane) {
        JdbcExecutionLane.FOREGROUND -> foregroundTransaction
        JdbcExecutionLane.OPERATOR   -> operatorTransaction
        JdbcExecutionLane.WORKER     -> workerChunkTransaction
        JdbcExecutionLane.SSE        -> sseTransaction
    }

    fun lockTimeout(lane: JdbcExecutionLane): Duration = when (lane) {
        JdbcExecutionLane.FOREGROUND -> foregroundLock
        JdbcExecutionLane.OPERATOR   -> operatorLock
        JdbcExecutionLane.WORKER     -> workerLock
        JdbcExecutionLane.SSE        -> sseLock
    }
}

internal class VoucherPoolJdbcTimeoutException(
    val lane: JdbcExecutionLane,
    val phase: JdbcTimeoutPhase,
    cause: Throwable,
): RuntimeException("voucher pool JDBC timeout lane=$lane phase=$phase", cause)

internal fun interface VoucherPoolJdbcMetrics {
    fun timedOut(
        lane: JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
    )

    companion object {
        val NONE = VoucherPoolJdbcMetrics { _, _ -> }
    }
}

internal fun interface VoucherPoolLockTimeoutApplier {

    fun apply(timeout: Duration)

    companion object {
        val NONE = VoucherPoolLockTimeoutApplier {}
    }
}

/** admission 이후, Spring transaction 안에서만 JDBC/Exposed work를 실행합니다. */
internal class VoucherPoolJdbcExecutor(
    private val gate: DatabasePermitGate,
    private val transactionManager: PlatformTransactionManager,
    val timeouts: VoucherPoolJdbcTimeouts = VoucherPoolJdbcTimeouts(),
    private val metrics: VoucherPoolJdbcMetrics = VoucherPoolJdbcMetrics.NONE,
    private val lockTimeoutApplier: VoucherPoolLockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
) {
    fun <T> foregroundTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.FOREGROUND, block)

    fun <T> operatorTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.OPERATOR, block)

    fun <T> workerTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.WORKER, block)

    fun <T> sseTransaction(block: () -> T): T =
        execute(JdbcExecutionLane.SSE, block)

    fun afterCommit(action: () -> Unit) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "afterCommit requires active transaction synchronization"
        }
        TransactionSynchronizationManager.registerSynchronization(
            object: TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private inline fun <T> execute(
        lane: JdbcExecutionLane,
        crossinline block: () -> T,
    ): T =
        try {
            withPermit(lane) {
                val template = TransactionTemplate(transactionManager)
                    .apply {
                        timeout = timeouts.transactionTimeout(lane).toSpringTimeoutSeconds()
                    }
                val result = template.execute {
                    lockTimeoutApplier.apply(timeouts.lockTimeout(lane))
                    TransactionResult(block())
                } // .checkNotNull { "Lock timeouts must not be null" }
                log.debug { "voucher_pool_jdbc_transaction_completed lane=$lane, result=$result" }

                result.value
            }
        } catch (failure: PoolBusyException) {
            throw timeoutFailure(lane, JdbcTimeoutPhase.PERMIT, failure)
        } catch (failure: VoucherPoolJdbcTimeoutException) {
            throw failure
        } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
            throw failure.toTimeoutFailure(lane) ?: failure
        }

    private fun <T> withPermit(
        lane: JdbcExecutionLane,
        block: () -> T,
    ): T =
        when (lane) {
            JdbcExecutionLane.FOREGROUND,
            JdbcExecutionLane.OPERATOR,
                                  -> gate.withForegroundPermit(block)

            JdbcExecutionLane.WORKER -> gate.withWorkerPermit(block)
            JdbcExecutionLane.SSE -> gate.withSsePermit(block)
        }

    private fun timeoutFailure(
        lane: JdbcExecutionLane,
        phase: JdbcTimeoutPhase,
        cause: Throwable,
    ): VoucherPoolJdbcTimeoutException {
        metrics.timedOut(lane, phase)
        log.debug { "voucher_pool_jdbc_timeout lane=$lane phase=$phase" }
        return VoucherPoolJdbcTimeoutException(lane = lane, phase = phase, cause = cause)
    }

    private fun Throwable.toTimeoutFailure(lane: JdbcExecutionLane): VoucherPoolJdbcTimeoutException? =
        timeoutPhaseInCauseChain()?.let { phase ->
            timeoutFailure(lane, phase, this)
        }

    private data class TransactionResult<T>(val value: T)

    private fun Duration.toSpringTimeoutSeconds(): Int =
        ((toMillis() + MILLIS_CEILING_OFFSET) / MILLIS_PER_SECOND)
            .coerceAtLeast(MINIMUM_TIMEOUT_SECONDS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    companion object: KLogging()
}
