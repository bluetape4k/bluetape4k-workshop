package io.bluetape4k.workshop.exposed.mvc.vt.order.schema

import org.jetbrains.exposed.v1.core.Table
import java.time.Instant

object OrderTable : Table("orders") {
    val id = long("id").autoIncrement()
    val customerId = long("customer_id")
    val orderDate = long("order_date").clientDefault { Instant.now().toEpochMilli() }
    val status = enumerationByName("status", 20, OrderStatus::class).default(OrderStatus.PENDING)
    override val primaryKey = PrimaryKey(id)
}
