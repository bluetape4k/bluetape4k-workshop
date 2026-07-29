package io.bluetape4k.workshop.spring.modulith.boundaries.invalid.payment

import io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.events.OrderPlacedEvent
import io.bluetape4k.workshop.spring.modulith.boundaries.invalid.ordering.internal.LeakyOrderRepository

/**
 * Spring Modulith verification 에서 반드시 실패해야 하는 test-only dependency leak 입니다.
 */
class PaymentBoundaryLeak(
    private val leakyOrderRepository: LeakyOrderRepository,
) {
    fun authorize(event: OrderPlacedEvent): Boolean =
        leakyOrderRepository.exists(event.orderId)
}
