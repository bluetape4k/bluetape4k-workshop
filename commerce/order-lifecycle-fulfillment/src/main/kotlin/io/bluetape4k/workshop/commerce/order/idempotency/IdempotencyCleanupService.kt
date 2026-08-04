package io.bluetape4k.workshop.commerce.order.idempotency

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** recoverable lease를 삭제하지 않고 terminal HTTP idempotency evidence를 주기적으로 제한합니다. */
@Service
internal class IdempotencyCleanupService(
    private val repository: HttpIdempotencyRepository,
    private val clock: Clock,
    @param:Value("\${order-lifecycle.idempotency.cleanup-batch-size:250}")
    private val batchSize: Int,
) {
    init {
        batchSize.requireInRange(1, HttpIdempotencyRepository.MAX_CLEANUP_BATCH, "batchSize")
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
