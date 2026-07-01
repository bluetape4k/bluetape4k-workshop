package io.bluetape4k.workshop.spring.modulith.ddd.audit.orders

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal

/**
 * JPA representation of the current order state.
 *
 * ## Behavior / Contract
 * - PostgreSQL stores current aggregate state; JaVers owns history snapshots.
 * - [version] enables optimistic locking for repeated or concurrent approval attempts.
 * - [lines] are stored as value objects in a child table.
 */
@Entity
@Table(name = "orders")
class OrderEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 96)
    var id: String = "",

    @Column(name = "customer_id", nullable = false, length = 96)
    var customerId: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: OrderStatus = OrderStatus.PLACED,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
) {
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_lines", joinColumns = [JoinColumn(name = "order_id")])
    @OrderColumn(name = "line_index")
    var lines: MutableList<OrderLineEntity> = mutableListOf()

    /**
     * Applies [order] to this entity while preserving the JPA identity.
     */
    fun apply(order: Order): OrderEntity = apply {
        customerId = order.customerId.value
        status = order.status
        lines.clear()
        lines.addAll(order.lines.map(OrderLineEntity::from))
    }

    /**
     * Converts this entity to a domain aggregate without pending events.
     */
    fun toDomain(): Order =
        Order.restore(
            id = OrderId(id),
            customerId = CustomerId(customerId),
            lines = lines.map { it.toDomain() },
            status = status,
            version = version,
        )

    companion object {
        /**
         * Creates an entity from a domain aggregate.
         */
        fun from(order: Order): OrderEntity =
            OrderEntity(
                id = order.id.value,
                customerId = order.customerId.value,
                status = order.status,
                version = order.version,
            ).apply(order)
    }
}

/**
 * JPA embeddable order line value.
 */
@Embeddable
class OrderLineEntity(
    @Column(name = "sku", nullable = false, length = 96)
    var sku: String = "",

    @Column(name = "quantity", nullable = false)
    var quantity: Int = 0,

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    var unitPriceAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "unit_price_currency", nullable = false, length = 3)
    var unitPriceCurrency: String = "USD",
) {
    fun toDomain(): OrderLine =
        OrderLine(
            sku = sku,
            quantity = quantity,
            unitPrice = Money(unitPriceAmount, unitPriceCurrency),
        )

    companion object {
        fun from(line: OrderLine): OrderLineEntity =
            OrderLineEntity(
                sku = line.sku,
                quantity = line.quantity,
                unitPriceAmount = line.unitPrice.amount,
                unitPriceCurrency = line.unitPrice.currency,
            )
    }
}
