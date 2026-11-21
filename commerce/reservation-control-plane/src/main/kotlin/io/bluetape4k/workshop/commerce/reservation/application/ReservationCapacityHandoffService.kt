package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationChannel
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationDeliveryRepository
import io.bluetape4k.workshop.commerce.reservation.notification.NotificationRequest
import io.bluetape4k.workshop.commerce.reservation.persistence.CapacityResourceRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationAuditRepository
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 이미 점유된 capacity slot 하나를 FIFO waitlist head로 이전하거나, 대기자가 없으면 해제합니다.
 * caller가 transaction을 소유하며, 이 service를 호출하기 전에 resource를 lock해야 합니다.
 * @property resources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property waitlist 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property notifications 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property audits 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
internal class ReservationCapacityHandoffService(
    /**
     * `resources`은 예약 제어면에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val resources: CapacityResourceRepository,
    /**
     * `waitlist`은 예약 제어면에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    private val waitlist: WaitlistCommandService,
    /**
     * `notifications`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val notifications: NotificationDeliveryRepository,
    /**
     * `audits`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val audits: ReservationAuditRepository,
) {
    companion object: KLogging()

    /**
     * `promoteOrRelease`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = promoteOrRelease(resourceId = 1L, now = now)
     * ```
     * @param resourceId 요청 경계에서 검증된 `resourceId`입니다.
     * @param now 요청 경계에서 검증된 `now`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun promoteOrRelease(
        resourceId: Long,
        now: Instant,
    ): ReservationOfferRecord? {
        val offer = waitlist.promote(resourceId)
        if (offer == null) {
            val resource = resources.findByIdForUpdate(resourceId)
            resources.release(resource.id, resource.revision)
                .checkBeTrue { "capacity release lost after terminal reservation transition" }
            audits.record(
                aggregateType = "CAPACITY_RESOURCE",
                aggregateId = resource.id,
                revision = resource.revision + 1,
                outcome = "CAPACITY_RELEASED"
            )
            log.debug { "reservation_capacity_released resourceId=$resourceId revision=${resource.revision + 1}" }
            return null
        }
        notifications.enqueue(
            NotificationRequest(
                deliveryId = "offer-created:${offer.id}:${offer.revision}:in-app",
                channel = NotificationChannel.IN_APP,
                templateCode = "RESERVATION_OFFER_CREATED",
                aggregateId = offer.id.toString()
            ),
            now
        )
        log.debug { "reservation_capacity_handed_off resourceId=$resourceId offerId=${offer.id}" }
        return offer
    }
}
