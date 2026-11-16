package io.bluetape4k.workshop.exposed.mvc.jdbc.order.schema

import org.jetbrains.exposed.v1.core.Table

object OrderTable: Table("orders") {
    val id = long("id").autoIncrement()
    val customerId = long("customer_id")
    val orderDate = long("order_date").clientDefault { System.currentTimeMillis() }
    val status = enumerationByName<OrderStatus>("status", 20).default(OrderStatus.PENDING)
    
    override val primaryKey = PrimaryKey(id)
}
