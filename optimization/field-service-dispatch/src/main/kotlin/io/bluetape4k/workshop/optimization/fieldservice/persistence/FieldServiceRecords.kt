package io.bluetape4k.workshop.optimization.fieldservice.persistence

import io.bluetape4k.workshop.optimization.fieldservice.domain.AggregateId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEventType
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanProposal
import io.bluetape4k.workshop.optimization.fieldservice.domain.Visit
import io.bluetape4k.workshop.optimization.fieldservice.domain.Worker
import java.io.Serializable
import java.time.Instant
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/** event repository가 사용하는 command metadata이며 payload는 이미 canonicalized 상태입니다. */
data class FieldServiceCommand(
    val aggregateType: String,
    val aggregateId: AggregateId,
    val eventKey: EventKey,
    val eventType: FieldServiceEventType,
    val digest: EventDigest,
    val payloadSummary: String,
    val expectedVersion: Long = 0L,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** durable idempotent 결과 반환에 사용하는 redacted event identity입니다. */
data class StoredFieldServiceEvent(
    val digest: EventDigest,
    val aggregateVersion: Long,
)

enum class EventAppendResult {
    APPENDED,
    DUPLICATE,
    EVENT_KEY_REUSED,
}

/** 최소 persisted outbox row이며 raw provider response는 의도적으로 저장하지 않습니다. */
data class OutboxRecord(
    val id: Long = 0L,
    val payload: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attempt: Int = 0,
    val maxAttempts: Int = 5,
    val nextAttemptAt: Instant,
    val leaseOwner: String? = null,
    val leaseToken: String? = null,
    val leaseExpiresAt: Instant? = null,
    val lastError: String? = null,
    val createdAt: Instant = Instant.now(),
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** redacted serialized plan projection을 위한 Jackson 3 codec입니다. */
internal object FieldServiceRecordCodec {
    private val mapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .build()

    fun encode(plan: PlanProposal): String = mapper.writeValueAsString(plan)

    fun decodePlan(payload: String): PlanProposal = mapper.readValue(payload, PlanProposal::class.java)

    fun encodeWorker(worker: Worker): String = mapper.writeValueAsString(worker)

    fun decodeWorker(payload: String): Worker = mapper.readValue(payload, Worker::class.java)

    fun encodeVisit(visit: Visit): String = mapper.writeValueAsString(visit)

    fun decodeVisit(payload: String): Visit = mapper.readValue(payload, Visit::class.java)
}
