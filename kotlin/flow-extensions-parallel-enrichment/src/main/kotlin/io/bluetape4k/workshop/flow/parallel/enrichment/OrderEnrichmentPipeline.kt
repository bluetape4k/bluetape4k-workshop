package io.bluetape4k.workshop.flow.parallel.enrichment

import io.bluetape4k.coroutines.flow.extensions.parallel.map as parallelMap
import io.bluetape4k.coroutines.flow.extensions.parallel.parallel
import io.bluetape4k.coroutines.flow.extensions.parallel.sequential
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 주문 Enrichment 파이프라인.
 *
 * - 유효한 주문만 병렬로 enrichment 한다.
 * - 각 주문은 병렬 rail에서 `parallel` 처리 후 `sequential` 로 결합한다.
 */
class OrderEnrichmentPipeline(
    private val customerProfileService: CustomerProfileService,
    private val inventoryService: InventoryService,
    private val promotionService: PromotionService
) {

    /**
     * 고객, 재고, 할인 조회를 병렬 rail로 실행한 뒤 단일 결과 스트림으로 재정렬합니다.
     */
    fun enrichInParallel(
        source: Flow<OrderCommand>,
        parallelism: Int,
        runOn: (Int) -> CoroutineDispatcher
    ): Flow<EnrichedOrder> {
        parallelism.requirePositiveNumber("parallelism")

        return source
            .filter(::isValidOrder)
            .parallel(parallelism, runOn)
            .parallelMap { order -> enrichOrder(order) }
            .sequential()
    }

    /**
     * 동일한 Enrichment 로직을 병렬 rail 없이 순차로 실행합니다.
     */
    fun enrichSequentially(source: Flow<OrderCommand>): Flow<EnrichedOrder> =
        source
            .filter(::isValidOrder)
            .map { order -> enrichOrder(order) }

    private fun isValidOrder(order: OrderCommand): Boolean =
        order.orderId.isNotBlank() &&
            order.customerId.isNotBlank() &&
            order.items.isNotEmpty()

    private suspend fun enrichOrder(order: OrderCommand): EnrichedOrder = coroutineScope {
        val profile = async {
            customerProfileService.findByCustomerId(order.customerId)
        }
        val inventory = async {
            inventoryService.reserve(order.items)
        }
        val customer = profile.await()
        val stock = inventory.await()
        val discount = promotionService.estimateDiscount(customer)

        EnrichedOrder(
            orderId = order.orderId,
            customerId = order.customerId,
            loyaltyGrade = customer.loyaltyGrade,
            totalItems = order.items.sumOf { it.quantity },
            discountPercent = discount,
            fulfillable = stock.fulfillable,
            unavailableSkus = stock.unavailableSkus
        )
    }
}
