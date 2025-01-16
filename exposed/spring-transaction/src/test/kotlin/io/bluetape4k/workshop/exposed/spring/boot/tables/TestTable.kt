package io.bluetape4k.workshop.exposed.spring.boot.tables

import org.jetbrains.exposed.dao.id.IntIdTable

object TestTable: IntIdTable("test_table") {
    val name = varchar("name", 100)
}
