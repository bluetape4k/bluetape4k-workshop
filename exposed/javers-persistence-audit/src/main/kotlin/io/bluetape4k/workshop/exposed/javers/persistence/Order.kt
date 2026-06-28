package io.bluetape4k.workshop.exposed.javers.persistence

import org.javers.core.metamodel.annotation.Id
import org.javers.core.metamodel.annotation.TypeName
import java.io.Serializable
import java.math.BigDecimal

/**
 * Immutable order aggregate audited by JaVers.
 *
 * ## Behavior / Contract
 * - [id] is the JaVers entity key and the Exposed table primary key.
 * - [status] records the lifecycle state used by audit-history examples.
 * - [totalAmount] is stored as a decimal value to avoid floating-point rounding.
 *
 * ```kotlin
 * val order = Order("order-1", "customer-1", OrderStatus.PLACED, BigDecimal("19.99"))
 * val paid = order.copy(status = OrderStatus.PAID)
 * ```
 */
@TypeName("Order")
data class Order(
    @Id val id: String,
    val customerId: String,
    val status: OrderStatus,
    val totalAmount: BigDecimal,
): Serializable {
    init {
        require(id.isNotBlank()) { "id must not be blank." }
        require(customerId.isNotBlank()) { "customerId must not be blank." }
        require(totalAmount >= BigDecimal.ZERO) { "totalAmount must not be negative." }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Order lifecycle states used by the persistence audit workshop.
 */
enum class OrderStatus {
    PLACED,
    PAID,
}
