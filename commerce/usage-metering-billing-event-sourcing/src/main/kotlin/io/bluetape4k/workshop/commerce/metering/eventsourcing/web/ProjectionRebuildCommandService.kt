package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionRebuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Clock
/**
 * ProjectionRebuildCommandService은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property rebuilder 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property receipts 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class ProjectionRebuildCommandService(
    /**
     * `rebuilder`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuilder: ProjectionRebuilder,
    /**
     * `receipts`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val receipts: CommandReceiptService,
    /**
     * `mapper`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val mapper: ObjectMapper,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val OPERATOR_SCOPE = "operator-control"
    }

    /**
     * begin은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param key 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param actorId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    fun begin(projectionName: String, key: String, actorId: String): ResponseEntity<Any> {
        val scope = CommandScope(OPERATOR_SCOPE, "projection-rebuild:$projectionName", CommandFingerprint.key(key))
        val fingerprint = CommandFingerprint.request("projection-rebuild", mapOf("projection" to projectionName))

        return when (val acquired = receipts.acquire(scope, fingerprint, clock.instant())) {
            is CommandAcquireResult.Owned  -> beginOwned(projectionName, actorId, acquired)
            is CommandAcquireResult.Replay -> ResponseEntity.status(acquired.httpStatus)
                .header("Idempotency-Replayed", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(acquired.response)
            is CommandAcquireResult.InProgress -> ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", acquired.retryAfter.seconds.coerceAtLeast(1).toString())
                .body(ApiError("command_in_progress", "command_in_progress"))
            CommandAcquireResult.Conflict  -> ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError("idempotency_conflict", "idempotency_conflict"))
        }
    }

    /**
     * beginOwned은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param actorId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param owned 업무 처리에 필요한 owned 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun beginOwned(
        projectionName: String,
        actorId: String,
        owned: CommandAcquireResult.Owned,
    ): ResponseEntity<Any> {
        receipts.requireOwnership(owned, clock.instant())
        val generation = rebuilder.beginNext(projectionName, clock.instant())
        val response = ProjectionStatusResponse(
            name = projectionName,
            generation = generation.generation,
            state = generation.state.name,
            checkpoint = generation.checkpoint,
            highWatermark = generation.highWatermark,
            lag = (generation.highWatermark - generation.checkpoint).coerceAtLeast(0),
            quarantined = false,
            failedPosition = null,
            failureAttempts = null,
            actorId = actorId,
        )
        val completed = receipts.succeed(
            owned = owned,
            httpStatus = HttpStatus.ACCEPTED.value(),
            response = mapper.writeValueAsString(response),
            now = clock.instant(),
        )
        check(completed) { "command_receipt_owner_lost" }

        return ResponseEntity.accepted().body(response)
    }

}
