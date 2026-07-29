package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.springframework.stereotype.Repository

/**
 * aggregate revision별로 idempotent audit outcome 하나를 append합니다.
 *
 * composite primary key와 `insertIgnore` 덕분에 durable transition record를 중복하지 않으면서
 * command retry를 관측할 수 있습니다.
 */
@Repository
internal class ReservationAuditRepository {
    fun record(
        aggregateType: String,
        aggregateId: Long,
        revision: Long,
        outcome: String,
        reason: String? = null,
    ) {
        require(aggregateType.matches(AGGREGATE_TYPE)) { "aggregateType must be a bounded stable code" }
        require(outcome.matches(OUTCOME)) { "outcome must be a bounded stable code" }
        require(reason == null || reason.length <= 80) { "reason must be at most 80 characters" }
        val inserted =
            ReservationAuditTable
                .insertIgnore {
                    it[ReservationAuditTable.aggregateType] = aggregateType
                    it[ReservationAuditTable.aggregateId] = aggregateId
                    it[ReservationAuditTable.revision] = revision
                    it[ReservationAuditTable.outcome] = outcome
                    it[ReservationAuditTable.reason] = reason
                }.insertedCount == 1
        log.debug {
            "reservation_transition_audit aggregateType=$aggregateType aggregateId=$aggregateId " +
                "revision=$revision outcome=$outcome inserted=$inserted"
        }
    }

    companion object : KLogging() {
        private val AGGREGATE_TYPE = Regex("[A-Z_]{3,32}")
        private val OUTCOME = Regex("[A-Z0-9_]{2,32}")
    }
}
