package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Payment module listener that consumes the ordering module's event contract.
 */
@Component
class PaymentEventHandler(
    private val paymentLedger: PaymentLedger,
) {

    @EventListener
    fun on(event: OrderPlacedEvent) {
        paymentLedger.authorize(event)
    }
}
