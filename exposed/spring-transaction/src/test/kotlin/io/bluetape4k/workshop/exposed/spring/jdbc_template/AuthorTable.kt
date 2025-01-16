package io.bluetape4k.workshop.exposed.spring.jdbc_template

import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID
import io.bluetape4k.exposed.dao.id.TimebasedUUIDTable

object AuthorTable: TimebasedUUIDTable("authors") {
    val description = text("description")
}

object BookTable: TimebasedUUIDTable("books") {
    val description = text("description")
}

class Book(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<Book>(AuthorTable)

    var description by AuthorTable.description
}
