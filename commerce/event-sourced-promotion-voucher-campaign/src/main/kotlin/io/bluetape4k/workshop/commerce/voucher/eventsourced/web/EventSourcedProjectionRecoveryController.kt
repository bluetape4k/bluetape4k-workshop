package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.PoisonRetryRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.PoisonRetryResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionReconciliationRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionReconciliationResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.ProjectionRecoveryManagementService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.*
/**
 * PoisonRetryHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property attempts 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을 함께 적용합니다.
 * @property checkpointPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class PoisonRetryHttpResponse(
    /**
     * `eventId`은 바우처·프로모션에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `attempts`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val attempts: Int,
    /**
     * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpointPosition: Long,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
)
/**
 * ProjectionReconciliationHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property streamPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property checkpointPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property lag 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failedPoisonCount 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야
 * 합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class ProjectionReconciliationHttpResponse(
    /**
     * `streamPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val streamPosition: Long,
    /**
     * `checkpointPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpointPosition: Long,
    /**
     * `lag`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lag: Long,
    /**
     * `failedPoisonCount`은 바우처·프로모션에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val failedPoisonCount: Long,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
)
/**
 * EventSourcedProjectionRecoveryController은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property service 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/operator/api/v1/projections/{projection}/generations/{generation}")
internal class EventSourcedProjectionRecoveryController(
    /**
     * `service`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: ProjectionRecoveryManagementService,
) {

    companion object: KLogging()

    /**
     * `retryPoison`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = retryPoison(projection = "demo", generation = 1L, eventId = UUID.randomUUID(),
     * headers = headers)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @param eventId 요청 경계에서 검증된 `eventId`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/poison-events/{eventId}/retry")
    fun retryPoison(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @PathVariable eventId: UUID,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service
            .retryPoison(
                request = PoisonRetryRequest(
                    identity = headers.operatorIdentity(),
                    key = ProjectionKey(projection, generation),
                    eventId = eventId,
                    expectedToken = headers.expectedGenerationToken(),
                ),
            )
            .toResponse()

    /**
     * `reconcile`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reconcile(projection = "demo", generation = 1L, headers = headers)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reconciliation")
    fun reconcile(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service
            .reconcile(
                request = ProjectionReconciliationRequest(
                    identity = headers.operatorIdentity(),
                    key = ProjectionKey(projection, generation),
                    expectedToken = headers.expectedGenerationToken(),
                ),
            )
            .toResponse()
}

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun PoisonRetryResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is PoisonRetryResult.Resolved ->
            ResponseEntity.ok()
                .header("X-Idempotent-Replay", replayed.toString())
                .body(
                    PoisonRetryHttpResponse(
                        eventId = poison.eventId,
                        state = poison.state.name,
                        attempts = poison.attempts,
                        checkpointPosition = checkpointPosition,
                        replayed = replayed,
                    ),
                )
        is PoisonRetryResult.RetryLater ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, delay.retryAfterSeconds().toString())
                .body(EventSourcedApiError("POISON_RETRY_BACKOFF", "poison retry is not ready"))

        is PoisonRetryResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))

        is PoisonRetryResult.Conflict ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EventSourcedApiError(reason, "poison retry conflicts with current state"))

        PoisonRetryResult.NotFound    -> ResponseEntity.notFound().build()
    }

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ProjectionReconciliationResult.toResponse(): ResponseEntity<Any> =
    when (this) {
        is ProjectionReconciliationResult.Completed ->
            ResponseEntity.ok()
                .header("X-Idempotent-Replay", replayed.toString())
                .body(
                    ProjectionReconciliationHttpResponse(
                        streamPosition = snapshot.streamPosition,
                        checkpointPosition = snapshot.checkpointPosition,
                        lag = snapshot.lag,
                        failedPoisonCount = snapshot.failedPoisonCount,
                        replayed = replayed,
                    ),
                )
        is ProjectionReconciliationResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))

        ProjectionReconciliationResult.NotFound     -> ResponseEntity.notFound().build()
    }

/**
 * retryAfterSeconds은 프로모션 바우처의 실패·지연 데이터를 복구할 때 원본 revision과 중복 키를 확인하고, 재시도 가능한 항목만 안전하게 재처리합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun Duration.retryAfterSeconds(): Long =
    ((toMillis() + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1)

/**
 * `MILLIS_PER_SECOND`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val MILLIS_PER_SECOND = 1_000L
