package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory payment-owned ledger used to prove event-driven handoff.
 */
@Component
class PaymentLedger {

    private val authorizations = ConcurrentHashMap<String, PaymentAuthorization>()

    fun authorize(event: OrderPlacedEvent): PaymentAuthorization {
        val authorization = PaymentAuthorization(
            orderId = event.orderId,
            customerId = event.customerId,
            amountCents = event.totalCents,
        )
        authorizations[event.orderId] = authorization
        return authorization
    }

    fun find(orderId: String): PaymentAuthorization? =
        authorizations[orderId]

    fun all(): List<PaymentAuthorization> =
        authorizations.values.sortedBy { it.orderId }

    fun reset() {
        authorizations.clear()
    }
}
