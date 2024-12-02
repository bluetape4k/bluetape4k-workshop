package io.bluetape4k.workshop.spring.modulith.events.a.fundamentals.quickstart

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import java.io.Serializable

data class Order(
    val id: String = TimebasedUuid.Reordered.nextIdAsString(),
): Serializable {

    var status: OrderStatus = OrderStatus.OPEN
        private set

    fun complete() {
        this.status = OrderStatus.COMPLETED
    }

    override fun toString(): String {
        return "Order(id='$id', status=$status)"
    }

    enum class OrderStatus {
        OPEN,
        COMPLETED;
    }
}
