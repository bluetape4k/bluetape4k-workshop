package io.bluetape4k.workshop.flow.parallel.enrichment

import java.io.Serializable

/**
 * 주문 엔티티 모델과 도메인 계약 객체입니다.
 */
data class OrderItem(
    val sku: String,
    val quantity: Int
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 병렬로 enrichment 가 필요한 입력 이벤트입니다.
 */
data class OrderCommand(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 2L
    }
}

data class CustomerProfile(
    val customerId: String,
    val loyaltyGrade: LoyaltyGrade
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 3L
    }
}

enum class LoyaltyGrade {
    REGULAR,
    SILVER,
    GOLD,
}

data class ProductInventorySnapshot(
    val sku: String,
    val requestQuantity: Int,
    val availableQuantity: Int
) : Serializable {
    val availableForOrder: Boolean get() = availableQuantity >= requestQuantity

    companion object {
        private const val serialVersionUID: Long = 4L
    }
}

data class InventorySnapshot(
    val perItem: List<ProductInventorySnapshot>
) : Serializable {
    val fulfillable: Boolean get() = perItem.all { it.availableForOrder }
    val unavailableSkus: List<String> get() = perItem.filter { !it.availableForOrder }.map { it.sku }

    companion object {
        private const val serialVersionUID: Long = 5L
    }
}

data class EnrichedOrder(
    val orderId: String,
    val customerId: String,
    val loyaltyGrade: LoyaltyGrade,
    val totalItems: Int,
    val discountPercent: Int,
    val fulfillable: Boolean,
    val unavailableSkus: List<String>
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 6L
    }
}

class UnknownCustomerException(customerId: String) :
    IllegalArgumentException("Customer not found: $customerId")

class UnknownProductException(sku: String) :
    IllegalArgumentException("Product not found: $sku")
