package io.bluetape4k.workshop.commerce.usagebilling.query.domain

import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.UUID

data class QueryInboxEvent(
    val eventId: UUID,
    val tenantId: String,
    val eventType: String,
    val aggregateType: String = "Invoice",
    val aggregateId: String = eventId.toString(),
    val aggregateVersion: Long = 1,
    val payload: String = "{}",
    val payloadDigest: String = EMPTY_JSON_DIGEST,
    val receivedAt: Instant = Instant.now(),
) {
    init {
        tenantId.requireNotBlank("tenantId")
        eventType.requireNotBlank("eventType")
        aggregateType.requireNotBlank("aggregateType")
        aggregateId.requireNotBlank("aggregateId")
        payload.requireNotBlank("payload")
        payloadDigest.requireNotBlank("payloadDigest")
    }

    companion object {
        private const val EMPTY_JSON_DIGEST = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
    }
}

data class QueryApplyResult(
    val applied: Boolean,
)

data class QueryQuarantineEvent(
    val eventId: UUID,
    val tenantId: String,
    val eventType: String,
    val reason: String,
    val quarantinedAt: Instant,
) {
    init {
        tenantId.requireNotBlank("tenantId")
        eventType.requireNotBlank("eventType")
        reason.requireNotBlank("reason")
    }
}

data class QueryRecoverySnapshot(
    val quarantineCount: Long,
    val oldestQuarantineAt: Instant?,
)

data class QueryRedriveResult(
    val requested: Boolean,
)

interface QueryProjectionJournal {
    val readModelEventIds: Set<UUID>
    var checkpoint: Long

    fun hasEvent(eventId: UUID): Boolean

    fun apply(event: QueryInboxEvent)
}

interface QueryRecoveryJournal {
    fun snapshot(): QueryRecoverySnapshot

    fun quarantine(event: QueryQuarantineEvent)

    fun requestRedrive(eventId: UUID, actor: String, correlationId: String): Boolean
}
