package io.bluetape4k.workshop.commerce.order.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrder
import io.bluetape4k.workshop.commerce.order.domain.SubmitOrderLine
import io.bluetape4k.workshop.commerce.order.idempotency.AcquireResult
import io.bluetape4k.workshop.commerce.order.idempotency.HttpIdempotencyRepository
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyFingerprint
import io.bluetape4k.workshop.commerce.order.idempotency.IdempotencyScope
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.Serializable
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
/**
 * SubmitOrderRequest은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 주문·fulfillment 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게
 * 합니다.
 * @property customerReference 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property lines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property providerMode 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class SubmitOrderRequest(
    /**
     * `tenantId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `customerReference`은 주문·결제·재고·배송에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val customerReference: String,
    /**
     * `lines`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lines: List<SubmitOrderLineRequest>,
    /**
     * `providerMode`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val providerMode: ProviderMode = ProviderMode.SUCCESS,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * SubmitOrderLineRequest은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property sku 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property quantity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property unitPrice 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 */


internal data class SubmitOrderLineRequest(
    /**
     * `sku`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sku: String,
    /**
     * `quantity`은 주문·결제·재고·배송에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity: Int,
    /**
     * `unitPrice`은 주문·결제·재고·배송에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val unitPrice: BigDecimal,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * IdempotentOrderResult은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property status 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property body 주문·fulfillment 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 * @property replayed 주문·fulfillment 정책을 켜고 끄는 플래그입니다. true/false 각각의 처리 경로와 기본값을 계약으로 유지해야 합니다.
 * @property retryAfterSeconds 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 */


internal data class IdempotentOrderResult(
    /**
     * `status`은 주문·결제·재고·배송에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val status: HttpStatus,
    /**
     * `body`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val body: String,
    /**
     * `replayed`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val replayed: Boolean,
    /**
     * `retryAfterSeconds`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val retryAfterSeconds: Long? = null,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * IdempotentOrderSubmissionService은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property idempotency 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property commands 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property clock 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */


@Service
internal class IdempotentOrderSubmissionService(
    /**
     * `idempotency`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val idempotency: HttpIdempotencyRepository,
    /**
     * `commands`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: OrderCommandService,
    /**
     * `clock`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val clock: Clock,
) {

    companion object: KLogging() {
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val SUBMIT_ORDER = "SUBMIT_ORDER"
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val LEASE = 30.seconds()
        /**
         * 재시도·임대·보존·처리 제한을 결정하는 시간 정책입니다. 장애 시 무한 대기와 중복 실행을 막습니다.
         */
        private val RETENTION = 24.hours()
    }

    /**
     * `submit`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = submit(rawKey = "demo-idempotency-1", request = httpRequest)
     * ```
     * @param rawKey 요청 경계에서 검증된 `rawKey`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @Transactional
    fun submit(
        rawKey: String,
        request: SubmitOrderRequest,
    ): IdempotentOrderResult {
        require(rawKey.length in 8..200) { "Idempotency-Key must contain 8..200 characters" }
        val now = Instant.now(clock)
        val scope = IdempotencyScope(
            tenantId = request.tenantId,
            operation = SUBMIT_ORDER,
            keyHash = IdempotencyFingerprint.key(rawKey)
        )
        val ownerToken = Uuid.V7.nextId()
        val acquired = idempotency.acquire(
            scope = scope,
            fingerprint = IdempotencyFingerprint.request(request.canonicalPayload()),
            ownerToken = ownerToken,
            now = now,
            lease = LEASE,
            retention = RETENTION
        )

        return when (acquired) {
            is AcquireResult.Acquired -> {
                val submitted = commands.submit(request.toCommand())
                val body = """{"orderId":"${submitted.orderId}","paymentAttemptId":"${submitted.paymentAttemptId}"}"""
                check(idempotency.finalize(acquired.record.id, ownerToken, HttpStatus.CREATED.value(), body, false)) {
                    "idempotency owner lost before finalize"
                }
                log.info {
                    "idempotency_completed scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)} " +
                            "orderId=${submitted.orderId} status=${HttpStatus.CREATED.value()}"
                }
                IdempotentOrderResult(HttpStatus.CREATED, body, replayed = false)
            }
            is AcquireResult.Replay -> {
                log.info {
                    "idempotency_replayed scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)} status=${acquired.status}"
                }
                IdempotentOrderResult(
                    status = HttpStatus.valueOf(acquired.status),
                    body = acquired.body,
                    replayed = true
                )
            }
            AcquireResult.FingerprintConflict -> {
                log.warn {
                    "idempotency_fingerprint_conflict scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)}"
                }
                errorResult(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_FINGERPRINT_CONFLICT"
                )
            }
            is AcquireResult.InProgress -> {
                log.warn {
                    "idempotency_in_progress scope=$SUBMIT_ORDER keyHashPrefix=${scope.keyHash.take(12)} " +
                            "retryAfterSeconds=${acquired.retryAfter.seconds.coerceAtLeast(1)}"
                }
                errorResult(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_IN_PROGRESS",
                    acquired.retryAfter.seconds.coerceAtLeast(1)
                )
            }
        }
    }

    /**
     * toCommand은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SubmitOrderRequest.toCommand(): SubmitOrder =
        SubmitOrder(
            tenantId = tenantId,
            customerReference = customerReference,
            providerMode = providerMode,
            lines = lines.map { SubmitOrderLine(it.sku, it.quantity, it.unitPrice) }
        )

    /**
     * canonicalPayload은 주문 라이프사이클과 fulfillment 경계의 payload를 계약된 스키마와 canonical 표현으로 변환하고, 누락·버전
     * 불일치·무결성
     * 오류를 업무 오류로 분류합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun SubmitOrderRequest.canonicalPayload(): String = buildString {
        append("tenant=")
            .append(tenantId.length)
            .append(':')
            .appendLine(tenantId)

        append("customer=")
            .append(customerReference.length)
            .append(':')
            .appendLine(customerReference)

        append("provider=").appendLine(providerMode.name)

        lines.forEachIndexed { index, line ->
            append(index)
                .append('|')
                .append(line.sku.length)
                .append(':')
                .append(line.sku)
                .append('|')
                .append(line.quantity)
                .append('|')
                .appendLine(line.unitPrice.stripTrailingZeros().toPlainString())
        }
    }

    /**
     * errorResult은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param retryAfterSeconds 업무 처리에 필요한 retryAfterSeconds 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun errorResult(
        status: HttpStatus,
        code: String,
        retryAfterSeconds: Long? = null,
    ) = IdempotentOrderResult(
        status = status,
        body = """{"code":"$code"}""",
        replayed = false,
        retryAfterSeconds = retryAfterSeconds
    )
}
