package io.bluetape4k.workshop.commerce.usagebilling.meter.messaging

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class MeterOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    RETRY_WAIT,
    QUARANTINED,
}

data class MeterOutboxLease(
    val eventId: UUID,
    val partitionKey: String,
    val payload: String,
    val status: MeterOutboxStatus,
    val claimOwner: String? = null,
)

data class MeterOutboxPublishResult(
    val claimed: Int = 0,
    val published: Int = 0,
    val retryWait: Int = 0,
)

interface MeterOutboxJournal {
    fun claim(owner: String, now: Instant, limit: Int): List<MeterOutboxLease>

    fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean

    fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean
}

interface MeterEventTransport {
    fun publish(partitionKey: String, payload: String)
}

class MeterEventTransportFailure(cause: Exception) : RuntimeException("meter_event_transport_failure", cause)

@Component
class MeterOutboxPublisher(
    private val journal: MeterOutboxJournal,
    private val transport: MeterEventTransport,
    private val clock: Clock,
) {
    fun publishPending(limit: Int = DEFAULT_BATCH_SIZE): MeterOutboxPublishResult {
        val owner = Uuid.V7.nextId().toString()
        val claimed = journal.claim(owner, clock.instant(), limit)
        var published = 0
        var retryWait = 0

        claimed.forEach { event ->
            try {
                transport.publish(event.partitionKey, event.payload)
                if (journal.markPublished(event.eventId, owner, clock.instant())) {
                    published += 1
                }
            } catch (failure: MeterEventTransportFailure) {
                journal.markRetryWait(event.eventId, owner, clock.instant())
                retryWait += 1
                log.warn(failure) { "meter.outbox.publish.retry_wait eventId=${event.eventId}" }
            }
        }
        return MeterOutboxPublishResult(claimed.size, published, retryWait)
    }

    private companion object : KLogging() {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
