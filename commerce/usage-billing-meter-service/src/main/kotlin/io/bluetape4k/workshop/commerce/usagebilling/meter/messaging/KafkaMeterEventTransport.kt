package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class KafkaMeterEventTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : MeterEventTransport {
    @Suppress("ThrowsCount")
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: KafkaException) {
            throw MeterEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MeterEventTransportFailure(failure)
        }
    }

    private companion object {
        const val TOPIC = "meter.events.v1"
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }
}
