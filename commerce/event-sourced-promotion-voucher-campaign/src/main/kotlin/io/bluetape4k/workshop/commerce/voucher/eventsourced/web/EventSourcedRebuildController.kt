package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.EventSourcedRebuildManagementService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildGenerationRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildManagementResult
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.StartRebuildRequest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionGeneration
import io.bluetape4k.workshop.commerce.voucher.eventsourced.projection.ProjectionKey
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
/**
 * StartRebuildHttpRequest은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property targetPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 */


internal data class StartRebuildHttpRequest(
    @field:Min(0) val targetPosition: Long,
)
/**
 * RebuildHttpResponse은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property projection 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property state 현재 프로모션 바우처 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property targetPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property currentPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property fencingToken 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property cancellationRevision 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property retryableFailure 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property replayed 프로모션 바우처 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 */


internal data class RebuildHttpResponse(
    /**
     * `projection`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val projection: String,
    /**
     * `generation`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Long,
    /**
     * `state`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `targetPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val targetPosition: Long,
    /**
     * `currentPosition`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val currentPosition: Long,
    /**
     * `fencingToken`은 바우처·프로모션에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fencingToken: Long,
    /**
     * `cancellationRevision`은 바우처·프로모션에서 업무 규칙의 허용 여부를 나타내는 flag입니다.
     *
     * true와 false 각각의 분기를 명시적으로 처리하고 기본값이 안전한 거부인지 확인합니다.
     */
    val cancellationRevision: Long,
    /**
     * `retryableFailure`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryableFailure: Boolean,
    /**
     * `replayed`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
)
/**
 * EventSourcedRebuildController은 프로모션 바우처에서 사용하는 업무 객체입니다.
 * @property service 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/operator/api/v1/projections/{projection}/rebuilds")
internal class EventSourcedRebuildController(
    /**
     * `service`은 바우처·프로모션에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: EventSourcedRebuildManagementService,
) {

    companion object: KLogging()

    /**
     * `start`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = start(projection = "demo", headers = headers, body = body)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @param body 요청 경계에서 검증된 `body`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping
    fun start(
        @PathVariable projection: String,
        @RequestHeader headers: HttpHeaders,
        @Valid @RequestBody body: StartRebuildHttpRequest,
    ): ResponseEntity<Any> =
        service
            .start(
                request = StartRebuildRequest(
                    identity = headers.operatorIdentity(),
                    projection = projection,
                    targetPosition = body.targetPosition,
                    expectedToken = headers.expectedGenerationToken(),
                ),
            )
            .toResponse(HttpStatus.ACCEPTED)

    /**
     * `status`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = status(projection = "demo", generation = 1L, headers = headers)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/{generation}")
    fun status(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service
            .status(headers.generationRequest(projection, generation))
            .toResponse(HttpStatus.OK)

    /**
     * `cancel`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = cancel(projection = "demo", generation = 1L, headers = headers)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/{generation}/cancel")
    fun cancel(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service
            .cancel(headers.generationRequest(projection, generation))
            .toResponse(HttpStatus.OK)

    /**
     * `resume`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = resume(projection = "demo", generation = 1L, headers = headers)
     * ```
     * @param projection 요청 경계에서 검증된 `projection`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @param headers 요청 경계에서 검증된 `headers`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/{generation}/resume")
    fun resume(
        @PathVariable projection: String,
        @PathVariable generation: Long,
        @RequestHeader headers: HttpHeaders,
    ): ResponseEntity<Any> =
        service
            .resume(headers.generationRequest(projection, generation))
            .toResponse(HttpStatus.OK)
}

/**
 * generationRequest은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
 * 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param projection 업무 처리에 필요한 projection 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun HttpHeaders.generationRequest(
    projection: String,
    generation: Long,
): RebuildGenerationRequest =
    RebuildGenerationRequest(
        identity = operatorIdentity(),
        key = ProjectionKey(projection, generation),
        expectedToken = expectedGenerationToken(),
    )

/**
 * toResponse은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param successStatus 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 */
private fun RebuildManagementResult.toResponse(successStatus: HttpStatus): ResponseEntity<Any> =
    when (this) {
        is RebuildManagementResult.Accepted ->
            ResponseEntity.status(successStatus)
                .header("X-Idempotent-Replay", replayed.toString())
                .body(generation.toHttp(replayed))

        is RebuildManagementResult.StaleToken ->
            ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .header(EXPECTED_GENERATION_TOKEN_HEADER, currentToken.toString())
                .body(EventSourcedApiError("STALE_GENERATION_TOKEN", "generation token is stale"))

        is RebuildManagementResult.Conflict ->
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(EventSourcedApiError(reason, "rebuild conflicts with current state"))

        RebuildManagementResult.NotFound    -> ResponseEntity.notFound().build()
    }

/**
 * toHttp은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun ProjectionGeneration.toHttp(replayed: Boolean): RebuildHttpResponse =
    RebuildHttpResponse(
        projection = key.projection,
        generation = key.generation,
        state = state.name,
        targetPosition = targetPosition,
        currentPosition = currentPosition,
        fencingToken = fencingToken,
        cancellationRevision = cancellationRevision,
        retryableFailure = retryableFailure,
        replayed = replayed,
    )
