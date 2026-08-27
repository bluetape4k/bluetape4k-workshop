package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test

/**
 * Kafka value로 사용되는 strict JSON 문자열 계약을 검증합니다.
 */
class KafkaFailoverCodecTest {

    private val codec = KafkaFailoverCodec()
    private val event = KafkaFailoverEvent(
        eventId = "event-1",
        sequence = 3L,
        payload = "payload-1",
    )

    @Test
    fun `encode uses canonical field order and decode round trips`() {
        val encoded = codec.encode(event)

        encoded shouldBeEqualTo
            "{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}"
        codec.decode(encoded) shouldBeEqualTo event
        codec.encode(codec.decode(encoded)) shouldBeEqualTo encoded
    }

    @Test
    fun `fingerprint is stable for the canonical event and changes with content`() {
        val sameEvent = KafkaFailoverEvent("event-1", 3L, "payload-1")
        val conflictingEvent = KafkaFailoverEvent("event-1", 3L, "payload-2")

        codec.fingerprint(event) shouldBeEqualTo codec.fingerprint(sameEvent)
        (codec.fingerprint(event) != codec.fingerprint(conflictingEvent)).shouldBeTrue()
        codec.fingerprint(event).length shouldBeEqualTo 64
    }

    @Test
    fun `decode rejects unknown duplicate and missing fields`() {
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\",\"extra\":true}")
        assertInvalid("{\"eventId\":\"event-1\",\"eventId\":\"event-2\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"partitionKey\":\"failover-partition-0\"}")
    }

    @Test
    fun `decode rejects null malformed trailing and non object JSON`() {
        assertInvalid("")
        assertInvalid("   ")
        assertInvalid("null")
        assertInvalid("{\"eventId\":null,\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":oops,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"} trailing")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}{\"eventId\":\"event-2\"}")
        assertInvalid("[\"event-1\",3,\"payload-1\",\"failover-partition-0\"]")
    }

    @Test
    fun `decode rejects scalar coercion`() {
        assertInvalid("{\"eventId\":1,\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":\"3\",\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3.0,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":true,\"payload\":\"payload-1\",\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":{\"raw\":true},\"partitionKey\":\"failover-partition-0\"}")
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"payload-1\",\"partitionKey\":false}")
    }

    @Test
    fun `decode rejects oversized and deeply nested JSON`() {
        val oversizedPayload = "x".repeat(20_000)
        assertInvalid("{\"eventId\":\"event-1\",\"sequence\":3,\"payload\":\"$oversizedPayload\",\"partitionKey\":\"failover-partition-0\"}")

        val deeplyNested = "[".repeat(100) + "0" + "]".repeat(100)
        assertInvalid(deeplyNested)
    }

    private fun assertInvalid(json: String) {
        assertFailsWith<IllegalArgumentException> { codec.decode(json) }
    }
}
