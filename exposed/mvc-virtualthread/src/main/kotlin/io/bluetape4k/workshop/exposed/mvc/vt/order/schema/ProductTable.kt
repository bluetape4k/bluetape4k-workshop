package io.bluetape4k.workshop.exposed.mvc.vt.order.schema

import org.jetbrains.exposed.v1.core.Table

object ProductTable : Table("products") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 200)
    val price = decimal("price", 12, 2)
    val stock = integer("stock")
    override val primaryKey = PrimaryKey(id)
}
