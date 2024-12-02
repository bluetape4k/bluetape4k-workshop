package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.springdata

import io.bluetape4k.workshop.spring.modulith.events.util.StringAggregate
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "MyOrder")
class Order: StringAggregate<Order>() {

    var status: OrderStatus = OrderStatus.OPEN

    fun complete(): Order {
        this.status = OrderStatus.COMPLETED
        registerEvent(OrderCompleted(this))
        return this
    }

    override fun toString(): String {
        return "Order(id='$id', status=$status)"
    }

    enum class OrderStatus {
        OPEN,
        COMPLETED;
    }

    data class OrderCompleted(val order: Order)
}
