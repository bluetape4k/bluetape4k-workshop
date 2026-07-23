package io.bluetape4k.workshop.commerce.usagebilling.query.domain

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
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
) : Serializable {
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
        private const val serialVersionUID: Long = 1L
    }
}

data class QueryApplyResult(
    val applied: Boolean,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class QueryQuarantineEvent(
    val eventId: UUID,
    val tenantId: String,
    val eventType: String,
    val reason: String,
    val quarantinedAt: Instant,
) : Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        eventType.requireNotBlank("eventType")
        reason.requireNotBlank("reason")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class QueryRecoverySnapshot(
    val quarantineCount: Long,
    val oldestQuarantineAt: Instant?,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class QueryRedriveResult(
    val requested: Boolean,
) : Serializable {
    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

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
