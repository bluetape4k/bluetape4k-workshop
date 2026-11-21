package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireInRange
import io.bluetape4k.workshop.commerce.reservation.application.AcceptOfferCommand
import io.bluetape4k.workshop.commerce.reservation.application.CancelWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentCommandResult
import io.bluetape4k.workshop.commerce.reservation.application.IdempotentReservationCommandService
import io.bluetape4k.workshop.commerce.reservation.application.JoinWaitlistCommand
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCommandExecutionGate
import io.bluetape4k.workshop.commerce.reservation.application.WaitlistCommandService
import io.bluetape4k.workshop.commerce.reservation.persistence.ReservationOfferRecord
import io.bluetape4k.workshop.commerce.reservation.persistence.WaitlistEntryRecord
import io.bluetape4k.workshop.commerce.reservation.query.WaitlistQueryService
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.time.Instant

/**
 * replay 가능한 idempotent response를 가진 owner-scoped waitlist 및 offer command용 HTTP boundary입니다.
 * @property commands 좌석 예약·대기열 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property queries 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property executionGate 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property idempotency 좌석 예약·대기열 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api")
internal class WaitlistController(
    /**
     * `commands`은 예약 제어면에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: WaitlistCommandService,
    /**
     * `queries`은 예약 제어면에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: WaitlistQueryService,
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
        private const val JOIN_WAITLIST = "JOIN_WAITLIST"
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private const val CANCEL_WAITLIST = "CANCEL_WAITLIST"
        /**
         * 좌석 예약·대기열 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val ACCEPT_OFFER = "ACCEPT_OFFER"
    }

    /**
     * `join`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = join(resourceId = 1L, owner = "demo", key = "demo-idempotency-1", request =
     * httpRequest)
     * ```
     * @param resourceId 요청 경계에서 검증된 `resourceId`입니다.
     * @param owner 요청 경계에서 검증된 `owner`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/resources/{resourceId}/waitlist")
    fun join(
        @PathVariable resourceId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: JoinWaitlistRequest,
    ): ResponseEntity<WaitlistResponse> {
        validateCommandHeaders(owner, key)
        val result = executionGate.execute(JOIN_WAITLIST, key) {
            idempotency.execute(
                JOIN_WAITLIST,
                key,
                owner,
                "resourceId=$resourceId\nexpectedRevision=${request.expectedResourceRevision}\n" +
                        "policyVersion=${request.policyVersion}",
                HttpStatus.CREATED.value(),
                WaitlistResponse::class.java
            ) {
                commands
                    .join(
                        JoinWaitlistCommand(
                            resourceId = resourceId,
                            ownerToken = owner,
                            expectedResourceRevision = request.expectedResourceRevision,
                            policyVersion = request.policyVersion
                        )
                    )
                    .toResponse(position = 0, offer = null)
            }
        }
        log.debug { "waitlist_http_join_applied entryId=${result.value.id} resourceId=$resourceId" }

        return result.toResponseEntity()
    }

    /**
     * `entry`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = entry(entryId = 1L, owner = "demo")
     * ```
     * @param entryId 요청 경계에서 검증된 `entryId`입니다.
     * @param owner 요청 경계에서 검증된 `owner`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/waitlist/{entryId}")
    fun entry(
        @PathVariable entryId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
    ): WaitlistResponse {
        owner.length.requireGe(32) { "owner credential must contain at least 256 bits of encoded entropy" }

        val snapshot = queries.entry(entryId, owner)
        return snapshot.entry.toResponse(snapshot.position, snapshot.activeOffer)
    }

    /**
     * `cancel`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = cancel(entryId = 1L, owner = "demo", key = "demo-idempotency-1", request =
     * httpRequest)
     * ```
     * @param entryId 요청 경계에서 검증된 `entryId`입니다.
     * @param owner 요청 경계에서 검증된 `owner`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/waitlist/{entryId}/cancel")
    fun cancel(
        @PathVariable entryId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RevisionRequest,
    ): ResponseEntity<WaitlistResponse> {
        validateCommandHeaders(owner, key)
        val result = executionGate.execute(CANCEL_WAITLIST, key) {
            idempotency.execute(
                operation = CANCEL_WAITLIST,
                rawKey = key,
                rawOwner = owner,
                canonicalPayload = "entryId=$entryId\nexpectedRevision=${request.expectedRevision}",
                successStatus = HttpStatus.OK.value(),
                bodyType = WaitlistResponse::class.java
            ) {
                commands
                    .cancel(
                        CancelWaitlistCommand(
                            entryId = entryId,
                            expectedRevision = request.expectedRevision,
                            ownerToken = owner
                        )
                    )
                    .toResponse(0, null)
            }
        }
        return result.toResponseEntity()
    }

    /**
     * `offer`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = offer(offerId = 1L, owner = "demo")
     * ```
     * @param offerId 요청 경계에서 검증된 `offerId`입니다.
     * @param owner 요청 경계에서 검증된 `owner`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/offers/{offerId}")
    fun offer(
        @PathVariable offerId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
    ): OfferResponse {
        owner.length.requireGe(32) { "owner credential must contain at least 256 bits of encoded entropy" }
        return queries.offer(offerId, owner).toResponse()
    }

    /**
     * `accept`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = accept(offerId = 1L, owner = "demo", key = "demo-idempotency-1", request =
     * httpRequest)
     * ```
     * @param offerId 요청 경계에서 검증된 `offerId`입니다.
     * @param owner 요청 경계에서 검증된 `owner`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/offers/{offerId}/accept")
    fun accept(
        @PathVariable offerId: Long,
        @RequestHeader("X-Reservation-Owner") owner: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RevisionRequest,
    ): ResponseEntity<OfferResponse> {
        validateCommandHeaders(owner, key)
        val result = executionGate.execute(ACCEPT_OFFER, key) {
            idempotency.execute(
                operation = ACCEPT_OFFER,
                rawKey = key,
                rawOwner = owner,
                canonicalPayload = "offerId=$offerId\nexpectedRevision=${request.expectedRevision}",
                successStatus = HttpStatus.OK.value(),
                bodyType = OfferResponse::class.java
            ) {
                commands
                    .accept(AcceptOfferCommand(offerId, request.expectedRevision, owner))
                    .offer
                    .toResponse()
            }
        }
        return result.toResponseEntity()
    }

    /**
     * validateCommandHeaders은 예약 제어면 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을
     * 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param owner 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param key 처리할 예약 제어면 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun validateCommandHeaders(
        owner: String,
        key: String,
    ) {
        owner.length.requireGe(32) { "owner credential must contain at least 256 bits of encoded entropy" }
        key.length.requireInRange(16, 200) { "idempotency key must contain 16..200 characters" }
    }
}
/**
 * JoinWaitlistRequest은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property expectedResourceRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를
 * 거부합니다.
 * @property policyVersion 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class JoinWaitlistRequest(
    @field:Min(0) val expectedResourceRevision: Long,
    @field:Min(1) val policyVersion: Long,
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
 * RevisionRequest은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property expectedRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class RevisionRequest(
    @field:Min(0) val expectedRevision: Long,
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
 * WaitlistResponse은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property sequence 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property position 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property offerId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property offerRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property offerExpiresAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class WaitlistResponse(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
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
     * `sequence`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val sequence: Long,
    /**
     * `revision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val revision: Long,
    /**
     * `position`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val position: Int,
    /**
     * `offerId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val offerId: Long? = null,
    /**
     * `offerRevision`은 예약 제어면에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val offerRevision: Long? = null,
    /**
     * `offerExpiresAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val offerExpiresAt: Instant? = null,
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
 * OfferResponse은 좌석 예약·대기열에서 전달되는 불변 데이터 모델입니다.
 * @property id 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property resourceId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property entryId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property state 현재 좌석 예약·대기열 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property revision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property expiresAt 좌석 예약·대기열의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock 정책을
 * 유지합니다.
 */


internal data class OfferResponse(
    /**
     * `id`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val id: Long,
    /**
     * `resourceId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val resourceId: Long,
    /**
     * `entryId`은 예약 제어면에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val entryId: Long,
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
    /**
     * `expiresAt`은 예약 제어면에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val expiresAt: Instant,
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
 * toResponse은 예약 제어면의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param position 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param offer 업무 처리에 필요한 offer 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun WaitlistEntryRecord.toResponse(
    position: Int,
    offer: ReservationOfferRecord?,
): WaitlistResponse =
    WaitlistResponse(
        id = id,
        resourceId = resourceId,
        state = state.name,
        sequence = sequence,
        revision = revision,
        position = position,
        offerId = offer?.id,
        offerRevision = offer?.revision,
        offerExpiresAt = offer?.expiresAt
    )

/**
 * toResponse은 예약 제어면의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ReservationOfferRecord.toResponse() =
    OfferResponse(
        id = id,
        resourceId = resourceId,
        entryId = entryId,
        state = state.name,
        revision = revision,
        expiresAt = expiresAt
    )

/**
 * toResponseEntity은 예약 제어면의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun <T: Any> IdempotentCommandResult<T>.toResponseEntity(): ResponseEntity<T> =
    ResponseEntity
        .status(status)
        .header("Idempotency-Replayed", replayed.toString())
        .body(value)
