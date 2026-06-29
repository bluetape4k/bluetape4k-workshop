package io.bluetape4k.workshop.messaging.fallback.publication

import io.bluetape4k.workshop.messaging.fallback.config.FallbackOutboxProperties
import io.bluetape4k.workshop.messaging.fallback.observability.OutboxMetrics
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.Serializable
import java.time.Clock
import java.time.LocalDateTime

/**
 * Reconstructs missing publication rows from persisted orders.
 */
@Component
class PublicationReconciler(
    private val objectMapper: ObjectMapper,
    private val properties: FallbackOutboxProperties,
    private val eventPublicationRepository: EventPublicationRepository,
    private val outboxMetrics: OutboxMetrics,
    private val clock: Clock,
) {

    @Scheduled(fixedDelayString = "\${workshop.kafka-outbox-fallback.relay-fixed-delay}")
    fun scheduledReconcile() {
        if (properties.reconcilerEnabled) {
            reconcileOnce()
        }
    }

    fun reconcileOnce(): ReconcileResult {
        val cutoff = LocalDateTime.now(clock).minus(properties.reconcilerGrace)
        val candidates = eventPublicationRepository.findOrdersWithoutPublications()
            .filter { event -> !event.createdAt.isAfter(cutoff) }

        val reconstructed = candidates.count { event ->
            val payload = objectMapper.writeValueAsString(event)
            eventPublicationRepository.upsertReconstructed(event, payload)
        }

        if (reconstructed > 0) {
            outboxMetrics.recordReconciler("reconstructed")
        }

        return ReconcileResult(
            scanned = candidates.size,
            reconstructed = reconstructed,
            duplicateRiskDocumented = true,
        )
    }
}

/**
 * Summary returned by one reconciliation pass.
 */
data class ReconcileResult(
    val scanned: Int,
    val reconstructed: Int,
    val duplicateRiskDocumented: Boolean,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
