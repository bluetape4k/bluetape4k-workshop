package io.bluetape4k.workshop.exposed.spring.boot.tables.ignore

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

object IgnoreTable: IntIdTable("ignore_table") {
    val name = varchar("name", 100)
}
