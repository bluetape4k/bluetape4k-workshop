package io.bluetape4k.workshop.commerce.metering.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.workshop.commerce.metering.application.AdjustmentService
import io.bluetape4k.workshop.commerce.metering.application.BillingCloseService
import io.bluetape4k.workshop.commerce.metering.application.BillingPeriodService
import io.bluetape4k.workshop.commerce.metering.application.InvoiceService
import io.bluetape4k.workshop.commerce.metering.application.MeterService
import io.bluetape4k.workshop.commerce.metering.application.PriceActivationService
import io.bluetape4k.workshop.commerce.metering.application.PriceGapRepair
import io.bluetape4k.workshop.commerce.metering.application.ReconciliationService
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionCommand
import io.bluetape4k.workshop.commerce.metering.application.UsageIngestionService
import io.bluetape4k.workshop.commerce.metering.config.MeteringMetrics
import io.bluetape4k.workshop.commerce.metering.domain.IdempotencyKey
import io.bluetape4k.workshop.commerce.metering.domain.MeterCode
import io.bluetape4k.workshop.commerce.metering.domain.SourceEventId
import io.bluetape4k.workshop.commerce.metering.domain.SourceSystem
import io.bluetape4k.workshop.commerce.metering.domain.TenantId
import io.bluetape4k.workshop.commerce.metering.domain.UnitPrice
import io.bluetape4k.workshop.commerce.metering.domain.UsageQuantity
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandAcquireResult
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptScope
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptService
import io.bluetape4k.workshop.commerce.metering.idempotency.Sha256Digest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.*
/**
 * IdempotentHttpCommand은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property operation 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property rawKey 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 * @property fingerprint 무결성·인증·멱등성 검증에 사용하는 보안 자료입니다. 원문 비밀값을 로그나 일반 응답에 노출하지 않고 변조 여부를 확인합니다.
 */


data class IdempotentHttpCommand(
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `operation`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val operation: String,
    /**
     * `rawKey`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val rawKey: String,
    /**
     * `fingerprint`은 사용량 과금·정산에서 멱등성·무결성·lease fencing을 확인하는 보호 값입니다.
     *
     * 원문 비밀값을 로그·일반 응답에 노출하지 않고 재시도와 오래된 worker를 안전하게 차단합니다.
     */
    val fingerprint: Sha256Digest,
)
/**
 * IdempotentHttpCommandExecutor은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property receipts 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property objectMapper 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property metrics 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Component
class IdempotentHttpCommandExecutor(
    /**
     * `receipts`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val receipts: CommandReceiptService,
    /**
     * `objectMapper`은 사용량 과금·정산에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val objectMapper: ObjectMapper,
    /**
     * `metrics`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val metrics: MeteringMetrics,
) {
    /**
     * execute은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun execute(request: IdempotentHttpCommand, command: () -> Any): ResponseEntity<Any> {
        val scope = CommandReceiptScope(
            request.tenantId,
            request.operation,
            CommandFingerprint.key(IdempotencyKey(request.rawKey).value).value,
        )

        return when (val acquired = receipts.acquire(scope, request.fingerprint)) {
            is CommandAcquireResult.Acquired   -> executeOwned(request.operation, acquired, command)
            is CommandAcquireResult.Replay     ->
                meter(request.operation, "replay") {
                    ResponseEntity.status(acquired.httpStatus)
                        .header("Idempotency-Replayed", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(acquired.response)
                }
            is CommandAcquireResult.InProgress ->
                meter(request.operation, "in_progress") {
                    ResponseEntity.status(HttpStatus.CONFLICT)
                        .header(HttpHeaders.RETRY_AFTER, acquired.retryAfter.seconds.coerceAtLeast(1).toString())
                        .body(ApiError("command_in_progress", "command_in_progress"))
                }
            CommandAcquireResult.Conflict      ->
                meter(request.operation, "conflict") {
                    ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiError("idempotency_conflict", "idempotency_conflict"))
                }
        }
    }

    /**
     * executeOwned은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param acquired 업무 처리에 필요한 acquired 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun executeOwned(
        operation: String,
        acquired: CommandAcquireResult.Acquired,
        command: () -> Any,
    ): ResponseEntity<Any> {
        try {
            val result = command()
            val body = objectMapper.writeValueAsString(result)
            receipts.succeed(
                acquired.receipt.id,
                acquired.receipt.ownerToken,
                HttpStatus.CREATED.value(),
                body,
            ).checkBeTrue { "command_receipt_owner_lost" }

            metrics.command(operation, "created")
            return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(result)
        } catch (failure: IllegalArgumentException) {
            recordFailure(acquired, failure)
            metrics.command(operation, "rejected")
            throw failure
        } catch (failure: IllegalStateException) {
            recordFailure(acquired, failure)
            metrics.command(operation, "rejected")
            throw failure
        }
    }

    /**
     * meter은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operation 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param result 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     * @param response 업무 처리에 필요한 response 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun meter(
        operation: String,
        result: String,
        response: () -> ResponseEntity<Any>,
    ): ResponseEntity<Any> {
        metrics.command(operation, result)
        return response()
    }

    /**
     * recordFailure은 사용량 계량·청구 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param acquired 업무 처리에 필요한 acquired 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param failure 업무 처리에 필요한 failure 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun recordFailure(acquired: CommandAcquireResult.Acquired, failure: RuntimeException) {
        val message = failure.message ?: "command_failed"
        receipts.fail(
            receiptId = acquired.receipt.id,
            ownerToken = acquired.receipt.ownerToken,
            httpStatus = MeteringErrorStatus.from(failure).value(),
            response = objectMapper.writeValueAsString(ApiError(message, message)),
        )
    }
}
/**
 * TenantMeteringController은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property commands 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property meters 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property usage 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property periods 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
class TenantMeteringController(
    /**
     * `commands`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: IdempotentHttpCommandExecutor,
    /**
     * `meters`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val meters: MeterService,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceActivationService,
    /**
     * `usage`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val usage: UsageIngestionService,
    /**
     * `periods`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val periods: BillingPeriodService,
) {

    companion object: KLogging()

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
    fun registerMeter(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: RegisterMeterRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> =
        execute(
            request = IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "meter-register",
                rawKey = key,
                fingerprint = CommandFingerprint.request(
                    "meter-register",
                    mapOf("code" to request.code, "unit" to request.unit)
                ),
            ),
            authentication = authentication,
        ) {
            meters.register(
                tenantId = TenantId(value = tenantId),
                code = MeterCode(request.code),
                unit = request.unit,
                description = request.description
            )
        }

    /**
     * `activatePrice`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = activatePrice(tenantId = "tenant-a", meterCode = "demo", key =
     * "demo-idempotency-1",
     * request = httpRequest, authentication = authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param meterCode 요청 경계에서 검증된 `meterCode`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/meters/{meterCode}/prices")
    fun activatePrice(
        @PathVariable tenantId: String,
        @PathVariable meterCode: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ActivatePriceRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> =
        execute(
            request = IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "price-activate",
                rawKey = key,
                fingerprint = CommandFingerprint.request(
                    operation = "price-activate",
                    fields = mapOf(
                        "meterCode" to meterCode,
                        "currency" to request.currency,
                        "unitPrice" to CommandFingerprint.decimal(request.unitPrice),
                        "effectiveFrom" to CommandFingerprint.instant(request.effectiveFrom),
                    ),
                ),
            ),
            authentication = authentication,
        ) {
            prices.activate(
                tenantId = TenantId(tenantId),
                meterCode = MeterCode(meterCode),
                currency = Currency.getInstance(request.currency),
                unitPrice = UnitPrice(request.unitPrice),
                effectiveFrom = request.effectiveFrom,
            )
        }

    /**
     * `ingest`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = ingest(tenantId = "tenant-a", key = "demo-idempotency-1", request =
     * httpRequest,
     * authentication = authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/usage-events")
    fun ingest(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: IngestUsageRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            operation = "usage-ingest",
            fields = mapOf(
                "sourceSystem" to request.sourceSystem,
                "sourceEventId" to request.sourceEventId,
                "meterCode" to request.meterCode,
                "quantity" to CommandFingerprint.decimal(request.quantity),
                "occurredAt" to CommandFingerprint.instant(request.occurredAt),
            ),
        )

        return execute(
            IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "usage-ingest",
                rawKey = key,
                fingerprint = fingerprint
            ),
            authentication
        ) {
            usage.ingest(
                UsageIngestionCommand(
                    tenantId = TenantId(tenantId),
                    sourceSystem = SourceSystem(request.sourceSystem),
                    sourceEventId = SourceEventId(request.sourceEventId),
                    meterCode = MeterCode(request.meterCode),
                    quantity = UsageQuantity(request.quantity),
                    occurredAt = request.occurredAt,
                    actor = authentication.name,
                    correlationId = request.correlationId,
                    requestFingerprint = fingerprint,
                ),
            )
        }
    }

    /**
     * `createPeriod`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = createPeriod(tenantId = "tenant-a", key = "demo-idempotency-1", request =
     * httpRequest, authentication = authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/billing-periods")
    fun createPeriod(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: CreateBillingPeriodRequest,
        authentication: Authentication,
    ): ResponseEntity<Any> =
        execute(
            IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "period-create",
                rawKey = key,
                fingerprint = CommandFingerprint.request(
                    operation = "period-create",
                    fields = mapOf(
                        "currency" to request.currency,
                        "startsAt" to request.startsAt.toString(),
                        "endsAt" to request.endsAt.toString(),
                    ),
                ),
            ),
            authentication,
        ) {
            periods.create(
                tenantId = TenantId(tenantId),
                currency = Currency.getInstance(request.currency),
                startsAt = request.startsAt,
                endsAt = request.endsAt
            )
        }

    /**
     * execute은 사용량 계량·청구의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param request 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     * @param authentication 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    private fun execute(
        request: IdempotentHttpCommand,
        authentication: Authentication,
        command: () -> Any,
    ): ResponseEntity<Any> {
        requireTenant(authentication, request.tenantId)
        return commands.execute(request, command)
    }
}
/**
 * MeteringOperatorController은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property periods 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property close 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property invoices 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property adjustments 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliation 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property prices 청구·정산 금액 또는 통화입니다. 통화 일치와 반올림 규칙을 적용한 뒤 저장합니다.
 * @property commands 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


@RestController
@RequestMapping("/api/v1/operator/tenants/{tenantId}")
@Suppress("LongParameterList") // Operator routes expose explicit application boundaries without a service locator.
class MeteringOperatorController(
    /**
     * `periods`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val periods: BillingPeriodService,
    /**
     * `close`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val close: BillingCloseService,
    /**
     * `invoices`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val invoices: InvoiceService,
    /**
     * `adjustments`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val adjustments: AdjustmentService,
    /**
     * `reconciliation`은 사용량 과금·정산에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliation: ReconciliationService,
    /**
     * `prices`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    private val prices: PriceActivationService,
    /**
     * `commands`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: IdempotentHttpCommandExecutor,
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
     * val response = start(tenantId = "tenant-a", periodId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param periodId 요청 경계에서 검증된 `periodId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/billing-periods/{periodId}/close-runs")
    fun start(@PathVariable tenantId: String, @PathVariable periodId: UUID): ResponseEntity<Any> {
        val run = periods.startClose(TenantId(tenantId), periodId)
        return ResponseEntity.accepted()
            .header(HttpHeaders.LOCATION, "/api/v1/operator/tenants/$tenantId/close-runs/${run.id}")
            .body(run)
    }

    /**
     * `process`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = process(tenantId = "tenant-a", runId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/close-runs/{runId}/process-next")
    fun process(
        @PathVariable tenantId: String,
        @PathVariable runId: UUID,
    ): Any =
        close.processNextBatch(tenantId, runId)

    /**
     * `resume`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = resume(tenantId = "tenant-a", runId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/close-runs/{runId}/resume-after-price-repair")
    fun resume(@PathVariable tenantId: String, @PathVariable runId: UUID): Any =
        close.resumeAfterPriceRepair(tenantId, runId)

    /**
     * `finalize`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = finalize(tenantId = "tenant-a", runId = UUID.randomUUID())
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param runId 요청 경계에서 검증된 `runId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/close-runs/{runId}/finalize")
    fun finalize(@PathVariable tenantId: String, @PathVariable runId: UUID): Any =
        invoices.finalize(tenantId, runId)

    /**
     * `lateDebit`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = lateDebit(tenantId = "tenant-a", usageEventId = UUID.randomUUID(), currency =
     * "demo")
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param usageEventId 요청 경계에서 검증된 `usageEventId`입니다.
     * @param currency 요청 경계에서 검증된 `currency`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/usage-events/{usageEventId}/late-debit")
    fun lateDebit(
        @PathVariable tenantId: String,
        @PathVariable usageEventId: UUID,
        @RequestHeader("X-Currency") currency: String,
    ): Map<String, UUID> =
        mapOf("ledgerEntryId" to adjustments.postLateDebit(tenantId, usageEventId, currency))

    /**
     * `credit`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = credit(tenantId = "tenant-a", request = httpRequest, authentication =
     * authentication)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/adjustments/credits")
    fun credit(
        @PathVariable tenantId: String,
        @RequestBody request: CreditAdjustmentRequest,
        authentication: Authentication,
    ): Map<String, UUID> {
        val entryId = adjustments.postCredit(
            tenantId = tenantId,
            originalEntryId = UUID.fromString(request.originalEntryId),
            reason = request.reason,
            actor = authentication.name,
        )
        return mapOf("ledgerEntryId" to entryId)
    }

    /**
     * `reconcile`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reconcile(tenantId = "tenant-a")
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reconciliation-runs")
    fun reconcile(@PathVariable tenantId: String): Any =
        reconciliation.inspect(tenantId)

    /**
     * `repairLateUsage`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = repairLateUsage(tenantId = "tenant-a", findingId = UUID.randomUUID(), key =
     * "demo-idempotency-1", request = httpRequest)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param findingId 요청 경계에서 검증된 `findingId`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/reconciliation-findings/{findingId}/repair-late-usage")
    fun repairLateUsage(
        @PathVariable tenantId: String,
        @PathVariable findingId: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: ReconciliationRepairRequest,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            operation = "reconciliation-repair",
            fields = mapOf(
                "findingId" to findingId.toString(),
                "expectedDigest" to request.expectedDigest,
                "currency" to request.currency,
            ),
        )
        return commands.execute(
            IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "reconciliation-repair",
                rawKey = key,
                fingerprint = fingerprint
            ),
        ) {
            mapOf(
                "ledgerEntryId" to reconciliation.repairLateUsage(
                    tenantId,
                    findingId,
                    request.expectedDigest,
                    request.currency,
                ),
            )
        }
    }

    /**
     * `repairPriceGap`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = repairPriceGap(tenantId = "tenant-a", key = "demo-idempotency-1", request =
     * httpRequest)
     * ```
     * @param tenantId 요청 경계에서 검증된 `tenantId`입니다.
     * @param key 요청 경계에서 검증된 `key`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/price-gaps/repair")
    fun repairPriceGap(
        @PathVariable tenantId: String,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: RepairPriceGapRequest,
    ): ResponseEntity<Any> {
        val fingerprint = CommandFingerprint.request(
            operation = "price-gap-repair",
            fields = mapOf(
                "meterCode" to request.meterCode,
                "currency" to request.currency,
                "unitPrice" to CommandFingerprint.decimal(request.unitPrice),
                "effectiveFrom" to request.effectiveFrom.toString(),
                "effectiveTo" to request.effectiveTo.toString(),
            ),
        )
        return commands.execute(
            IdempotentHttpCommand(
                tenantId = tenantId,
                operation = "price-gap-repair",
                rawKey = key,
                fingerprint = fingerprint
            )
        ) {
            prices.repairGap(
                PriceGapRepair(
                    tenantId = TenantId(tenantId),
                    meterCode = MeterCode(request.meterCode),
                    currency = Currency.getInstance(request.currency),
                    unitPrice = UnitPrice(request.unitPrice),
                    effectiveFrom = request.effectiveFrom,
                    effectiveTo = request.effectiveTo,
                ),
            )
        }
    }
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
    val operator = authentication.authorities.any { it.authority == "ROLE_OPERATOR" }
    require(operator || authentication.name == tenantId) { "tenant_mismatch" }
}
