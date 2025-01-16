package io.bluetape4k.workshop.exposed.spring.boot.tables.ignore

import org.jetbrains.exposed.dao.id.IntIdTable

object IgnoreTable: IntIdTable("ignore_table") {
    val name = varchar("name", 100)
}
