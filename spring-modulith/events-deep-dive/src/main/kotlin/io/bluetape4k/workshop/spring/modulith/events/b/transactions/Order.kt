package io.bluetape4k.workshop.spring.modulith.events.b.transactions

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.workshop.spring.modulith.events.util.StringAggregate
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "MyOrder")
class Order: StringAggregate<Order>() {

    var status: OrderStatus = OrderStatus.OPEN

    fun complete(): Order = apply {
        this.status = OrderStatus.COMPLETED
        registerEvent(OrderCompleted(this))
    }

    override fun buildStringHelper(): ToStringBuilder {
        return super.buildStringHelper()
            .add("status", status)
    }

    enum class OrderStatus {
        OPEN,
        COMPLETED;
    }

    data class OrderCompleted(val order: Order)
}
