package io.bluetape4k.workshop.messaging.fallback.domain

import io.bluetape4k.workshop.messaging.fallback.api.OrderPublicationStatus
import io.bluetape4k.workshop.messaging.fallback.api.OrderRequest
import io.bluetape4k.workshop.messaging.fallback.api.OrderResponse
import io.bluetape4k.workshop.messaging.fallback.publication.OrderEventPublisher
import io.bluetape4k.workshop.messaging.fallback.publication.OrderPlacedEvent
import org.springframework.stereotype.Service

/**
 * Public order placement boundary.
 *
 * Direct Kafka publish and fallback persistence are added in the next TDD task;
 * until then the use case exposes `UNKNOWN` for read-only validation paths.
 */
@Service
class PlaceOrderUseCase(
    private val transactionalOrderWriter: TransactionalOrderWriter,
    private val orderEventPublisher: OrderEventPublisher,
) {

    fun placeOrder(request: OrderRequest): OrderResponse {
        val order = transactionalOrderWriter.saveOrder(
            customerId = request.customerId,
            product = request.product,
            quantity = request.quantity,
        )
        val event = OrderPlacedEvent.from(order)
        val publicationStatus = orderEventPublisher.publishDirectOrFallback(event)
        return OrderResponse.from(order, publicationStatus)
    }
}
