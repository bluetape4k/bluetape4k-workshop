package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class KafkaInvoiceEventTransport(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : InvoiceEventTransport {
    @Suppress("ThrowsCount")
    override fun publish(partitionKey: String, payload: String) {
        try {
            kafkaTemplate.send(TOPIC, partitionKey, payload).get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: TimeoutException) {
            throw InvoiceEventTransportFailure(failure)
        } catch (failure: ExecutionException) {
            throw InvoiceEventTransportFailure(failure)
        } catch (failure: KafkaException) {
            throw InvoiceEventTransportFailure(failure)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InvoiceEventTransportFailure(failure)
        }
    }

    private companion object {
        const val TOPIC = "invoice.events.v1"
        const val PUBLISH_TIMEOUT_SECONDS = 5L
    }
}
