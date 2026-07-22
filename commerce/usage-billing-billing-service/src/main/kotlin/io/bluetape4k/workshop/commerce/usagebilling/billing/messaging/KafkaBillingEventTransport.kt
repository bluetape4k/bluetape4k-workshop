package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class KafkaBillingEventTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : BillingEventTransport {
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            throw BillingEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw BillingEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BillingEventTransportFailure(failure)
        }
    }

    private companion object {
        const val TOPIC = "billing.events.v1"
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }
}
