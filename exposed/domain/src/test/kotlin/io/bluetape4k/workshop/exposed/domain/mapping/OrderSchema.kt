package io.bluetape4k.workshop.exposed.domain.mapping

import io.bluetape4k.workshop.exposed.domain.AbstractExposedTest
import io.bluetape4k.workshop.exposed.domain.TestDB
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.Item
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.ItemTable
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.Order
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderDetail
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderDetailTable
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderLine
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderLineTable
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.OrderTable
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.User
import io.bluetape4k.workshop.exposed.domain.mapping.OrderSchema.UserTable
import io.bluetape4k.workshop.exposed.domain.withTables
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.javatime.date
import java.io.Serializable
import java.time.LocalDate

object OrderSchema {

    val allOrderTables = arrayOf(OrderTable, OrderDetailTable, ItemTable, OrderLineTable, UserTable)

    object OrderTable: LongIdTable("orders") {
        val orderDate = date("order_date")
    }

    object OrderDetailTable: LongIdTable("order_details") {
        val orderId = reference("order_id", OrderTable)
        val lineNumber = integer("line_number")
        val description = varchar("description", 255)
        val quantity = integer("quantity")
    }

    object ItemTable: LongIdTable("items") {
        val description = varchar("description", 255)
    }

    object OrderLineTable: LongIdTable("order_lines") {
        val orderId = reference("order_id", OrderTable)
        val itemId = optReference("item_id", ItemTable)
        val lineNumber = integer("line_number")
        val quantity = integer("quantity")
    }

    object UserTable: LongIdTable("users") {
        val userName = varchar("user_name", 255)
        val parentId = reference("parent_id", UserTable).nullable()
    }

    class Order(id: EntityID<Long>): LongEntity(id), Serializable {
        companion object: LongEntityClass<Order>(OrderTable)

        val details by OrderDetail referrersOn OrderDetailTable.orderId
        var orderDate by OrderTable.orderDate

        override fun toString(): String = "Order(id=$id, orderDate=$orderDate)"
    }

    class OrderDetail(id: EntityID<Long>): LongEntity(id), Serializable {
        companion object: LongEntityClass<OrderDetail>(OrderDetailTable)

        var order by Order referencedOn OrderDetailTable
        var lineNumber by OrderDetailTable.lineNumber
        var description by OrderDetailTable.description
        var quantity by OrderDetailTable.quantity

        val orderId by OrderDetailTable.orderId

        override fun toString(): String =
            "OrderDetail(id=$id, orderId=$orderId, lineNumber=$lineNumber, description=$description, quantity=$quantity)"
    }

    class Item(id: EntityID<Long>): LongEntity(id), Serializable {
        companion object: LongEntityClass<Item>(ItemTable)

        var description by ItemTable.description
    }

    class OrderLine(id: EntityID<Long>): LongEntity(id), Serializable {
        companion object: LongEntityClass<OrderLine>(OrderLineTable)

        var order by Order referencedOn OrderLineTable
        var item by Item optionalReferencedOn OrderLineTable
        var lineNumber by OrderLineTable.lineNumber
        var quantity by OrderLineTable.quantity
    }

    class User(id: EntityID<Long>): LongEntity(id), Serializable {
        companion object: LongEntityClass<User>(UserTable)

        var userName by UserTable.userName
        var parent by User optionalReferencedOn UserTable.parentId
    }

    data class OrderRecord(
        val itemId: Long? = null,
        val orderId: Long? = null,
        val quantity: Int? = null,
        val description: String? = null,
    ): Comparable<OrderRecord>, Serializable {
        override fun compareTo(other: OrderRecord): Int =
            orderId?.compareTo(other.orderId ?: 0)
                ?: itemId?.compareTo(other.itemId ?: 0)
                ?: 0
    }
}

fun AbstractExposedTest.withOrdersTables(
    testDB: TestDB,
    statement: Transaction.(
        orders: OrderTable,
        orderDetails: OrderDetailTable,
        items: ItemTable,
        orderLines: OrderLineTable,
        users: UserTable,
    ) -> Unit,
) {

    val orders = OrderTable
    val orderDetails = OrderDetailTable
    val items = ItemTable
    val orderLines = OrderLineTable
    val users = UserTable

    withTables(testDB, *OrderSchema.allOrderTables) {
        val order1 = Order.new {
            orderDate = LocalDate.of(2017, 1, 17)
        }
        val orderDetail1 = OrderDetail.new {
            order = order1
            lineNumber = 1
            description = "Tennis Ball"
            quantity = 3
        }
        val orderDetail2 = OrderDetail.new {
            order = order1
            lineNumber = 2
            description = "Tennis Racket"
            quantity = 1
        }

        val order2 = Order.new {
            orderDate = LocalDate.of(2017, 1, 18)
        }
        val orderDetail3 = OrderDetail.new {
            order = order2
            lineNumber = 1
            description = "Football"
            quantity = 2
        }

        val item1 = Item.new(22) {
            description = "Helmet"
        }
        val item2 = Item.new(33) {
            description = "First Base Glove"
        }
        val item3 = Item.new(44) {
            description = "Outfield Glove"
        }
        val item4 = Item.new(55) {
            description = "Catcher Glove"
        }

        val orderLine1 = OrderLine.new {
            order = order1
            item = item1
            lineNumber = 1
            quantity = 1
        }
        val orderLine2 = OrderLine.new {
            order = order1
            item = item2
            lineNumber = 2
            quantity = 1
        }
        val orderLine3 = OrderLine.new {
            order = order2
            item = item1
            lineNumber = 1
            quantity = 1
        }
        val orderLine4 = OrderLine.new {
            order = order2
            item = item3
            lineNumber = 2
            quantity = 1
        }
        val orderLine5 = OrderLine.new {
            order = order2
            item = null
            lineNumber = 3
            quantity = 6
        }

        val fred = User.new {
            userName = "Fred"
        }
        val barney = User.new {
            userName = "Barney"
        }
        User.new {
            userName = "Pebbles"
            parent = fred
        }
        User.new {
            userName = "Bamm Bamm"
            parent = barney
        }

        flushCache()

        statement(orders, orderDetails, items, orderLines, users)
    }
}
