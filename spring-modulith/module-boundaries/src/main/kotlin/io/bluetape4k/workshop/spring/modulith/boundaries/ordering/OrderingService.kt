package io.bluetape4k.workshop.spring.modulith.boundaries.ordering

import io.bluetape4k.workshop.spring.modulith.boundaries.catalog.api.CatalogLookup
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.events.OrderPlacedEvent
import io.bluetape4k.workshop.spring.modulith.boundaries.ordering.internal.OrderNumberGenerator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Application service that owns order placement and publishes order events.
 */
@Service
class OrderingService(
    private val catalogLookup: CatalogLookup,
    private val orderNumberGenerator: OrderNumberGenerator,
    private val eventPublisher: ApplicationEventPublisher,
) {

    /**
     * Places an order through the exported catalog API and publishes an order event.
     */
    fun placeOrder(request: OrderRequest): OrderReceipt {
        require(request.sku.isNotBlank()) { "sku must not be blank" }
        require(request.quantity > 0) { "quantity must be positive" }
        require(request.customerId.isNotBlank()) { "customerId must not be blank" }

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
