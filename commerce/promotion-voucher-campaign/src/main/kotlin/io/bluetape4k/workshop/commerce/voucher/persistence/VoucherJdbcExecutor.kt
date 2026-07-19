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

/** Opens the Spring/Exposed transaction only after the matching local JDBC permit is held. */
internal class VoucherJdbcExecutor(
    private val gate: DatabasePermitGate,
    transactionManager: PlatformTransactionManager,
    private val lockTimeout: Duration = Duration.ofSeconds(5),
) : VoucherTransactionRunner {
    private val transactions = TransactionTemplate(transactionManager)

    override fun <T> foregroundTransaction(block: () -> T): T =
        transaction(DatabaseLane.FOREGROUND, applyLockTimeout = true, block)

    fun <T> workerTransaction(block: () -> T): T =
        transaction(DatabaseLane.WORKER, applyLockTimeout = false, block)

    fun <T> sseMaintenanceTransaction(block: () -> T): T =
        transaction(DatabaseLane.SSE_MAINTENANCE, applyLockTimeout = false, block)

    private fun <T> transaction(
        lane: DatabaseLane,
        applyLockTimeout: Boolean,
        block: () -> T,
    ): T =
        gate.withPermit(lane) {
            val result =
                transactions.execute {
                    if (applyLockTimeout) {
                        TransactionManager.current().exec("SET LOCAL lock_timeout = '${lockTimeout.toMillis()}ms'")
                    }
                    TransactionResult(block())
                }
            log.debug { "voucher_jdbc_transaction_completed lane=$lane" }
            result.value
        }

    private data class TransactionResult<T>(val value: T)

    companion object : KLogging()
}
