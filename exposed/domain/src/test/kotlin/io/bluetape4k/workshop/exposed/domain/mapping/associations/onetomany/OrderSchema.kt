package io.bluetape4k.workshop.exposed.domain.mapping.associations.onetomany

import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode
import io.bluetape4k.exposed.dao.toStringBuilder
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * One-To-Many Bidirectional Relationship
 */
object OrderSchema {

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS orders (
     *      id SERIAL PRIMARY KEY,
     *      "no" VARCHAR(255) NOT NULL
     * )
     * ```
     */
    object OrderTable: IntIdTable("orders") {
        val no = varchar("no", 255)
    }

    /**
     * ```sql
     * CREATE TABLE IF NOT EXISTS order_items (
     *      id SERIAL PRIMARY KEY,
     *      "name" VARCHAR(255) NOT NULL,
     *      price DECIMAL(10, 2) NULL,
     *      order_id INT NOT NULL,
     *
     *      CONSTRAINT fk_order_items_order_id__id FOREIGN KEY (order_id)
     *      REFERENCES orders(id) ON DELETE CASCADE ON UPDATE CASCADE
     * );
     *
     * CREATE INDEX order_items_order_id ON order_items (order_id);
     * ```
     */
    object OrderItemTable: IntIdTable("order_items") {
        val name = varchar("name", 255)
        val price = decimal("price", 10, 2).nullable()

        // reference to Order
        val order = reference(
            "order_id",
            OrderTable,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE
        ).index()
    }

    class Order(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<Order>(OrderTable)

        var no by OrderTable.no
        val items by OrderItem.referrersOn(OrderItemTable.order)

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("no", no)
                .toString()
    }

    class OrderItem(id: EntityID<Int>): IntEntity(id) {
        companion object: IntEntityClass<OrderItem>(OrderItemTable)

        var name by OrderItemTable.name
        var price by OrderItemTable.price

        var order by Order referencedOn OrderItemTable.order

        override fun equals(other: Any?): Boolean = idEquals(other)
        override fun hashCode(): Int = idHashCode()
        override fun toString(): String =
            toStringBuilder()
                .add("name", name)
                .add("price", price)
                .add("order id", order.id._value)
                .toString()
    }
}
