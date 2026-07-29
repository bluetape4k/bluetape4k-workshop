package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.UUID

class QueryInboxServiceTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val journal = InMemoryQueryProjectionJournal()
    private val service = QueryInboxService(journal, QueryMetrics(meterRegistry))

    @Test
    fun `new event mutates its local read model and advances the projection checkpoint together`() {
        service.apply(event()).applied shouldBeEqualTo true
        journal.readModelEventIds.size shouldBeEqualTo 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
    fun `duplicate event does not mutate the read model or advance the checkpoint`() {
        val event = event()
        service.apply(event)

        service.apply(event).applied shouldBeEqualTo false
        journal.readModelEventIds.size shouldBeEqualTo 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
    fun `terminal inbox results are recorded with low cardinality outcome and event type tags`() {
        service.apply(event())

        requireNotNull(
            meterRegistry.find("usage_billing_inbox_outcome_total")
                .tags("service", "query", "outcome", "APPLIED", "event_type", "InvoiceIssued")
                .counter(),
        ).count() shouldBeEqualTo 1.0
    }

    private fun event(): QueryInboxEvent = QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

    private class InMemoryQueryProjectionJournal : QueryProjectionJournal {
        override val readModelEventIds = mutableSetOf<UUID>()
        override var checkpoint: Long = 0

        override fun hasEvent(eventId: UUID): Boolean = eventId in readModelEventIds

        override fun apply(event: QueryInboxEvent) {
            readModelEventIds += event.eventId
            checkpoint += 1
        }
    }
}
