package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.support.requireNotNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * 장애 조치 reference event의 불변성과 입력 계약을 검증합니다.
 */
class KafkaFailoverEventTest {

    @Test
    fun `event keeps the fixed topic and partition key`() {
        val event = KafkaFailoverEvent(
            eventId = "event-1",
            sequence = 0L,
            payload = "payload-1",
        )

        event.eventId shouldBeEqualTo "event-1"
        event.sequence shouldBeEqualTo 0L
        event.payload shouldBeEqualTo "payload-1"
        event.partitionKey shouldBeEqualTo KafkaFailoverEvent.PARTITION_KEY
        KafkaFailoverEvent.TOPIC shouldBeEqualTo "kafka-failover-reference"
    }

    @Test
    fun `event rejects blank fields negative sequence and a non reference partition key`() {
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvent(eventId = " ", sequence = 0L, payload = "payload")
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvent(eventId = "event-1", sequence = 0L, payload = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvent(
                eventId = "event-1",
                sequence = 0L,
                payload = "payload",
                partitionKey = " ",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvent(eventId = "event-1", sequence = -1L, payload = "payload")
        }
        assertFailsWith<IllegalArgumentException> {
            KafkaFailoverEvent(
                eventId = "event-1",
                sequence = 0L,
                payload = "payload",
                partitionKey = "another-partition",
            )
        }
    }

    @Test
    fun `copy cannot bypass constructor validation`() {
        val event = KafkaFailoverEvent("event-1", 0L, "payload")

        assertFailsWith<IllegalArgumentException> { event.copy(eventId = " ") }
        assertFailsWith<IllegalArgumentException> { event.copy(sequence = -1L) }
    }

    @Test
    fun `serializable event declares a static serial version field`() {
        val field = KafkaFailoverEvent::class.java
            .declaredFields
            .firstOrNull { it.name == "serialVersionUID" }
            .requireNotNull("KafkaFailoverEvent.serialVersionUID")

        Modifier.isStatic(field.modifiers).shouldBeTrue()
        java.io.Serializable::class.java.isAssignableFrom(KafkaFailoverEvent::class.java).shouldBeTrue()
    }
}
