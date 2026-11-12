package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireInRange
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.stereotype.Service
import java.io.Serializable

internal data class ReconciliationResult(
    val requested: Int,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

@Service
internal class PublicationReconciliationService(
    private val failedPublications: FailedEventPublications,
) {
    companion object: KLogging() {
        const val DEF_BATCH_SIZE = 30
        const val MAX_BATCH_SIZE = 100
    }

    fun replayFailed(batchSize: Int = DEF_BATCH_SIZE): ReconciliationResult {
        batchSize.requireInRange(1, MAX_BATCH_SIZE, "batchSize")
        val options = ResubmissionOptions.defaults()
            .withMaxInFlight(batchSize)
            .withBatchSize(batchSize)

        failedPublications.resubmit(options)
        log.info { "publication_replay_requested batchSize=$batchSize" }

        return ReconciliationResult(batchSize)
    }
}
