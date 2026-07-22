package io.bluetape4k.workshop.commerce.metering.config

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("workshop.metering")
data class MeteringProperties(
    val commandReceipt: CommandReceipt = CommandReceipt(),
    val allowedLateness: Duration = DEFAULT_ALLOWED_LATENESS,
    val occurredAtRetention: Duration = DEFAULT_OCCURRED_AT_RETENTION,
    val occurredAtFutureSkew: Duration = DEFAULT_FUTURE_SKEW,
    val close: Close = Close(),
    val reconciliation: Reconciliation = Reconciliation(),
    val schemaInitializerEnabled: Boolean = false,
    val demoEnabled: Boolean = false,
) {
    init {
        allowedLateness.requireGt(Duration.ZERO, "allowedLateness")
        occurredAtRetention.requireGt(Duration.ZERO, "occurredAtRetention")
        occurredAtFutureSkew.compareTo(Duration.ZERO)
            .requireInRange(0, Int.MAX_VALUE, "occurredAtFutureSkew.compareTo(Duration.ZERO)")
    }

    data class CommandReceipt(
        val lease: Duration = DEFAULT_RECEIPT_LEASE,
        val retention: Duration = DEFAULT_RECEIPT_RETENTION,
        val terminalResponseBytes: Int = MAX_TERMINAL_RESPONSE_BYTES,
    ) {
        init {
            require(lease in MIN_RECEIPT_LEASE..MAX_RECEIPT_LEASE) {
                "command receipt lease must be between $MIN_RECEIPT_LEASE and $MAX_RECEIPT_LEASE"
            }
            require(retention > lease) { "command receipt retention must be longer than lease" }
            terminalResponseBytes.requireInRange(1, MAX_TERMINAL_RESPONSE_BYTES, "terminalResponseBytes")
        }
    }

    data class Close(
        val batchSize: Int = 200,
        val maxBatchSize: Int = 1_000,
        val maxBatchesPerTick: Int = 5,
        val schedulerDelay: Duration = DEFAULT_SCHEDULER_DELAY,
        val schedulerEnabled: Boolean = true,
    ) {
        init {
            maxBatchSize.requireInRange(1, MAX_CLOSE_BATCH_SIZE, "maxBatchSize")
            batchSize.requireInRange(1, maxBatchSize, "batchSize")
            maxBatchesPerTick.requireInRange(1, MAX_BATCHES_PER_TICK, "maxBatchesPerTick")
            schedulerDelay.requireGt(Duration.ZERO, "schedulerDelay")
        }
    }

    data class Reconciliation(
        val pageSize: Int = 200,
        val maxPageSize: Int = 500,
    ) {
        init {
            maxPageSize.requireInRange(1, MAX_RECONCILIATION_PAGE_SIZE, "maxPageSize")
            pageSize.requireInRange(1, maxPageSize, "pageSize")
        }
    }

    companion object {
        val DEFAULT_ALLOWED_LATENESS: Duration = Duration.ofHours(48)
        val DEFAULT_OCCURRED_AT_RETENTION: Duration = Duration.ofDays(400)
        val DEFAULT_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
        val DEFAULT_RECEIPT_LEASE: Duration = Duration.ofSeconds(30)
        val DEFAULT_RECEIPT_RETENTION: Duration = Duration.ofHours(24)
        val DEFAULT_SCHEDULER_DELAY: Duration = Duration.ofSeconds(5)
        private val MIN_RECEIPT_LEASE: Duration = Duration.ofSeconds(5)
        private val MAX_RECEIPT_LEASE: Duration = Duration.ofMinutes(5)
        const val MAX_TERMINAL_RESPONSE_BYTES: Int = 16 * 1024
        const val MAX_CLOSE_BATCH_SIZE: Int = 1_000
        const val MAX_BATCHES_PER_TICK: Int = 20
        const val MAX_RECONCILIATION_PAGE_SIZE: Int = 500
    }
}
