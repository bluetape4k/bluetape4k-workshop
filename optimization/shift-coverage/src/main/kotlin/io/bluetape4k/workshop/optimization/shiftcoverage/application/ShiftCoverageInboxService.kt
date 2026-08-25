package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.workshop.optimization.shiftcoverage.adapter.ShiftCoverageProvider
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.EventId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** six event namespace가 공유하는 provider/event inbox key입니다. */
data class ShiftCoverageInboxEvent(
    val provider: ShiftCoverageProvider,
    val eventId: EventId,
    val digest: String,
    val revision: Long,
) {
    init {
        require(digest.matches(Regex("[0-9a-f]{64}"))) { "event digest must be lowercase SHA-256" }
        require(revision >= 0L) { "provider revision must be non-negative" }
    }
}

enum class ShiftCoverageInboxStatus { RECEIVED, RETRYABLE, RETRY_EXHAUSTED, APPLIED, DUPLICATE, STALE, EVENT_KEY_REUSED }

class ShiftCoverageInboxRequeueRejected(message: String) : IllegalStateException(message)

data class ShiftCoverageInboxRecord(
    val event: ShiftCoverageInboxEvent,
    val status: ShiftCoverageInboxStatus,
    val attempt: Int = 0,
    val nextAttemptAt: Instant? = null,
    val requestId: String = Uuid.V7.nextId().toString(),
    val lastReason: String? = null,
)

/** monotonic provider revision과 bounded retry/requeue를 DB inbox semantics로 표현합니다. */
@Profile("demo")
@Service
class ShiftCoverageInboxService {
    private val records = ConcurrentHashMap<String, ShiftCoverageInboxRecord>()
    private val retryDelays = listOf(2L, 4L, 8L, 16L, 30L).map(Duration::ofSeconds)

    fun claim(event: ShiftCoverageInboxEvent): ShiftCoverageInboxRecord {
        val key = key(event)
        val result = records.compute(key) { _, current ->
            when {
                current == null -> ShiftCoverageInboxRecord(event, ShiftCoverageInboxStatus.RECEIVED)
                current.event.digest != event.digest -> current.copy(status = ShiftCoverageInboxStatus.EVENT_KEY_REUSED, lastReason = "digest mismatch")
                event.revision < current.event.revision -> current.copy(status = ShiftCoverageInboxStatus.STALE)
                event.revision == current.event.revision -> when (current.status) {
                    ShiftCoverageInboxStatus.RETRY_EXHAUSTED -> current
                    ShiftCoverageInboxStatus.RECEIVED -> current.copy(status = ShiftCoverageInboxStatus.DUPLICATE)
                    else -> current.copy(status = ShiftCoverageInboxStatus.DUPLICATE)
                }
                else -> ShiftCoverageInboxRecord(event, ShiftCoverageInboxStatus.APPLIED, requestId = current.requestId)
            }
        }
        return checkNotNull(result)
    }

    fun fail(event: ShiftCoverageInboxEvent, now: Instant): ShiftCoverageInboxRecord {
        val key = key(event)
        val result = records.compute(key) { _, current ->
            val existing = current ?: ShiftCoverageInboxRecord(event, ShiftCoverageInboxStatus.RECEIVED)
            val nextAttempt = existing.attempt + 1
            val terminal = nextAttempt >= retryDelays.size
            existing.copy(
                status = if (terminal) ShiftCoverageInboxStatus.RETRY_EXHAUSTED else ShiftCoverageInboxStatus.RETRYABLE,
                attempt = nextAttempt,
                nextAttemptAt = if (terminal) null else now.plus(retryDelays[nextAttempt - 1]),
                lastReason = "provider failure",
            )
        }
        return checkNotNull(result)
    }

    fun requeue(provider: ShiftCoverageProvider, eventId: EventId, reason: String): ShiftCoverageInboxRecord {
        require(reason.isNotBlank()) { "requeue reason must not be blank" }
        val key = "$provider|${eventId.value}"
        val result = records.computeIfPresent(key) { _, current ->
            if (current.status != ShiftCoverageInboxStatus.RETRY_EXHAUSTED) current
            else current.copy(status = ShiftCoverageInboxStatus.RECEIVED, attempt = 0, requestId = Uuid.V7.nextId().toString(), lastReason = reason)
        }
        return result ?: throw ShiftCoverageInboxRequeueRejected("retry-exhausted inbox event does not exist")
    }

    fun find(provider: ShiftCoverageProvider, eventId: EventId): ShiftCoverageInboxRecord? = records[key(provider, eventId)]

    private fun key(event: ShiftCoverageInboxEvent): String = key(event.provider, event.eventId)
    private fun key(provider: ShiftCoverageProvider, eventId: EventId): String = "$provider|${eventId.value}"
}
