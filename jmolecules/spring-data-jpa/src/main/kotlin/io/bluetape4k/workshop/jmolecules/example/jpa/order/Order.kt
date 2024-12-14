package io.bluetape4k.workshop.jmolecules.example.jpa.order

import io.bluetape4k.idgenerators.uuid.TimebasedUuid
import io.bluetape4k.workshop.jmolecules.example.jpa.customer.Customer
import io.bluetape4k.workshop.jmolecules.example.jpa.customer.Customer.CustomerId
import jakarta.persistence.Table
import org.jmolecules.ddd.types.AggregateRoot
import org.jmolecules.ddd.types.Association
import org.jmolecules.ddd.types.Identifier
import java.io.Serializable

@Table(name = "SAMPLE_ORDER")
class Order(customer: Customer): AggregateRoot<Order, Order.OrderId> {

    override val id: OrderId = OrderId(TimebasedUuid.Reordered.nextIdAsString())

    val lineItems: MutableList<LineItem> = mutableListOf()
    var customer: Association<Customer, CustomerId> = Association.forAggregate(customer)

    @JvmInline
    value class OrderId(val id: String): Identifier, Serializable
}
