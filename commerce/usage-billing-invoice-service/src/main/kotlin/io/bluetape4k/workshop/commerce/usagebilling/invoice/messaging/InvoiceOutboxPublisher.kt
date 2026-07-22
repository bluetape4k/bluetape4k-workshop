package io.bluetape4k.workshop.commerce.usagebilling.invoice.messaging

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class InvoiceOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    RETRY_WAIT,
    QUARANTINED,
}

data class InvoiceOutboxLease(
    val eventId: UUID,
    val partitionKey: String,
    val payload: String,
)

data class InvoiceOutboxPublishResult(
    val claimed: Int = 0,
    val published: Int = 0,
    val retryWait: Int = 0,
)

interface InvoiceOutboxJournal {
    fun claim(owner: String, now: Instant, limit: Int): List<InvoiceOutboxLease>
    fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean
    fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean
}

interface InvoiceEventTransport {
    fun publish(partitionKey: String, payload: String)
}

class InvoiceEventTransportFailure(cause: Exception) : RuntimeException("invoice_event_transport_failure", cause)

@Component
class InvoiceOutboxPublisher(
    private val journal: InvoiceOutboxJournal,
    private val transport: InvoiceEventTransport,
    private val clock: Clock,
) {
    fun publishPending(limit: Int = DEFAULT_BATCH_SIZE): InvoiceOutboxPublishResult {
        val owner = Uuid.V7.nextId().toString()
        val claimed = journal.claim(owner, clock.instant(), limit)
        var published = 0
        var retryWait = 0
        claimed.forEach { event ->
            try {
                transport.publish(event.partitionKey, event.payload)
                if (journal.markPublished(event.eventId, owner, clock.instant())) published += 1
            } catch (failure: InvoiceEventTransportFailure) {
                journal.markRetryWait(event.eventId, owner, clock.instant())
                retryWait += 1
                log.warn(failure) { "invoice.outbox.publish.retry_wait eventId=${event.eventId}" }
            }
        }
        return InvoiceOutboxPublishResult(claimed.size, published, retryWait)
    }

    private companion object : KLogging() {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
