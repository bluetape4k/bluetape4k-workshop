package io.bluetape4k.workshop.commerce.usagebilling.billing.messaging

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class BillingOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    RETRY_WAIT,
    QUARANTINED,
}

data class BillingOutboxLease(
    val eventId: UUID,
    val partitionKey: String,
    val payload: String,
)

data class BillingOutboxPublishResult(
    val claimed: Int = 0,
    val published: Int = 0,
    val retryWait: Int = 0,
)

interface BillingOutboxJournal {
    fun claim(owner: String, now: Instant, limit: Int): List<BillingOutboxLease>
    fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean
    fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean
}

interface BillingEventTransport {
    fun publish(partitionKey: String, payload: String)
}

class BillingEventTransportFailure(cause: Exception) : RuntimeException("billing_event_transport_failure", cause)

@Component
class BillingOutboxPublisher(
    private val journal: BillingOutboxJournal,
    private val transport: BillingEventTransport,
    private val clock: Clock,
) {
    fun publishPending(limit: Int = DEFAULT_BATCH_SIZE): BillingOutboxPublishResult {
        val owner = Uuid.V7.nextId().toString()
        val claimed = journal.claim(owner, clock.instant(), limit)
        var published = 0
        var retryWait = 0
        claimed.forEach { event ->
            try {
                transport.publish(event.partitionKey, event.payload)
                if (journal.markPublished(event.eventId, owner, clock.instant())) published += 1
            } catch (failure: BillingEventTransportFailure) {
                journal.markRetryWait(event.eventId, owner, clock.instant())
                retryWait += 1
                log.warn(failure) { "billing.outbox.publish.retry_wait eventId=${event.eventId}" }
            }
        }
        return BillingOutboxPublishResult(claimed.size, published, retryWait)
    }

    private companion object : KLogging() {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
