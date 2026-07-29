package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api.CatalogLookup
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.internal.OrderNumberGenerator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * order placement 를 소유하고 order event 를 publish 하는 application service 입니다.
 */
@Service
class OrderingService(
    private val catalogLookup: CatalogLookup,
    private val orderNumberGenerator: OrderNumberGenerator,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * exported catalog API 를 통해 주문을 생성하고 order event 를 publish 합니다.
     */
    fun placeOrder(request: OrderRequest): OrderReceipt {
        request.sku.requireNotBlank("sku")
        request.quantity.requirePositiveNumber("quantity")
        request.customerId.requireNotBlank("customerId")

        val item = requireNotNull(catalogLookup.findItem(request.sku)) {
            "catalog item not found: ${request.sku}"
        }
        require(item.inStock) {
            "catalog item is not in stock: ${request.sku}"
        }

        val orderId = orderNumberGenerator.nextOrderId()
        val totalCents = item.unitPriceCents * request.quantity
        val event = OrderPlacedEvent(
            orderId = orderId,
            customerId = request.customerId,
            sku = item.sku,
            quantity = request.quantity,
            totalCents = totalCents,
            placedAt = Instant.now(),
        )

        eventPublisher.publishEvent(event)

        return OrderReceipt(
            orderId = orderId,
            sku = item.sku,
            quantity = request.quantity,
            totalCents = totalCents,
        )
    }
}
