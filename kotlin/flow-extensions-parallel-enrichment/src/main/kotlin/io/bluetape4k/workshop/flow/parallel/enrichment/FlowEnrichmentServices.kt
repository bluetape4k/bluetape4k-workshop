package io.bluetape4k.workshop.flow.parallel.enrichment

import kotlinx.coroutines.delay

/**
 * 고객 프로필 조회를 담당하는 간단한 In-memory 서비스입니다.
 */
class CustomerProfileService(
    private val profiles: Map<String, LoyaltyGrade>,
    private val ioDelayMillis: Long = 20L
) {
    suspend fun findByCustomerId(customerId: String): CustomerProfile {
        delay(ioDelayMillis)
        val grade = profiles[customerId]
            ?: throw UnknownCustomerException(customerId)
        return CustomerProfile(customerId, grade)
    }
}

/**
 * 재고 조사를 담당하는 간단한 In-memory 서비스입니다.
 */
class InventoryService(
    private val stockBySku: Map<String, Int>,
    private val ioDelayMillis: Long = 20L
) {
    suspend fun reserve(orderItems: List<OrderItem>): InventorySnapshot {
        delay(ioDelayMillis)
        val snapshots = orderItems.map { item ->
            val stock = stockBySku[item.sku] ?: throw UnknownProductException(item.sku)
            ProductInventorySnapshot(
                sku = item.sku,
                requestQuantity = item.quantity,
                availableQuantity = stock
            )
        }
        return InventorySnapshot(snapshots)
    }
}

/**
 * 할인 정책 계산을 담당하는 간단한 In-memory 서비스입니다.
 */
class PromotionService(
    private val baseDiscount: Int = 0,
    private val ioDelayMillis: Long = 10L
) {
    suspend fun estimateDiscount(profile: CustomerProfile): Int {
        delay(ioDelayMillis)
        return when (profile.loyaltyGrade) {
            LoyaltyGrade.REGULAR -> baseDiscount
            LoyaltyGrade.SILVER -> (baseDiscount + 5).coerceAtMost(20)
            LoyaltyGrade.GOLD -> (baseDiscount + 10).coerceAtMost(20)
        }
    }
}
