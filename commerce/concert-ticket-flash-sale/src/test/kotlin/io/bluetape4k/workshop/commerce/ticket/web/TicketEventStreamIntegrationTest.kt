package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.idgenerators.uuid.Uuid
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal class TicketEventStreamIntegrationTest {
    @Test
    fun `terminal event between snapshot and subscribe is caught up`() {
        val stream = TicketEventStream(4, 4, clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val scope = StreamScope.OwnerAttempt(Uuid.V7.nextId(), Uuid.V7.nextId())

        val subscription = stream.subscribe(scope) {
            stream.publish(scope, "purchase_terminal", mapOf("state" to "approved"))
            mapOf("state" to "payment_authorizing")
        }

        subscription.snapshot.highWater shouldBeEqualTo 0L
        subscription.poll()?.type shouldBeEqualTo "purchase_terminal"
        subscription.poll() shouldBeEqualTo null
        subscription.close()
    }
}
