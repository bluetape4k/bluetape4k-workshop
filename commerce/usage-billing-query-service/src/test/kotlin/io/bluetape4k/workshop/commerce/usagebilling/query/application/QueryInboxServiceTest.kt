package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.usagebilling.query.config.QueryMetrics
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryInboxEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryProjectionJournal
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class QueryInboxServiceTest {

    companion object: KLogging()

    private val meterRegistry = SimpleMeterRegistry()
    private val journal = InMemoryQueryProjectionJournal()
    private val service = QueryInboxService(journal, QueryMetrics(meterRegistry))

    @BeforeEach
    fun beforeEach() {
        meterRegistry.clear()
        journal.reset()
    }

    @Test
    fun `new event mutates its local read model and advances the projection checkpoint together`() {
        service.apply(event()).applied.shouldBeTrue()
        journal.readModelEventIds shouldHaveSize 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
    fun `duplicate event does not mutate the read model or advance the checkpoint`() {
        val event = event()
        service.apply(event)

        service.apply(event).applied.shouldBeFalse()
        journal.readModelEventIds shouldHaveSize 1
        journal.checkpoint shouldBeEqualTo 1L
    }

    @Test
    fun `terminal inbox results are recorded with low cardinality outcome and event type tags`() {
        service.apply(event())


        meterRegistry.find("usage_billing_inbox_outcome_total")
            .tags("service", "query", "outcome", "APPLIED", "event_type", "InvoiceIssued")
            .counter().shouldNotBeNull()
            .count() shouldBeEqualTo 1.0
    }

    private fun event(): QueryInboxEvent =
        QueryInboxEvent(UUID.randomUUID(), "tenant-a", "InvoiceIssued")

    private class InMemoryQueryProjectionJournal: QueryProjectionJournal {
        override val readModelEventIds = mutableSetOf<UUID>()
        override var checkpoint: Long = 0

        override fun hasEvent(eventId: UUID): Boolean = eventId in readModelEventIds

        override fun apply(event: QueryInboxEvent) {
            readModelEventIds += event.eventId
            checkpoint += 1
        }

        fun reset() {
            readModelEventIds.clear()
            checkpoint = 0
        }
    }
}
