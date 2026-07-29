package io.bluetape4k.workshop.commerce.usagebilling.usage.messaging

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.stereotype.Component
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class UsageOutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED,
    RETRY_WAIT,
    QUARANTINED,
}

data class UsageOutboxLease(
    val eventId: UUID,
    val partitionKey: String,
    val payload: String,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class UsageOutboxPublishResult(
    val claimed: Int = 0,
    val published: Int = 0,
    val retryWait: Int = 0,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

interface UsageOutboxJournal {
    fun claim(owner: String, now: Instant, limit: Int): List<UsageOutboxLease>

    fun markPublished(eventId: UUID, owner: String, now: Instant): Boolean

    fun markRetryWait(eventId: UUID, owner: String, now: Instant): Boolean
}

interface UsageEventTransport {
    fun publish(partitionKey: String, payload: String)
}

class UsageEventTransportFailure(cause: Exception) : RuntimeException("usage_event_transport_failure", cause)

@Component
class UsageOutboxPublisher(
    private val journal: UsageOutboxJournal,
    private val transport: UsageEventTransport,
    private val clock: Clock,
) {
    fun publishPending(limit: Int = DEFAULT_BATCH_SIZE): UsageOutboxPublishResult {
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
            } catch (failure: UsageEventTransportFailure) {
                journal.markRetryWait(event.eventId, owner, clock.instant())
                retryWait += 1
                log.warn(failure) { "usage.outbox.publish.retry_wait eventId=${event.eventId}" }
            }
        }
        return UsageOutboxPublishResult(claimed.size, published, retryWait)
    }

    private companion object : KLogging() {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
