package io.bluetape4k.workshop.spring.modulith.events.d.architecture.after.order

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.workshop.spring.modulith.events.util.StringAggregate
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicInsert
import org.hibernate.annotations.DynamicUpdate

@Entity
@Table(name = "MyOrder")
@DynamicInsert
@DynamicUpdate
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
