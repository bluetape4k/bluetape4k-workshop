package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 설정한 횟수만큼 실패하고 각 delivery를 한 번만 적용하는 결정적 fake provider입니다.
 * 외부 delivery service를 도입하지 않고 provider retry와 deduplication semantic을 모델링합니다.
 * @property failFirstAttempts 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 */
internal class FailFirstDeduplicatingNotificationProvider(
    /**
     * `failFirstAttempts`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val failFirstAttempts: Int = 1,
) {
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val lock = ReentrantLock()
    /**
     * `attempts`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val attempts = mutableMapOf<String, Int>()
    /**
     * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    private val accepted = mutableSetOf<String>()

    init {
        failFirstAttempts.requireZeroOrPositiveNumber("failFirstAttempts")
    }

    /**
     * send은 예약 제어면 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param delivery 업무 처리에 필요한 delivery 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun send(delivery: NotificationDelivery): ProviderSendResult =
        lock.withLock {
            if (delivery.deliveryId in accepted) {
                log.debug { "fake_notification_deduplicated deliveryId=${delivery.deliveryId}" }
                return ProviderSendResult.Duplicate
            }

            /**
             * `attempt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
             *
             * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
             */
            val attempt = attempts.getOrDefault(delivery.deliveryId, 0) + 1
            attempts[delivery.deliveryId] = attempt

            if (attempt <= failFirstAttempts) {
                log.warn {
                    "fake_notification_failed deliveryId=${delivery.deliveryId} attempt=$attempt code=$TRANSIENT_CODE"
                }
                return ProviderSendResult.RetryableFailure(NotificationFailureCode.FAKE_TRANSIENT)
            }

            accepted += delivery.deliveryId
            log.debug { "fake_notification_accepted deliveryId=${delivery.deliveryId} attempt=$attempt" }
            ProviderSendResult.Accepted
        }

    /**
     * effectCount은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param deliveryId 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun effectCount(deliveryId: String): Int =
        lock.withLock {
            if (deliveryId in accepted) 1 else 0
        }

    private companion object: KLogging() {
        /**
         * 좌석 예약·대기열의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val TRANSIENT_CODE = NotificationFailureCode.FAKE_TRANSIENT
    }
}
