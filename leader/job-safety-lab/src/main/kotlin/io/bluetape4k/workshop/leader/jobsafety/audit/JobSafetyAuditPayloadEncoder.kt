package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.http.LeaderAuditHttpPayload
import io.bluetape4k.leader.audit.http.LeaderAuditPayloadEncoder
import java.time.Instant

/**
 * leader audit event를 민감한 식별자 없이 bounded JSON payload로 변환합니다.
 *
 * upstream event 자체는 이미 sanitizer를 거친 snapshot이지만, wire DTO는 다시 필요한
 * 필드만 명시하여 token, lock/node/slot/leader 식별자와 raw exception message가 JSON
 * schema에 들어갈 수 없게 합니다. serialization은 Jackson 3 byte API를 직접 사용합니다.
 *
 * @param maxPayloadBytes 이 adapter가 허용하는 payload byte 상한입니다. upstream hard
 * bound인 1 MiB보다 클 수 없습니다.
 */
class JobSafetyAuditPayloadEncoder(
    private val maxPayloadBytes: Int,
) : LeaderAuditPayloadEncoder {

    init {
        require(maxPayloadBytes in 1..MAX_PAYLOAD_BYTES) {
            "maxPayloadBytes must be in 1..$MAX_PAYLOAD_BYTES: $maxPayloadBytes"
        }
    }

    override fun encode(event: LeaderAuditExportEvent): LeaderAuditHttpPayload {
        val wire = when (event) {
            is LeaderAuditExportEvent.History -> HistoryWire.from(event)
            is LeaderAuditExportEvent.Lifecycle -> LifecycleWire.from(event)
        }
        val bytes = Jackson.defaultJsonMapper.writeValueAsBytes(wire)
        require(bytes.size <= maxPayloadBytes) {
            "audit payload exceeds configured byte bound: ${bytes.size} > $maxPayloadBytes"
        }
        return LeaderAuditHttpPayload.of(CONTENT_TYPE, bytes)
    }

    /** History wire schema; sensitive source fields are intentionally not represented. */
    private data class HistoryWire(
        val occurredAt: Instant,
        val kind: String,
        val status: String,
        val durationMs: Long?,
        val errorType: String?,
        val attributes: Map<String, String>,
    ) {
        companion object {
            fun from(event: LeaderAuditExportEvent.History): HistoryWire = HistoryWire(
                occurredAt = event.occurredAt,
                kind = event.kind.name,
                status = event.status.name,
                durationMs = event.durationMs,
                errorType = event.errorType,
                attributes = event.attributes,
            )
        }
    }

    /** Lifecycle wire schema; lock and leader identities are intentionally omitted. */
    private data class LifecycleWire(
        val occurredAt: Instant,
        val outcome: String,
        val leaseExpiry: Instant?,
        val attributes: Map<String, String>,
    ) {
        companion object {
            fun from(event: LeaderAuditExportEvent.Lifecycle): LifecycleWire = LifecycleWire(
                occurredAt = event.occurredAt,
                outcome = event.outcome.name,
                leaseExpiry = event.leaseExpiry,
                attributes = event.attributes,
            )
        }
    }

    private companion object {
        const val CONTENT_TYPE = "application/json"
        const val MAX_PAYLOAD_BYTES = 1024 * 1024
    }
}
