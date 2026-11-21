package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.commerce.reservation.application.ForceReleaseHoldCommand
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.sweeper.PostgresReservationSweepWork
import io.bluetape4k.workshop.commerce.reservation.sweeper.SweepBatchSummary
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable

/**
 * force release와 bounded manual sweep command를 위한 authenticated operator boundary입니다.
 * force release는 user command처럼 revision-checked, audited, idempotent 상태로 유지됩니다.
 * @property commands 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property sweepWork 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executionGate 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api/operator")
@ConditionalOnProperty(prefix = "reservation.operator", name = ["enabled"], havingValue = "true")
internal class OperatorController(
    /**
     * `commands`은 예약 제어면에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: ReservationCommandService,
    /**
     * `sweepWork`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val sweepWork: PostgresReservationSweepWork,
    /**
     * `executionGate`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val executionGate: ReservationCommandExecutionGate,
    /**
     * `idempotency`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: IdempotentReservationCommandService,
) {

    companion object: KLogging() {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private const val FORCE_RELEASE = "OPERATOR_FORCE_RELEASE"
    }

    /**
     * `forceRelease`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = forceRelease(holdId = 1L, operatorKey = "demo-idempotency-1", key =
     * "demo-idempotency-1", request = httpRequest)
     * ```
     * @param holdId 요청 경계에서 검증된 `holdId`입니다.
     * @param operatorKey 요청 경계에서 검증된 `operatorKey`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/holds/{holdId}/force-release")
    fun forceRelease(
        @PathVariable holdId: Long,
        @RequestHeader("X-Operator-Key") operatorKey: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: ForceReleaseRequest,
    ): ResponseEntity<OperatorReleaseResponse> {
        val result = executionGate.execute(FORCE_RELEASE, key) {
            idempotency.execute(
                operation = FORCE_RELEASE,
                rawKey = key,
                rawOwner = operatorKey,
                canonicalPayload = "holdId=$holdId\nexpectedRevision=${request.expectedRevision}\nreasonCode=${request.reasonCode}",
                successStatus = HttpStatus.OK.value(),
                bodyType = OperatorReleaseResponse::class.java
            ) {
                commands
                    .forceRelease(ForceReleaseHoldCommand(holdId, request.expectedRevision, request.reasonCode))
                    .let {
                        OperatorReleaseResponse(it.id, it.resourceId, it.state.name, it.revision)
                    }
            }
        }
        log.info { "reservation_operator_force_release_completed holdId=$holdId replayed=${result.replayed}" }

        return ResponseEntity
            .status(result.status)
            .header("Idempotency-Replayed", result.replayed.toString())
            .body(result.value)
    }

    /**
     * `sweep`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = sweep(request = httpRequest)
     * ```
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/sweep")
    fun sweep(
        @Valid @RequestBody request: ManualSweepRequest,
    ): SweepBatchSummary =
        sweepWork.sweep(request.maxResources, 5.seconds())
            .also { summary ->
                log.info {
                    "reservation_operator_sweep_completed scanned=${summary.scannedResources} " +
                            "expired=${summary.expiredHolds} promoted=${summary.promotedEntries}"
                }
            }

}
/**
 * ForceReleaseRequest은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property reasonCode 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 */


internal data class ForceReleaseRequest(
    @field:Min(0) val expectedRevision: Long,
    @field:Pattern(regexp = "[A-Z0-9_]{3,40}") val reasonCode: String,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
/**
 * ManualSweepRequest은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property maxResources 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class ManualSweepRequest(
    @field:Min(1) @field:Max(32) val maxResources: Int = 32,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
/**
 * OperatorReleaseResponse은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property holdId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class OperatorReleaseResponse(
    /**
     * `holdId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val holdId: Long,
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `state`은 예약 제어면에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 예약 제어면에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID = 1L
    }
}
