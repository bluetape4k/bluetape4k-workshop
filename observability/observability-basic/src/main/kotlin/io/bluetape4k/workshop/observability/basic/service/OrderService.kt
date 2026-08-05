package io.bluetape4k.workshop.observability.basic.service

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.micrometer.observation.coroutines.withObservationContextSuspending
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.observability.basic.client.InventoryClient
import io.bluetape4k.workshop.observability.basic.model.Order
import io.micrometer.observation.ObservationRegistry
import org.springframework.stereotype.Service

/**
 * downstream service 에서 inventory 를 조회해 order retrieval 을 조율합니다.
 *
 * ## Behavior / Contract
 * - outbound WebClient call 을 감싸는 manual span `order.service.fetch` 를 생성합니다.
 * - inventory 를 사용할 수 없으면, 즉 item 을 찾지 못하면 `null` 을 반환합니다.
 * - released `withObservationContextSuspending` helper 로 observation lifecycle 과 coroutine context 전파를 모두 보장합니다.
 * - structured concurrency 를 위해 `runCatching {}` 을 사용하지 않습니다. `CancellationException` 은 반드시 전파되어야 합니다.
 */
@Service
class OrderService(
    private val inventoryClient: InventoryClient,
    private val observationRegistry: ObservationRegistry,
) {
    companion object : KLoggingChannel()

    /**
     * 주어진 [orderId] 에 대해 inventory availability 가 보강된 [Order] 를 조회합니다.
     *
     * order 의 item 에 대한 inventory 를 찾지 못하면 `null` 을 반환합니다.
     */
    suspend fun getOrder(orderId: Long): Order? =
        withObservationContextSuspending("order.service.fetch", observationRegistry) {
            val validOrderId = orderId.requirePositiveNumber("orderId")
            val inventory = inventoryClient.fetchInventory(validOrderId)
                ?: return@withObservationContextSuspending null
            debug { "Fetched inventory for orderId=$validOrderId: available=${inventory.available}" }
            Order(
                id = validOrderId,
                itemId = inventory.itemId,
                quantity = 1,
                inventoryAvailable = inventory.available,
            )
        }
}
