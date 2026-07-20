package io.bluetape4k.workshop.commerce.voucherpool.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
import java.util.Collections
import java.util.IdentityHashMap

private const val DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS = 2L
private const val DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS = 10L
private const val DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS = 5L
private const val DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS = 5L
private const val DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS = 10L
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_CEILING_OFFSET = MILLIS_PER_SECOND - 1L
private const val MINIMUM_TIMEOUT_SECONDS = 1L
private const val MAX_TIMEOUT_CAUSE_DEPTH = 32
private const val POSTGRES_LOCK_NOT_AVAILABLE = "55P03"
private const val POSTGRES_QUERY_CANCELED = "57014"

private fun Throwable.timeoutPhaseInCauseChain(): JdbcTimeoutPhase? {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
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
        PoolBusyException::class.java.isInstance(this) -> JdbcTimeoutPhase.PERMIT
        SQLTransientConnectionException::class.java.isInstance(this) -> JdbcTimeoutPhase.ACQUISITION
        SQLException::class.java.isInstance(this) &&
            SQLException::class.java.cast(this).sqlState == POSTGRES_LOCK_NOT_AVAILABLE -> JdbcTimeoutPhase.LOCK

        SQLTimeoutException::class.java.isInstance(this) -> JdbcTimeoutPhase.TRANSACTION
        SQLException::class.java.isInstance(this) &&
            SQLException::class.java.cast(this).sqlState == POSTGRES_QUERY_CANCELED -> JdbcTimeoutPhase.TRANSACTION

        TransactionTimedOutException::class.java.isInstance(this) -> JdbcTimeoutPhase.TRANSACTION

        else -> null
    }

internal enum class JdbcExecutionLane {
    FOREGROUND,
    OPERATOR,
    WORKER,
}

internal enum class JdbcTimeoutPhase {
    ACQUISITION,
    PERMIT,
    LOCK,
    TRANSACTION,
}

internal data class VoucherPoolJdbcTimeouts(
    val connectionAcquisition: Duration = Duration.ofSeconds(DEFAULT_CONNECTION_ACQUISITION_TIMEOUT_SECONDS),
    val foregroundTransaction: Duration = Duration.ofSeconds(DEFAULT_FOREGROUND_TRANSACTION_TIMEOUT_SECONDS),
    val operatorTransaction: Duration = Duration.ofSeconds(DEFAULT_OPERATOR_TRANSACTION_TIMEOUT_SECONDS),
    val workerChunkTransaction: Duration = Duration.ofSeconds(DEFAULT_WORKER_TRANSACTION_TIMEOUT_SECONDS),
    val foregroundLock: Duration = Duration.ofSeconds(DEFAULT_FOREGROUND_LOCK_TIMEOUT_SECONDS),
    val operatorLock: Duration = Duration.ofSeconds(DEFAULT_OPERATOR_LOCK_TIMEOUT_SECONDS),
    val workerLock: Duration = Duration.ofSeconds(DEFAULT_WORKER_LOCK_TIMEOUT_SECONDS),
) {
    init {
        require(
            listOf(
                connectionAcquisition,
                foregroundTransaction,
                operatorTransaction,
                workerChunkTransaction,
                foregroundLock,
                operatorLock,
                workerLock,
            ).all { !it.isNegative && !it.isZero },
        ) { "JDBC timeouts must be positive" }
        require(listOf(foregroundLock, operatorLock, workerLock).all { it.toMillis() >= 1L }) {
            "JDBC lock timeouts must be at least one millisecond"
        }
    }

    fun transactionTimeout(lane: JdbcExecutionLane): Duration =
        when (lane) {
            JdbcExecutionLane.FOREGROUND -> foregroundTransaction
            JdbcExecutionLane.OPERATOR -> operatorTransaction
            JdbcExecutionLane.WORKER -> workerChunkTransaction
        }

    fun lockTimeout(lane: JdbcExecutionLane): Duration =
        when (lane) {
            JdbcExecutionLane.FOREGROUND -> foregroundLock
            JdbcExecutionLane.OPERATOR -> operatorLock
            JdbcExecutionLane.WORKER -> workerLock
        }
}

internal class VoucherPoolJdbcTimeoutException(
    val lane: JdbcExecutionLane,
    val phase: JdbcTimeoutPhase,
    cause: Throwable,
) : RuntimeException("voucher pool JDBC timeout lane=$lane phase=$phase", cause)

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

/** Executes JDBC/Exposed work only after admission and within a Spring transaction. */
internal class VoucherPoolJdbcExecutor(
    private val gate: DatabasePermitGate,
    private val transactionManager: PlatformTransactionManager,
    val timeouts: VoucherPoolJdbcTimeouts = VoucherPoolJdbcTimeouts(),
    private val metrics: VoucherPoolJdbcMetrics = VoucherPoolJdbcMetrics.NONE,
    private val lockTimeoutApplier: VoucherPoolLockTimeoutApplier = VoucherPoolLockTimeoutApplier.NONE,
) {
    fun <T> foregroundTransaction(block: () -> T): T = execute(JdbcExecutionLane.FOREGROUND, block)

    fun <T> operatorTransaction(block: () -> T): T = execute(JdbcExecutionLane.OPERATOR, block)

    fun <T> workerTransaction(block: () -> T): T = execute(JdbcExecutionLane.WORKER, block)

    fun afterCommit(action: () -> Unit) {
        check(TransactionSynchronizationManager.isSynchronizationActive()) {
            "afterCommit requires active transaction synchronization"
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun <T> execute(
        lane: JdbcExecutionLane,
        block: () -> T,
    ): T =
        try {
            withPermit(lane) {
                val template =
                    TransactionTemplate(transactionManager).apply {
                        timeout = timeouts.transactionTimeout(lane).toSpringTimeoutSeconds()
                    }
                val result =
                    checkNotNull(
                        template.execute {
                            lockTimeoutApplier.apply(timeouts.lockTimeout(lane))
                            TransactionResult(block())
                        },
                    )
                log.debug { "voucher_pool_jdbc_transaction_completed lane=$lane" }
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
        timeoutPhaseInCauseChain()?.let { phase -> timeoutFailure(lane, phase, this) }

    private data class TransactionResult<T>(val value: T)

    private fun Duration.toSpringTimeoutSeconds(): Int =
        ((toMillis() + MILLIS_CEILING_OFFSET) / MILLIS_PER_SECOND)
            .coerceAtLeast(MINIMUM_TIMEOUT_SECONDS)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    companion object : KLogging()
}
