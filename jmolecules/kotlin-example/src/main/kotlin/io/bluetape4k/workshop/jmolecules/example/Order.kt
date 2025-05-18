package io.bluetape4k.workshop.jmolecules.example

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.jmolecules.ddd.types.AggregateRoot
import org.jmolecules.ddd.types.Identifier
import java.io.Serializable

@Entity
@Table(name = "KotlinOrder")
class Order: AggregateRoot<Order, Order.OrderIdentifier> {

    @get:Id
    override var id = OrderIdentifier(TimebasedUuid.Reordered.nextIdAsString())

    override fun toString(): String {
        return "Order(id=$id)"
    }

    data class OrderIdentifier(val value: String): Identifier, Serializable
}
