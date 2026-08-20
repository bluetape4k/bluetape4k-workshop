package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.OutboxRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** owner/token fencing과 terminal poison 처리를 포함한 bounded outbox replay입니다. */
class FieldServiceOutboxWorker(
    private val repository: FieldServiceRepository,
    private val handler: (OutboxRecord) -> ReplayOutcome,
    private val owner: String = "field-service-outbox",
) {
    fun processOutboxBatch(maxItems: Int = 10): ReplayResult {
        val claimed = transaction { repository.claimOutbox(maxItems, owner) }
        var completed = 0
        var retryable = 0
        var deadLetter = 0
        claimed.forEach { record ->
            val outcome = try {
                handler(record)
            } catch (failure: Exception) {
                log.warn { "Field Service outbox handler failed: type=${failure.javaClass.simpleName}" }
                ReplayOutcome.RETRYABLE
            }
            when (outcome) {
                ReplayOutcome.COMPLETED -> if (
                    transaction { repository.completeOutbox(record.id, owner, record.leaseToken.orEmpty()) }
                ) completed++
                ReplayOutcome.RETRYABLE -> if (
                    transaction { repository.retryOutbox(record.id, owner, record.leaseToken.orEmpty(), "RETRYABLE") }
                ) retryable++
                ReplayOutcome.DEAD_LETTER -> if (
                    transaction { repository.deadLetterOutbox(record.id, owner, record.leaseToken.orEmpty(), "DEAD_LETTER") }
                ) deadLetter++
            }
        }
        return ReplayResult(claimed.size, completed, retryable, deadLetter)
    }

    companion object : KLogging()
}

enum class ReplayOutcome {
    COMPLETED,
    RETRYABLE,
    DEAD_LETTER,
}

data class ReplayResult(
    val claimed: Int,
    val completed: Int,
    val retryable: Int,
    val deadLetter: Int,
)
