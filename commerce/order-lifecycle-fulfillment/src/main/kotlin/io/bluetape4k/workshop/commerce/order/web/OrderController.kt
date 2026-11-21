package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.application.IdempotentOrderSubmissionService
import io.bluetape4k.workshop.commerce.order.application.OrderCommandService
import io.bluetape4k.workshop.commerce.order.application.PaymentEventService
import io.bluetape4k.workshop.commerce.order.application.PublicationReconciliationService
import io.bluetape4k.workshop.commerce.order.application.SubmitOrderRequest
import io.bluetape4k.workshop.commerce.order.domain.FulfillmentStatus
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.Serializable
import java.util.*
/**
 * AdvanceFulfillmentRequest은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property target 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class AdvanceFulfillmentRequest(
    /**
     * `target`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val target: FulfillmentStatus,
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
 * CancelLineRequest은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property quantity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property reasonCode 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 */


internal data class CancelLineRequest(
    /**
     * `quantity`은 주문·결제·재고·배송에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity: Int,
    /**
     * `reasonCode`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
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
 * OrderController은 주문·fulfillment에서 사용하는 업무 객체입니다.
 * @property submissions 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property commands 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property queries 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property streams 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property reconciliation 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property paymentEvents 주문·fulfillment 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


@RestController
@RequestMapping("/api/v1")
internal class OrderController(
    /**
     * `submissions`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val submissions: IdempotentOrderSubmissionService,
    /**
     * `commands`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: OrderCommandService,
    /**
     * `queries`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: OrderLifecycleQueryService,
    /**
     * `streams`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val streams: OrderEventStream,
    /**
     * `reconciliation`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    private val reconciliation: PublicationReconciliationService,
    /**
     * `paymentEvents`은 주문·결제·재고·배송에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val paymentEvents: PaymentEventService,
) {

    companion object: KLogging() {
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val WORKSHOP_OPERATOR_HEADER = "X-Workshop-Operator"
        /**
         * 주문·fulfillment 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        const val WORKSHOP_OPERATOR_VALUE = "local-console"
    }

    /**
     * 주문을 한 번만 접수하고 payment·inventory 후속 workflow가 읽을 durable 결과를 반환합니다.
     *
     * `Idempotency-Key`는 같은 주문 재전송을 replay하는 기준입니다. 동일 key에 다른 payload를 보내면
     * 기존 주문을 덮어쓰지 않고 conflict로 거절하며, provider timeout처럼 결과가 모호한 경우에도
     * HTTP 응답만 보고 성공으로 추정하지 않도록 `Retry-After`와 replay header를 함께 확인해야 합니다.
     * 예:
     * ```bash
     * curl -i -X POST http://localhost:8080/api/v1/orders \
     *   -H 'Content-Type: application/json' \
     *   -H 'Idempotency-Key: order-2026-0001' \
     *   -d '{"customerId":"customer-1","lines":[{"sku":"sku-1","quantity":1}]}'
     * ```
     * @param idempotencyKey 동일 주문 재전송을 판별하는 클라이언트 키입니다.
     * @param request 고객, line, payment provider 정책을 포함한 주문 요청입니다.
     * @return 접수 상태와 replay 여부를 나타내는 HTTP 응답입니다.
     */
    @PostMapping(
        "/orders",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun submit(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: SubmitOrderRequest,
    ): ResponseEntity<String> {
        val result = submissions.submit(idempotencyKey, request)
        log.debug {
            "http_order_submit status=${result.status.value()} replayed=${result.replayed} " +
                    "providerMode=${request.providerMode} lineCount=${request.lines.size}"
        }
        val response = ResponseEntity
            .status(result.status)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Replayed", result.replayed.toString())
        result.retryAfterSeconds?.let { response.header(HttpHeaders.RETRY_AFTER, it.toString()) }

        return response.body(result.body)
    }

    /**
     * `snapshot`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = snapshot(orderId = UUID.randomUUID())
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/orders/{orderId}")
    fun snapshot(
        @PathVariable orderId: UUID,
    ) = queries.snapshot(orderId)

    /**
     * `advance`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = advance(orderId = UUID.randomUUID(), groupId = UUID.randomUUID(), request =
     * httpRequest)
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @param groupId 요청 경계에서 검증된 `groupId`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/orders/{orderId}/fulfillments/{groupId}/advance")
    fun advance(
        @PathVariable orderId: UUID,
        @PathVariable groupId: UUID,
        @RequestBody request: AdvanceFulfillmentRequest,
    ): ResponseEntity<Void> {
        val snapshot = queries.snapshot(orderId)
        check(snapshot.fulfillments.any { it.id == groupId }) { "fulfillment group does not belong to order" }

        return if (commands.advanceFulfillment(groupId, request.target)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.status(409).build()
        }
    }

    /**
     * `cancelLine`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = cancelLine(orderId = UUID.randomUUID(), lineId = UUID.randomUUID(), request =
     * httpRequest)
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @param lineId 요청 경계에서 검증된 `lineId`입니다.
     * @param request 요청 경계에서 검증된 `request`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/orders/{orderId}/lines/{lineId}/cancel")
    fun cancelLine(
        @PathVariable orderId: UUID,
        @PathVariable lineId: UUID,
        @RequestBody request: CancelLineRequest,
    ) = commands.cancelUnshippedLine(orderId, lineId, request.quantity, request.reasonCode)

    /**
     * `events`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = events(orderId = UUID.randomUUID(), lastEventId = 1L)
     * ```
     * @param orderId 요청 경계에서 검증된 `orderId`입니다.
     * @param lastEventId 요청 경계에서 검증된 `lastEventId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/orders/{orderId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable orderId: UUID,
        @RequestHeader("Last-Event-ID", required = false, defaultValue = "0") lastEventId: Long,
    ): SseEmitter = streams.open(orderId, lastEventId)

    /**
     * `replayFailed`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = replayFailed(operatorGuard = "demo", batchSize = 25)
     * ```
     * @param operatorGuard 요청 경계에서 검증된 `operatorGuard`입니다.
     * @param batchSize 요청 경계에서 검증된 `batchSize`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/operations/publications/replay-failed")
    fun replayFailed(
        @RequestHeader(WORKSHOP_OPERATOR_HEADER, required = false) operatorGuard: String?,
        @RequestParam(defaultValue = "10") batchSize: Int,
    ): ResponseEntity<Map<String, Int>> {
        requireWorkshopOperator(operatorGuard)
        /**
         * `result`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        val result = reconciliation.replayFailed(batchSize)

        return ResponseEntity.accepted().body(mapOf("requested" to result.requested))
    }

    /**
     * `reconcileDelayedPayment`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = reconcileDelayedPayment(operatorGuard = "demo", paymentAttemptId =
     * UUID.randomUUID())
     * ```
     * @param operatorGuard 요청 경계에서 검증된 `operatorGuard`입니다.
     * @param paymentAttemptId 요청 경계에서 검증된 `paymentAttemptId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/operations/payments/{paymentAttemptId}/reconcile-delayed")
    fun reconcileDelayedPayment(
        @RequestHeader(WORKSHOP_OPERATOR_HEADER, required = false) operatorGuard: String?,
        @PathVariable paymentAttemptId: UUID,
    ): ResponseEntity<Map<String, String>> {
        requireWorkshopOperator(operatorGuard)
        /**
         * `disposition`은 주문·결제·재고·배송에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
         *
         * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
         */
        val disposition = paymentEvents.reconcileDelayedSuccess(paymentAttemptId)

        return ResponseEntity.accepted().body(mapOf("disposition" to disposition.name))
    }

    /**
     * requireWorkshopOperator은 주문 라이프사이클과 fulfillment 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여
     * 잘못된 상태 전이와
     * 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param operatorGuard 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    private fun requireWorkshopOperator(operatorGuard: String?) {
        require(operatorGuard == WORKSHOP_OPERATOR_VALUE) { "invalid workshop operator guard" }
    }
}
