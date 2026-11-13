package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.concurrent.get
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

@Component
class KafkaUsageEventTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
): UsageEventTransport {
    @Suppress("ThrowsCount")
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS.seconds)
        } catch (failure: TimeoutException) {
            throw UsageEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw UsageEventTransportFailure(failure)
        } catch (failure: KafkaException) {
            throw UsageEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw UsageEventTransportFailure(failure)
        }
    }

    private companion object {
        const val TOPIC = "usage.events.v1"
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }
}
