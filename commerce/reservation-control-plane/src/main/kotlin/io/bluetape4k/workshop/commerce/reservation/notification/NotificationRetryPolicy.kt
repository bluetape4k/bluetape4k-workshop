package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.time.Duration

/**
 * deterministic fixture에 jitter를 추가하지 않고 capped exponential retry delay를 계산합니다.
 * @property maxAttempts 처리량·할당량·크기 또는 진척도입니다. 음수와 상한을 검증하고 경쟁 요청에서 원자적으로 갱신합니다.
 * @property initialDelay 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 * @property maxDelay 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
 */
internal class NotificationRetryPolicy(
    /**
     * `maxAttempts`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val maxAttempts: Int = 5,
    /**
     * `initialDelay`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val initialDelay: Duration = 1.seconds(),
    /**
     * `maxDelay`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val maxDelay: Duration = 1.minutes(),
) {
    private companion object: KLogging() {
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val MAX_SHIFT = 30
    }

    init {
        maxAttempts.requirePositiveNumber("maxAttempts")
        initialDelay.requirePositiveDuration("initialDelay")
        maxDelay.requirePositiveDuration("maxDelay")
        require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
    }

    /**
     * delayAfter은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param failedAttempt 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun delayAfter(failedAttempt: Int): Duration {
        failedAttempt.requirePositiveNumber("failedAttempt")
        val multiplier = 1L shl (failedAttempt - 1).coerceAtMost(MAX_SHIFT)
        val candidate = initialDelay.multipliedBy(multiplier)

        return minOf(candidate, maxDelay)
            .also { delay ->
                log.debug { "notification_retry_delay_calculated attempt=$failedAttempt delayMillis=${delay.toMillis()}" }
            }
    }
}
