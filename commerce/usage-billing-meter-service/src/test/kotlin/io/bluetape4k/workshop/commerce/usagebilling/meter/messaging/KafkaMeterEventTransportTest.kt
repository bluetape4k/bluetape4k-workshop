package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.kafka.KafkaException
import org.springframework.kafka.core.KafkaTemplate

class KafkaMeterEventTransportTest {
    @Test
    fun `synchronous Kafka send failure remains retryable at the outbox boundary`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        every { kafkaTemplate.send(any(), any(), any()) } throws KafkaException("send failed")

        assertFailsWith<MeterEventTransportFailure> {
            KafkaMeterEventTransport(kafkaTemplate).publish("tenant-a|Meter|api-calls", "{}")
        }
    }
}
