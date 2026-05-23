package io.bluetape4k.workshop.exposed.webflux.r2dbc.order.schema

import org.jetbrains.exposed.v1.core.Table

object ProductTable : Table("products") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 200)
    val price = decimal("price", 12, 2)
    val stock = integer("stock").default(0)
    override val primaryKey = PrimaryKey(id)
}
