package io.bluetape4k.workshop.jmolecules.example.jpa.order

import org.jmolecules.ddd.integration.AssociationResolver
import org.jmolecules.ddd.types.Repository

interface OrderRepository
    : Repository<Order, Order.OrderId>, AssociationResolver<Order, Order.OrderId> {

    fun save(order: Order): Order
}
