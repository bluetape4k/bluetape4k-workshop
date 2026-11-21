package io.bluetape4k.workshop.commerce.reservation.persistence

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireMatches
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

    companion object: KLogging() {
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val AGGREGATE_TYPE = Regex("[A-Z_]{3,32}")
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val OUTCOME = Regex("[A-Z0-9_]{2,32}")
    }

    /**
     * record은 예약 제어면 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param aggregateType 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param aggregateId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param revision 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun record(
        aggregateType: String,
        aggregateId: Long,
        revision: Long,
        outcome: String,
        reason: String? = null,
    ) {
        aggregateType.requireMatches(AGGREGATE_TYPE, "aggregateType")
        outcome.requireMatches(OUTCOME, "outcome")
        require(reason == null || reason.length <= 80) { "reason must be at most 80 characters" }

        /**
         * `inserted`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val inserted = ReservationAuditTable
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

}
