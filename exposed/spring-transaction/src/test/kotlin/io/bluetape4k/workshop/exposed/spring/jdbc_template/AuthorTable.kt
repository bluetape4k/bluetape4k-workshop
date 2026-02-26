package io.bluetape4k.workshop.exposed.spring.jdbc_template

import io.bluetape4k.exposed.core.dao.id.TimebasedUUIDTable
import io.bluetape4k.exposed.dao.entityToStringBuilder
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntity
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityClass
import io.bluetape4k.exposed.dao.id.TimebasedUUIDEntityID
import io.bluetape4k.exposed.dao.idEquals
import io.bluetape4k.exposed.dao.idHashCode

/**
 * ```sql
 * CREATE TABLE IF NOT EXISTS AUTHORS (
 *      ID UUID PRIMARY KEY,
 *      DESCRIPTION TEXT NOT NULL
 * );
 * ```
 */
object AuthorTable: TimebasedUUIDTable("authors") {
    val description = text("description")
}

/**
 * ```sql
 * CREATE TABLE IF NOT EXISTS BOOKS (
 *      ID UUID PRIMARY KEY,
 *      DESCRIPTION TEXT NOT NULL
 * );
 * ```
 */
object BookTable: TimebasedUUIDTable("books") {
    val description = text("description")
}

class Author(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<Author>(AuthorTable)

    var description by AuthorTable.description

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = entityToStringBuilder()
        .add("description", description)
        .toString()
}

class Book(id: TimebasedUUIDEntityID): TimebasedUUIDEntity(id) {
    companion object: TimebasedUUIDEntityClass<Book>(BookTable)

    var description by BookTable.description

    override fun equals(other: Any?): Boolean = idEquals(other)
    override fun hashCode(): Int = idHashCode()
    override fun toString(): String = entityToStringBuilder()
        .add("description", description)
        .toString()
}
