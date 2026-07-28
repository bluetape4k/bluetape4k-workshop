package io.bluetape4k.workshop.messaging.fallback.domain

import io.bluetape4k.workshop.messaging.fallback.api.OrderPublicationStatus
import io.bluetape4k.workshop.messaging.fallback.api.OrderRequest
import io.bluetape4k.workshop.messaging.fallback.api.OrderResponse
import io.bluetape4k.workshop.messaging.fallback.publication.OrderEventPublisher
import io.bluetape4k.workshop.messaging.fallback.publication.OrderPlacedEvent
import org.springframework.stereotype.Service

/**
 * public order placement boundary 입니다.
 *
 * hot transaction 안에서 order 를 persist 한 뒤 [OrderEventPublisher] 에게 deterministic `OrderPlaced` event 를 Kafka 로 직접 publish 하거나, direct publish 가 비활성화되거나 실패하면 durable fallback row 를 저장하도록 요청합니다.
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
