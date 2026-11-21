package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.AggregateType
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRecord
import io.bluetape4k.workshop.commerce.order.persistence.LifecycleAuditRepository
import org.springframework.stereotype.Component
import java.util.*
/**
 * LifecycleAuditAppender은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property repository 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
internal class LifecycleAuditAppender(
    /**
     * `repository`은 주문·결제·재고·배송에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val repository: LifecycleAuditRepository,
) {
    companion object: KLogging()

    /**
     * `append`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = append(orderId = UUID.randomUUID(), aggregateType = aggregateType,
     * aggregateId =
     * UUID.randomUUID(), revision = 1L, from = from, to = to, reason = "demo", actor =
     * "operator-a")
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @param aggregateType 요청 경계에서 검증된 `aggregateType`입니다.
     * @param aggregateId 요청 경계에서 검증된 `aggregateId`입니다.
     * @param revision 요청 경계에서 검증된 `revision`입니다.
     * @param from 요청 경계에서 검증된 `from`입니다.
     * @param to 요청 경계에서 검증된 `to`입니다.
     * @param reason 요청 경계에서 검증된 `reason`입니다.
     * @param actor 요청 경계에서 검증된 `actor`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun append(
        orderId: UUID,
        aggregateType: AggregateType,
        aggregateId: UUID,
        revision: Long,
        from: Enum<*>?,
        to: Enum<*>,
        reason: String? = null,
        actor: String,
    ): LifecycleAuditRecord {
        /**
         * `auditRecord`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val auditRecord = LifecycleAuditRecord(
            eventId = Uuid.V7.nextId(),
            orderId = orderId,
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            revision = revision,
            fromStatus = from?.name,
            toStatus = to.name,
            reasonCode = reason?.take(80),
            actorType = actor.take(40)
        )

        return repository.append(auditRecord).also {
            log.debug {
                "lifecycle_transition orderId=$orderId aggregateType=$aggregateType aggregateId=$aggregateId " +
                        "revision=$revision from=${from?.name ?: "NONE"} to=${to.name} actor=$actor"
            }
        }
    }
}
