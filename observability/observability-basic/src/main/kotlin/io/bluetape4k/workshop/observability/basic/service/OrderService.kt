package io.bluetape4k.workshop.observability.basic.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.basic.client.InventoryClient
import io.bluetape4k.workshop.observability.basic.model.Order
import io.bluetape4k.workshop.observability.basic.observation.observed
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Service

/**
 * Orchestrates order retrieval by fetching inventory from the downstream service.
 *
 * ## Behavior / Contract
 * - Produces manual span `order.service.fetch` wrapping the outbound WebClient call.
 * - Returns `null` when inventory is unavailable (item not found).
 * - Uses local [observed] helper (not `withObservationSuspending`) to guarantee `stop()` on all paths,
 *   including happy path — workaround for missing `finally { stop() }` in 1.8.0-SNAPSHOT library.
 * - Does not use `runCatching {}` — `CancellationException` must propagate for structured concurrency.
 */
@Service
class OrderService(
    private val inventoryClient: InventoryClient,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLoggingChannel()

    /**
     * Retrieves an [Order] for the given [orderId], enriched with inventory availability.
     *
     * Returns `null` when inventory is not found for the order's item.
     */
    suspend fun getOrder(orderId: Long): Order? =
        observed("order.service.fetch", observationRegistry) {
            val validOrderId = orderId.requirePositiveNumber("orderId")
            val inventory = inventoryClient.fetchInventory(validOrderId) ?: return@observed null
            debug { "Fetched inventory for orderId=$validOrderId: available=${inventory.available}" }
            Order(
                id = validOrderId,
                itemId = inventory.itemId,
                quantity = 1,
                inventoryAvailable = inventory.available,
            )
        }
}
