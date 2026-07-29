package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.UUID

internal class TicketEventStreamCapacityIntegrationTest {
    @Test
    fun `slow consumer is disconnected and releases its connection permit`() {
        val stream = TicketEventStream(queueSize = 1, maxConnections = 1)
        val scope = StreamScope.PublicSale(UUID.randomUUID())
        stream.subscribe(scope) { mapOf("status" to "open") }
        stream.publish(scope, "inventory", mapOf("remaining" to "10"))

        assertFailsWith<TicketStreamSlowConsumer> {
            stream.publish(scope, "inventory", mapOf("remaining" to "9"))
        }

        stream.activeConnections() shouldBeEqualTo 0
        stream.subscribe(scope) { mapOf("status" to "open") }.close()
    }

    @Test
    fun `public stream rejects owner and payment fields`() {
        val stream = TicketEventStream(2, 1)
        assertFailsWith<IllegalArgumentException> {
            stream.publish(
                StreamScope.PublicSale(UUID.randomUUID()),
                "purchase",
                mapOf("buyerSubjectId" to UUID.randomUUID().toString()),
            )
        }
    }
}
