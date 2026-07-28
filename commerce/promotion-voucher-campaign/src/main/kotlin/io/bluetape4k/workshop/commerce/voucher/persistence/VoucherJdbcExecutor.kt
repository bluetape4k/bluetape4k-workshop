package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

internal interface VoucherTransactionRunner {
    fun <T> foregroundTransaction(block: () -> T): T
}

/** 일치하는 local JDBC permit을 보유한 뒤에만 Spring/Exposed transaction을 엽니다. */
internal class VoucherJdbcExecutor(
    private val gate: DatabasePermitGate,
    private val transactionManager: PlatformTransactionManager,
    private val lockTimeout: Duration = Duration.ofSeconds(5),
) : VoucherTransactionRunner {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> foregroundTransaction(block: () -> T): T =
        if (gate.isHeld(DatabaseLane.FOREGROUND)) {
            block()
        } else {
            transaction(DatabaseLane.FOREGROUND, applyLockTimeout = true, block = block)
        }

    fun <T> workerTransaction(block: () -> T): T =
        transaction(DatabaseLane.WORKER, applyLockTimeout = false, block = block)

    /** deadline-bounded worker row에 Spring timeout과 PostgreSQL timeout을 모두 적용합니다. */
    fun <T> workerTransaction(
        timeout: Duration,
        block: () -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
        return transaction(
            lane = DatabaseLane.WORKER,
            applyLockTimeout = false,
            transactionTimeout = timeout,
            block = block,
        )
    }

    fun <T> sseMaintenanceTransaction(block: () -> T): T =
        transaction(DatabaseLane.SSE_MAINTENANCE, applyLockTimeout = false, block = block)

    private fun <T> transaction(
        lane: DatabaseLane,
        applyLockTimeout: Boolean,
        transactionTimeout: Duration? = null,
        block: () -> T,
    ): T =
        gate.withPermit(lane) {
            val template =
                transactionTimeout?.let { timeout ->
                    TransactionTemplate(transactionManager).apply {
                        this.timeout = timeout.toSpringTimeoutSeconds()
                    }
                } ?: transactions
            val result =
                template.execute {
                    if (applyLockTimeout) {
                        TransactionManager.current().exec("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'")
                    }
                    if (transactionTimeout != null) {
                        TransactionManager.current().exec(
                            "SET LOCAL statement_timeout = '${transactionTimeout.toMillis().coerceAtLeast(1)}ms'",
                        )
                    }
                    TransactionResult(block())
                }
            log.debug { "voucher_jdbc_transaction_completed lane=$lane" }
            result.value
        }

    private data class TransactionResult<T>(val value: T)

    private fun Duration.toSpringTimeoutSeconds(): Int =
        ((toMillis() + 999L) / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    companion object : KLogging()
}
