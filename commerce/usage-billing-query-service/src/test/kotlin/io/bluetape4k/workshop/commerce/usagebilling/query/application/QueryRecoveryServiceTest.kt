package io.bluetape4k.workshop.commerce.usagebilling.query.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryQuarantineEvent
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoveryJournal
import io.bluetape4k.workshop.commerce.usagebilling.query.domain.QueryRecoverySnapshot
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QueryRecoveryServiceTest {
    private val journal = InMemoryQueryRecoveryJournal()
    private val service = QueryRecoveryService(journal)

    @Test
    fun `redrive records the operator decision without changing financial state`() {
        val eventId = UUID.randomUUID()
        journal.quarantine(
            QueryQuarantineEvent(eventId, "tenant-a", "InvoiceIssued", "unsupported_schema", Instant.EPOCH),
        )

        service.redrive(eventId, "operator-a", "correlation-a").requested shouldBeEqualTo true
        journal.snapshot().quarantineCount shouldBeEqualTo 0
        journal.redriveActors shouldBeEqualTo listOf("operator-a")
    }

    private class InMemoryQueryRecoveryJournal : QueryRecoveryJournal {
        private val quarantines = linkedMapOf<UUID, QueryQuarantineEvent>()
        val redriveActors = mutableListOf<String>()

        override fun snapshot(): QueryRecoverySnapshot = QueryRecoverySnapshot(quarantines.size.toLong(), null)

        override fun quarantine(event: QueryQuarantineEvent) {
            quarantines[event.eventId] = event
        }

        override fun requestRedrive(eventId: UUID, actor: String, correlationId: String): Boolean =
            quarantines.remove(eventId)?.let {
                check(correlationId == "correlation-a")
                redriveActors += actor
                true
            } ?: false
    }
}
