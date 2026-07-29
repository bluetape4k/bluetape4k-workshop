package io.bluetape4k.workshop.spring.modulith.boundaries.payment

import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * ordering module 의 event contract 를 소비하는 payment module listener 입니다.
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
