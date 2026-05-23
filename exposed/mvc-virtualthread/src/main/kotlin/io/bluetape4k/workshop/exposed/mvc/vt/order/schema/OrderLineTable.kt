package io.bluetape4k.workshop.exposed.mvc.vt.order.schema

import org.jetbrains.exposed.v1.core.Table

object OrderLineTable : Table("order_lines") {
    val id = long("id").autoIncrement()
    val orderId = long("order_id").references(OrderTable.id)
    val productId = long("product_id").references(ProductTable.id)
    val quantity = integer("quantity")
    val unitPrice = decimal("unit_price", 12, 2)
    override val primaryKey = PrimaryKey(id)
}
