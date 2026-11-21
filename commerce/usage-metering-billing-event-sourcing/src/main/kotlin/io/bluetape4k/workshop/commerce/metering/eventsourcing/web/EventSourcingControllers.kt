package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterCommandService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.MeterRegistration
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationQuery
import io.bluetape4k.workshop.commerce.metering.eventsourcing.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.config.EventSourcingMetrics
import io.bluetape4k.workshop.commerce.metering.eventsourcing.domain.EventMetadata
import io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore.OptimisticConcurrencyException
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.eventsourcing.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.BillingReadModelRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionFailureRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.persistence.ProjectionGenerationRepository
import io.bluetape4k.workshop.commerce.metering.eventsourcing.projection.ProjectionGeneration
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.LockSupport
/**
 * RegisterMeterRequest은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property code 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property unit 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


data class RegisterMeterRequest(@field:NotBlank val code: String, @field:NotBlank val unit: String)
/**
 * MeterResponse은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property code 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property unit 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */

data class MeterResponse(val code: String, val unit: String)
/**
 * ApiError은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property code 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property message 사용량 계량·청구 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */

data class ApiError(val code: String, val message: String)
/**
 * BillingSummary은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property financialTotal 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 */

data class BillingSummary(val tenantId: String, val generation: Int, val financialTotal: String)
/**
 * ProjectionStatusResponse은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property name 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property generation 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property state 현재 사용량 계량·청구 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지 않습니다.
 * @property checkpoint 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property highWatermark 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property lag 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property quarantined 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property failedPosition 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값과 비교해 오래된 명령·중복 이벤트를 거부합니다.
 * @property failureAttempts 같은 업무를 재시도한 횟수 또는 다음 재시도까지의 대기 정책입니다. 최대 시도 횟수와 terminal failure 규칙을
 * 함께 적용합니다.
 * @property actorId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 */

data class ProjectionStatusResponse(
    /**
     * `name`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    val name: String,
    /**
     * `generation`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val generation: Int,
    /**
     * `state`은 사용량 과금·정산에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val state: String,
    /**
     * `checkpoint`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val checkpoint: Long,
    /**
     * `highWatermark`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val highWatermark: Long,
    /**
     * `lag`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lag: Long,
    /**
     * `quarantined`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val quarantined: Boolean,
    /**
     * `failedPosition`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    val failedPosition: Long?,
    /**
     * `failureAttempts`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val failureAttempts: Int?,
    /**
     * `actorId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val actorId: String? = null,
)

/**
 * ProjectionNotCaughtUpException은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class ProjectionNotCaughtUpException: IllegalStateException("projection_not_caught_up")
/**
 * ProjectionConsistencyWaiter은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 */


@Component
class ProjectionConsistencyWaiter(
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
) {
    /**
     * await은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param projectionName 업무 처리에 필요한 projectionName 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param targetPosition 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param timeout 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun await(projectionName: String, targetPosition: Long, timeout: Duration = MAX_WAIT) {
        require(targetPosition >= 0) { "projection_position_invalid" }
        val deadline = System.nanoTime() + timeout.toNanos()
        while ((generations.active(projectionName)?.checkpoint ?: -1) < targetPosition) {
            if (System.nanoTime() >= deadline) throw ProjectionNotCaughtUpException()
            LockSupport.parkNanos(POLL_INTERVAL.toNanos())
        }
    }

    private companion object {
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val POLL_INTERVAL: Duration = 5.millis()
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        val MAX_WAIT: Duration = 100.millis()
    }
}
/**
 * EventSourcingCommandController은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property meters 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property receipts 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property mapper 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property clock 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class EventSourcingCommandController(
    /**
     * `meters`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val meters: MeterCommandService,
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
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: EventSourcingMetrics,
    /**
     * `clock`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    private companion object: KLogging() {
        /**
         * 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
         */
        const val DEFAULT_CURRENCY = "USD"
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val APPEND_SUCCESS = "success"
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val APPEND_CONFLICT = "conflict"
    }

    /**
     * `registerMeter`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = registerMeter(tenantId = "tenant-a", key = "demo-idempotency-1", request =
     * httpRequest, authentication = authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/meters")
    @Transactional
    fun registerMeter(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @Valid @RequestBody request: RegisterMeterRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        requireTenant(authentication, tenantId)
        val scope = CommandScope(tenantId, "meter-register", CommandFingerprint.key(key))
        val fingerprint = CommandFingerprint.request(
            "meter-register",
            mapOf("code" to request.code, "unit" to request.unit),
        )

        return when (val acquired = receipts.acquire(scope, fingerprint, clock.instant())) {
            is CommandAcquireResult.Owned  -> registerOwned(tenantId, request, acquired, authentication.name)
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
     * registerOwned은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     * @param request 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param owned 업무 처리에 필요한 owned 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param actorId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun registerOwned(
        tenantId: String,
        request: RegisterMeterRequest,
        owned: CommandAcquireResult.Owned,
        actorId: String,
    ): ResponseEntity<Any> {
        receipts.requireOwnership(owned, clock.instant())
        val startedAt = System.nanoTime()
        try {
            val commandId = owned.receiptId.toString()
            meters.register(
                MeterRegistration(
                    tenantId,
                    request.code,
                    request.unit,
                    DEFAULT_CURRENCY,
                    clock.instant(),
                    EventMetadata(commandId, commandId, commandId, actorId),
                )
            )
            metrics.recordAppend(APPEND_SUCCESS, (System.nanoTime() - startedAt).nanos())
        } catch (failure: OptimisticConcurrencyException) {
            metrics.recordAppend(APPEND_CONFLICT, (System.nanoTime() - startedAt).nanos())
            throw failure
        }
        val response = MeterResponse(request.code, request.unit)

        val receiptCompleted = receipts.succeed(
            owned = owned,
            httpStatus = HttpStatus.CREATED.value(),
            response = mapper.writeValueAsString(response),
            now = clock.instant(),
        )
        check(receiptCompleted) { "command_receipt_owner_lost" }
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }
}
/**
 * EventSourcingQueryController은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property readModels 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
 * @property consistency 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class EventSourcingQueryController(
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `readModels`은 사용량 과금·정산에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    private val readModels: BillingReadModelRepository,
    /**
     * `consistency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val consistency: ProjectionConsistencyWaiter,
) {
    /**
     * `summary`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = summary(tenantId = "tenant-a", waitForPosition = 1L, authentication =
     * authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param waitForPosition 요청 경계에서 검증된 `waitForPosition`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/billing/summary")
    @Transactional(readOnly = true)
    fun summary(
        @PathVariable tenantId: String,
        @RequestHeader("X-Wait-For-Position", required = false) waitForPosition: Long?,
        authentication: Authentication,
    ): ResponseEntity<BillingSummary> {
        requireTenant(authentication, tenantId)
        waitForPosition?.let { consistency.await(BILLING_PROJECTION, it) }

        /**
         * `active`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val active = checkNotNull(generations.active(BILLING_PROJECTION)) { "active_projection_missing" }
        /**
         * `total`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val total = readModels.financialTotal(BILLING_PROJECTION, active.generation, tenantId)
        /**
         * `lag`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val lag = (active.highWatermark - active.checkpoint).coerceAtLeast(0)

        return ResponseEntity.ok()
            .header("Projection-Position", active.checkpoint.toString())
            .header("Projection-Lag", lag.toString())
            .body(BillingSummary(tenantId, active.generation, total.toPlainString()))
    }

    private companion object {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val BILLING_PROJECTION = "billing"
    }
}
/**
 * EventSourcingAdminController은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property generations 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
 * @property failures 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property rebuilds 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliation 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/admin/event-sourcing")
class EventSourcingAdminController(
    /**
     * `generations`은 사용량 과금·정산에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
     *
     * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
     */
    private val generations: ProjectionGenerationRepository,
    /**
     * `failures`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val failures: ProjectionFailureRepository,
    /**
     * `rebuilds`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val rebuilds: ProjectionRebuildCommandService,
    /**
     * `reconciliation`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliation: ReconciliationService,
) {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val BILLING_PROJECTION = "billing"
    }

    /**
     * `projection`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = projection(projectionName = "demo")
     * ```
     * @param projectionName 요청 경계에서 검증된 `projectionName`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/projections/{projectionName}")
    @Transactional(readOnly = true)
    fun projection(@PathVariable projectionName: String): ProjectionStatusResponse {
        val active = generations.active(projectionName).checkNotNull { "active_projection_missing" }
        return status(active)
    }

    /**
     * `generation`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = generation(projectionName = "demo", generation = 25)
     * ```
     * @param projectionName 요청 경계에서 검증된 `projectionName`입니다.
     * @param generation 요청 경계에서 검증된 `generation`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/projections/{projectionName}/generations/{generation}")
    @Transactional(readOnly = true)
    fun generation(
        @PathVariable projectionName: String,
        @PathVariable generation: Int,
    ): ProjectionStatusResponse =
        status(
            generations.get(projectionName, generation).checkNotNull { "projection_generation_missing" },
    )

    /**
     * `rebuild`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = rebuild(projectionName = "demo", key = "demo-idempotency-1", authentication =
     * authentication)
     * ```
     * @param projectionName 요청 경계에서 검증된 `projectionName`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/projections/{projectionName}/rebuilds")
    @Transactional
    fun rebuild(
        @PathVariable projectionName: String,
        @RequestHeader("Idempotency-Key") key: String,
        authentication: Authentication,
    ): ResponseEntity<Any> =
        rebuilds.begin(projectionName, key, authentication.name)

    /**
     * `reconcile`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reconcile(tenantId = "tenant-a", startsAt = startsAt, endsAt = endsAt)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param startsAt 요청 경계에서 검증된 `startsAt`입니다.
     * @param endsAt 요청 경계에서 검증된 `endsAt`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/reconciliation")
    @Transactional(readOnly = true)
    fun reconcile(
        @RequestParam tenantId: String,
        @RequestParam startsAt: Instant,
        @RequestParam endsAt: Instant,
    ): ResponseEntity<Any> {
        val finding = reconciliation.inspect(ReconciliationQuery(tenantId, BILLING_PROJECTION, startsAt, endsAt))
        return finding?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    /**
     * status은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param generation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param actorId 처리할 사용량 계량·청구 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    private fun status(generation: ProjectionGeneration, actorId: String? = null): ProjectionStatusResponse {
        val failure = failures.latest(generation.projectionName, generation.generation)
        return ProjectionStatusResponse(
            name = generation.projectionName,
            generation = generation.generation,
            state = generation.state.name,
            checkpoint = generation.checkpoint,
            highWatermark = generation.highWatermark,
            lag = (generation.highWatermark - generation.checkpoint).coerceAtLeast(0),
            quarantined = failure != null,
            failedPosition = failure?.globalPosition,
            failureAttempts = failure?.attemptCount,
            actorId = actorId,
        )
    }
}

@RestControllerAdvice
/**
 * EventSourcingErrorAdvice은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class EventSourcingErrorAdvice {

    private companion object: KLogging() {
        /**
         * 사용량 계량·청구의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        val STABLE_ERROR_CODE = Regex("[a-z][a-z0-9_]*")
    }

    /**
     * `projectionNotCaughtUp`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = projectionNotCaughtUp()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(ProjectionNotCaughtUpException::class)
    fun projectionNotCaughtUp(): ResponseEntity<ApiError> = ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiError("projection_not_caught_up", "projection_not_caught_up"))

    /**
     * `missingHeader`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예: `val response = missingHeader()`처럼 검증된 입력 경계 뒤에서 호출합니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError("idempotency_key_required", "idempotency_key_required"))

    /**
     * `invalid`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = invalid(failure = currentValidationException)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(failure: IllegalArgumentException): ResponseEntity<ApiError> {
        val code = stableCode(failure.message, "invalid_request")
        return ResponseEntity.badRequest().body(ApiError(code, code))
    }

    /**
     * `conflict`은 예외·입력 실패를 외부 API의 안정적인 오류 응답으로 변환하는 경계 메서드입니다.
     *
     * 예외의 분류와 HTTP status를 임의로 바꾸지 말고, 호출자가 수정·재시도·운영자 대사를 선택할 수 있도록 redacted detail과 correlation
     * 정보를 유지합니다.
     * 매핑 경계: `ExceptionHandler`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = conflict(failure = currentConflictException)
     * ```
     * @param failure 요청 경계에서 검증된 `failure`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @ExceptionHandler(IllegalStateException::class)
    fun conflict(failure: IllegalStateException): ResponseEntity<ApiError> {
        val code = stableCode(failure.message, "conflict")
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError(code, code))
    }

    /**
     * stableCode은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param message 사용량 계량·청구 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param fallback 업무 처리에 필요한 fallback 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * 예: 빈 provider message는 `provider_error` 같은 안정적인 code로 대체해 운영 검색과 재시도를 가능하게 합니다.
     */
    private fun stableCode(message: String?, fallback: String): String = message
        ?.substringBefore(':')
        ?.takeIf { it.matches(STABLE_ERROR_CODE) }
        ?: fallback
}

/**
 * requireTenant은 사용량 계량·청구 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param authentication 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
 * @param tenantId 사용량 계량·청구 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
 */
private fun requireTenant(authentication: Authentication, tenantId: String) {
    require(authentication.name == tenantId) { "tenant_mismatch" }
}
