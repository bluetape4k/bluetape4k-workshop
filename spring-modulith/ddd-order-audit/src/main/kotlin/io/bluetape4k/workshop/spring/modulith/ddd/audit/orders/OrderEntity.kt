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
 * 현재 주문 상태를 나타내는 JPA 표현입니다.
 *
 * ## 동작 / 계약
 * - PostgreSQL 은 현재 aggregate 상태를 저장하고, JaVers 는 history snapshot 을 소유합니다.
 * - [version] 은 반복 또는 동시 승인 시도에 대한 optimistic locking 을 활성화합니다.
 * - [lines] 는 child table 의 value object 로 저장됩니다.
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
     * JPA identity 를 유지하면서 [order] 를 이 entity 에 반영합니다.
     */
    fun apply(order: Order): OrderEntity = apply {
        customerId = order.customerId.value
        status = order.status
        lines.clear()
        lines.addAll(order.lines.map(OrderLineEntity::from))
    }

    /**
     * 이 entity 를 pending event 가 없는 domain aggregate 로 변환합니다.
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
         * domain aggregate 로부터 entity 를 생성합니다.
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
 * JPA embeddable order line 값입니다.
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
