package io.bluetape4k.workshop.exposed.domain.mapping.onetomany

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE

/**
 * One-To-Many Bidirectional Relationship
 */
object OrderSchema {

    object OrderTable: IntIdTable("orders") {
        val no = varchar("no", 255)
    }

    object OrderItemTable: IntIdTable("order_items") {
        val name = varchar("name", 255)
        val price = decimal("price", 10, 2).nullable()

        // reference to Order
        val order = reference("order_id", OrderTable, onDelete = CASCADE, onUpdate = CASCADE).index()
    }

    class Order(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Order>(OrderTable)

        var no by OrderTable.no
        val items by OrderItem.referrersOn(OrderItemTable.order)

        override fun equals(other: Any?): Boolean = other is Order && other.id._value == id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String = "Order(id=$id, no=$no)"
    }

    class OrderItem(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<OrderItem>(OrderItemTable)

        var name by OrderItemTable.name
        var price by OrderItemTable.price

        var order by Order referencedOn OrderItemTable.order

        override fun equals(other: Any?): Boolean = other is OrderItem && other.id._value == id._value
        override fun hashCode(): Int = id._value?.hashCode() ?: System.identityHashCode(this)
        override fun toString(): String =
            "OrderItem(id=$id, name=$name, price=$price, order=${order.id._value})"
    }
}
