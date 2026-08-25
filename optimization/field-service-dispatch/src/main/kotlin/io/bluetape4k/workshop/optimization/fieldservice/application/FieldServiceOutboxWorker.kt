package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.OutboxRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CancellationException

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
            var retryReason = OutboxFailureReason.GENERIC_RETRYABLE
            val outcome = try {
                handler(record)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                log.warn { "Field Service outbox handler failed: type=${failure.javaClass.simpleName}" }
                retryReason = OutboxFailureReason.from(failure)
                ReplayOutcome.RETRYABLE
            }
            when (outcome) {
                ReplayOutcome.COMPLETED -> if (
                    transaction { repository.completeOutbox(record.id, owner, record.leaseToken.orEmpty()) }
                ) completed++
                ReplayOutcome.RETRYABLE -> if (
                    transaction { repository.retryOutbox(record.id, owner, record.leaseToken.orEmpty(), retryReason) }
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

internal object OutboxFailureReason {
    const val GENERIC_RETRYABLE = "RETRYABLE"

    fun from(failure: Throwable): String {
        val type = failure.javaClass.simpleName
            .takeIf { it.isNotBlank() && it.all { character -> character.isLetterOrDigit() } }
            ?: "UnknownFailure"
        return "RETRYABLE:$type"
    }
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
