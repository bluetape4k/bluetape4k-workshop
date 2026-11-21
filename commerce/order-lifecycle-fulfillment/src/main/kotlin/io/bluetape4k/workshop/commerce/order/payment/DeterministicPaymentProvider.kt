package io.bluetape4k.workshop.commerce.order.payment

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.OrderSubmitted
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import org.springframework.stereotype.Component

/**
 * PaymentProvider은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal interface PaymentProvider {
    /**
     * authorize은 주문 라이프사이클과 fulfillment 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를
     * 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    fun authorize(event: OrderSubmitted): List<PaymentProviderEvent>
}

@Component
/**
 * DeterministicPaymentProvider은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class DeterministicPaymentProvider: PaymentProvider {

    companion object: KLogging()

    /**
     * authorize은 주문 라이프사이클과 fulfillment 요청의 인증·권한·무결성을 확인하고, 실패 시 민감한 내부 상태를 노출하지 않은 채 업무 처리를
     * 차단합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param event 주문 라이프사이클과 fulfillment 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야
     * 합니다.
     */
    override fun authorize(event: OrderSubmitted): List<PaymentProviderEvent> {
        val authorizing = event.providerEvent("authorizing", ProviderEventKind.AUTHORIZING)
        val success = event.providerEvent("success", ProviderEventKind.SUCCEEDED)
        val failure = event.providerEvent("decline", ProviderEventKind.FAILED)

        return when (event.providerMode) {
            ProviderMode.SUCCESS           -> listOf(authorizing, success)
            ProviderMode.DECLINE           -> listOf(authorizing, failure)
            ProviderMode.DELAYED_SUCCESS   -> listOf(authorizing)
            ProviderMode.OUT_OF_ORDER      -> listOf(
                authorizing,
                success,
                authorizing.copy(providerEventId = "${authorizing.providerEventId}-late")
            )
            ProviderMode.DUPLICATE_SUCCESS -> listOf(authorizing, success, success)
        }.also { events ->
            log.debug {
                "deterministic_provider_authorize orderId=${event.orderId} " +
                        "paymentAttemptId=${event.paymentAttemptId} " +
                        "mode=${event.providerMode} eventKinds=${events.joinToString(",") { it.kind.name }}"
            }
        }
    }

    /**
     * providerEvent은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param suffix 업무 처리에 필요한 suffix 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun OrderSubmitted.providerEvent(
        suffix: String,
        kind: ProviderEventKind,
    ): PaymentProviderEvent =
        PaymentProviderEvent(
            providerEventId = "fake-$paymentAttemptId-$suffix",
            paymentAttemptId = paymentAttemptId,
            kind = kind,
            occurredAt = occurredAt
        )
}
