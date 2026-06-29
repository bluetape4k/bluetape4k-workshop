package io.bluetape4k.workshop.messaging.fallback.publication

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.messaging.fallback.api.OrderPublicationStatus
import io.bluetape4k.workshop.messaging.fallback.config.FallbackOutboxProperties
import io.bluetape4k.workshop.messaging.fallback.observability.OutboxMetrics
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Publishes order events directly to Kafka and falls back to durable rows.
 */
@Component
class OrderEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val properties: FallbackOutboxProperties,
    private val eventPublicationRepository: EventPublicationRepository,
    private val outboxMetrics: OutboxMetrics,
) {
    companion object : KLogging()

    fun publishDirectOrFallback(event: OrderPlacedEvent): OrderPublicationStatus {
        if (!properties.directPublishEnabled) {
            val payload = objectMapper.writeValueAsString(event)
            return storeFallback(event, payload, properties.directPublishAttempts, "DIRECT_DISABLED", "Direct publish disabled")
        }

        val payload = objectMapper.writeValueAsString(event)
        require(payload.toByteArray(Charsets.UTF_8).size <= properties.maxPayloadBytes) {
            "OrderPlaced payload exceeds configured max size"
        }

        var lastFailure: Exception? = null
        val deadline = System.nanoTime() + properties.directPublishTotalTimeout.toNanos()

        for (attempt in 1..properties.directPublishAttempts) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMillis <= 0) break

            val timeoutMillis = minOf(properties.directPublishTimeout.toMillis(), remainingMillis)
            var future: CompletableFuture<*>? = null
            try {
                future = kafkaTemplate.send(properties.topic, event.eventId, payload)
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
                outboxMetrics.recordDirectPublish("success")
                return OrderPublicationStatus.PUBLISHED_DIRECT
            } catch (e: TimeoutException) {
                future?.cancel(true)
                lastFailure = e
                outboxMetrics.recordDirectPublish("timeout")
                log.warn { "order.event.direct-publish.failed eventId=${event.eventId} attempt=$attempt reason=timeout" }
            } catch (e: Exception) {
                lastFailure = e
                outboxMetrics.recordDirectPublish("failure")
                log.warn { "order.event.direct-publish.failed eventId=${event.eventId} attempt=$attempt reason=${e.javaClass.simpleName}" }
            }
        }

        return storeFallback(
            event = event,
            payload = payload,
            directAttemptCount = properties.directPublishAttempts,
            errorCode = lastFailure?.javaClass?.simpleName ?: "DirectPublishFailed",
            errorSummary = sanitize(lastFailure?.message ?: "Direct publish failed"),
        )
    }

    private fun storeFallback(
        event: OrderPlacedEvent,
        payload: String,
        directAttemptCount: Int,
        errorCode: String,
        errorSummary: String,
    ): OrderPublicationStatus =
        try {
            eventPublicationRepository.upsertNotPublished(event, payload, directAttemptCount, errorCode, errorSummary)
            outboxMetrics.recordFallbackStored("success")
            OrderPublicationStatus.FALLBACK_STORED
        } catch (e: Exception) {
            outboxMetrics.recordFallbackStored("failure")
            log.error(e) { "order.event.fallback-store.failed eventId=${event.eventId} reason=${e.javaClass.simpleName}" }
            OrderPublicationStatus.FALLBACK_STORE_FAILED
        }

    private fun sanitize(message: String): String =
        message
            .replace(Regex("(?i)(secret|token|password|credential)[^\\s,;]*"), "[redacted]")
            .take(240)
}
