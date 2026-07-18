package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** Periodically bounds terminal HTTP idempotency evidence without deleting recoverable leases. */
@Service
internal class IdempotencyCleanupService(
    private val repository: HttpIdempotencyRepository,
    private val clock: Clock,
    @param:Value("\${order-lifecycle.idempotency.cleanup-batch-size:250}")
    private val batchSize: Int,
) {
    init {
        require(batchSize in 1..HttpIdempotencyRepository.MAX_CLEANUP_BATCH)
    }

    @Scheduled(
        fixedDelayString = "\${order-lifecycle.idempotency.cleanup-interval:1h}",
        initialDelayString = "\${order-lifecycle.idempotency.cleanup-initial-delay:1m}"
    )
    @Transactional
    fun deleteExpiredTerminal(): Int =
        repository.deleteExpiredTerminal(Instant.now(clock), batchSize).also { deleted ->
            if (deleted > 0) log.info { "idempotency_cleanup_completed deleted=$deleted" }
        }

    companion object : KLogging()
}
