package io.bluetape4k.workshop.messaging.fallback.publication

import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.messaging.fallback.config.FallbackOutboxProperties
import io.bluetape4k.workshop.messaging.fallback.observability.OutboxMetrics
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * claim 기반 duplicate protection 으로 durable fallback row 를 Kafka 로 relay 합니다.
 */
@Component
class EventPublicationRelay(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val properties: FallbackOutboxProperties,
    private val eventPublicationRepository: EventPublicationRepository,
    private val outboxMetrics: OutboxMetrics,
) {
    companion object : KLogging()

    @Scheduled(fixedDelayString = "\${workshop.kafka-outbox-fallback.relay-fixed-delay}")
    fun scheduledRelay() {
        if (properties.relayEnabled) {
            relayOnce()
        }
    }

    fun relayOnce(): RelayResult {
        val workerId = "relay-${Base58.randomString(8)}"
        val claimed = eventPublicationRepository.claimNextBatch(workerId, properties.relayBatchSize)
        var published = 0
        var failed = 0
        var deadLettered = 0

        claimed.forEach { publication ->
            val result = publishClaimed(publication, workerId)
            published += result.published
            failed += result.failed
            deadLettered += result.deadLettered
        }

        return RelayResult(
            claimed = claimed.size,
            published = published,
            failed = failed,
            deadLettered = deadLettered,
        )
    }

    private fun publishClaimed(publication: EventPublicationRecord, workerId: String): RelayResult {
        var future: CompletableFuture<*>? = null
        return try {
            future = kafkaTemplate.send(properties.topic, publication.eventId, publication.payload)
            future.get(properties.directPublishTimeout.toMillis(), TimeUnit.MILLISECONDS)
            if (eventPublicationRepository.markPublished(publication.eventId, workerId)) {
                outboxMetrics.recordRelay("published")
                RelayResult(claimed = 1, published = 1)
            } else {
                log.warn { "order.event.relay.claim_lost eventId=${publication.eventId}" }
                outboxMetrics.recordRelay("claim-lost")
                RelayResult(claimed = 1, failed = 1)
            }
        } catch (e: TimeoutException) {
            future?.cancel(true)
            markFailure(publication, workerId, e)
        } catch (e: Exception) {
            markFailure(publication, workerId, e)
        }
    }

    private fun markFailure(
        publication: EventPublicationRecord,
        workerId: String,
        failure: Exception,
    ): RelayResult {
        val retryCount = publication.relayRetryCount + 1
        val nextStatus = eventPublicationRepository.markRelayFailure(
            eventId = publication.eventId,
            claimedBy = workerId,
            retryCount = retryCount,
            errorCode = failure.javaClass.simpleName,
            errorSummary = failure.message ?: "Relay publish failed",
        )
        log.warn { "order.event.relay.failed eventId=${publication.eventId} status=$nextStatus retry=$retryCount" }

        return if (nextStatus == EventPublicationStatus.DEAD_LETTER) {
            outboxMetrics.recordRelay("dead-letter")
            RelayResult(claimed = 1, deadLettered = 1)
        } else {
            outboxMetrics.recordRelay("failure")
            RelayResult(claimed = 1, failed = 1)
        }
    }
}

/**
 * relay pass 한 번이 반환하는 summary 입니다.
 */
data class RelayResult(
    val claimed: Int = 0,
    val published: Int = 0,
    val failed: Int = 0,
    val deadLettered: Int = 0,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
