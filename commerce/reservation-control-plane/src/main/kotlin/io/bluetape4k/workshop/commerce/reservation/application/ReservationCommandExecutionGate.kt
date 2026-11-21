package io.bluetape4k.workshop.commerce.reservation.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.reservation.redis.AdmissionOutcome
import io.bluetape4k.workshop.commerce.reservation.redis.InFlightCommandSuppressor
import io.bluetape4k.workshop.commerce.reservation.redis.ReservationAdmissionGate
import io.bluetape4k.workshop.commerce.reservation.redis.SuppressionOutcome

/**
 * PostgreSQL idempotency 전에 local/Redis admission과 짧은 duplicate suppression을 적용합니다.
 * 모든 durable command outcome은 여전히 PostgreSQL이 소유합니다.
 * @property admission 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property suppression 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property credentials 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property tenantId 좌석 예약·대기열 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리합니다.
 */
internal class ReservationCommandExecutionGate(
    /**
     * `admission`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val admission: ReservationAdmissionGate,
    /**
     * `suppression`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val suppression: InFlightCommandSuppressor,
    /**
     * `credentials`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val credentials: ReservationCredentialService,
    /**
     * `tenantId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    private val tenantId: String,
) {
    companion object: KLogging()

    /**
     * execute은 예약 제어면의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param rawIdempotencyKey 재전송·멱등성·무결성 확인에 사용하는 값입니다. 원문 비밀이나 개인정보를 로그에 남기지 않습니다.
     * @param action 업무 처리에 필요한 action 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun <T> execute(
        operation: String,
        rawIdempotencyKey: String,
        action: () -> T,
    ): T {
        val opaqueCommandId = credentials
            .idempotencyDigest(tenantId, operation, rawIdempotencyKey)
            .take(24)

        return when (
            val admissionOutcome =
                admission.execute {
                    when (val suppressionOutcome = suppression.execute(opaqueCommandId, action)) {
                        is SuppressionOutcome.Executed -> suppressionOutcome.value
                        SuppressionOutcome.Suppressed ->
                            throw ReservationCommandException(
                                reason = "COMMAND_IN_PROGRESS",
                                currentRevision = null,
                                retryable = true
                            )
                    }
                }
        ) {
            is AdmissionOutcome.Executed -> {
                admissionOutcome.value.also {
                    log.debug {
                        "reservation_command_gate_completed operation=$operation mode=${admissionOutcome.mode}"
                    }
                }
            }
            is AdmissionOutcome.Rejected -> {
                throw ReservationCommandException("ADMISSION_REJECTED", null, true)
            }
        }
    }


}
