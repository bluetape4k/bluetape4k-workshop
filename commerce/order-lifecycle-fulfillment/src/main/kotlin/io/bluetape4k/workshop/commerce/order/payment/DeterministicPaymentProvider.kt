package io.bluetape4k.workshop.commerce.order.payment

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.order.domain.OrderSubmitted
import io.bluetape4k.workshop.commerce.order.domain.PaymentProviderEvent
import io.bluetape4k.workshop.commerce.order.domain.ProviderEventKind
import io.bluetape4k.workshop.commerce.order.domain.ProviderMode
import org.springframework.stereotype.Component

internal interface PaymentProvider {
    fun authorize(event: OrderSubmitted): List<PaymentProviderEvent>
}

@Component
internal class DeterministicPaymentProvider : PaymentProvider {
    override fun authorize(event: OrderSubmitted): List<PaymentProviderEvent> {
        val authorizing = event.providerEvent("authorizing", ProviderEventKind.AUTHORIZING)
        val success = event.providerEvent("success", ProviderEventKind.SUCCEEDED)
        val failure = event.providerEvent("decline", ProviderEventKind.FAILED)
        return when (event.providerMode) {
            ProviderMode.SUCCESS -> {
                listOf(authorizing, success)
            }
            ProviderMode.DECLINE -> {
                listOf(authorizing, failure)
            }
            ProviderMode.DELAYED_SUCCESS -> {
                listOf(authorizing)
            }
            ProviderMode.OUT_OF_ORDER -> {
                listOf(
                    authorizing,
                    success,
                    authorizing.copy(providerEventId = "${authorizing.providerEventId}-late")
                )
            }
            ProviderMode.DUPLICATE_SUCCESS -> {
                listOf(authorizing, success, success)
            }
        }.also { events ->
            log.debug {
                "deterministic_provider_authorize orderId=${event.orderId} " +
                    "paymentAttemptId=${event.paymentAttemptId} " +
                    "mode=${event.providerMode} eventKinds=${events.joinToString(",") { it.kind.name }}"
            }
        }
    }

    private fun OrderSubmitted.providerEvent(
        suffix: String,
        kind: ProviderEventKind,
    ) = PaymentProviderEvent(
        providerEventId = "fake-$paymentAttemptId-$suffix",
        paymentAttemptId = paymentAttemptId,
        kind = kind,
        occurredAt = occurredAt
    )

    companion object : KLogging()
}
