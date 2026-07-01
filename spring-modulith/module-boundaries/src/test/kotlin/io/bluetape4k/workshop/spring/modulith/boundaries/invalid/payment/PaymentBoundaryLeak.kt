package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.payment

import io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.events.OrderPlacedEvent
import io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.internal.LeakyOrderRepository

/**
 * Test-only dependency leak that must fail Spring Modulith verification.
 */
class PaymentBoundaryLeak(
    private val leakyOrderRepository: LeakyOrderRepository,
) {
    fun authorize(event: OrderPlacedEvent): Boolean =
        leakyOrderRepository.exists(event.orderId)
}
