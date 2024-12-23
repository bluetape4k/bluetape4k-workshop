package io.bluetape4k.workshop.exposed.domain.mapping.composite

import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable


// CompositeIdTable with 2 key columns - int & uuid (both db-generated)
object Publishers: CompositeIdTable("publishers") {
    val pubId = integer("pub_id").autoIncrement().entityId()
    val isbn = uuid("isbn_code").autoGenerate().entityId()
    val name = varchar("publisher_name", 32)

    override val primaryKey = PrimaryKey(pubId, isbn)
}

object Authors: IntIdTable("authors") {
    val publisherId = integer("publisher_id")
    val publisherIsbn = uuid("publisher_isbn")
    val penName = varchar("pen_name", 32)

    // FK constraint with multiple columns is created as a table-level constraint
    init {
        foreignKey(publisherId, publisherIsbn, target = Publishers.primaryKey)
    }
}

// CompositeIdTable with 1 key column - int (db-generated)
object Books: CompositeIdTable("books") {
    val bookId = integer("book_id").autoIncrement().entityId()
    val title = varchar("title", 32)
    val author = optReference("author_id", Authors)

    override val primaryKey = PrimaryKey(bookId)
}

// CompositeIdTable with 2 key columns - string & long (neither db-generated)
object Reviews: CompositeIdTable("reviews") {
    val content = varchar("code", 8).entityId()
    val rank = long("rank").entityId()
    val book = integer("book_id")

    override val primaryKey = PrimaryKey(content, rank)

    init {
        foreignKey(book, target = Books.primaryKey)
    }
}

// CompositeIdTable with 3 key columns - string, string, & int (none db-generated)
object Offices: CompositeIdTable("offices") {
    val zipCode = varchar("zip_code", 8).entityId()
    val name = varchar("name", 64).entityId()
    val areaCode = integer("area_code").entityId()
    val staff = long("staff").nullable()
    val publisherId = integer("publisher_id").nullable()
    val publisherIsbn = uuid("publisher_isbn").nullable()

    override val primaryKey = PrimaryKey(zipCode, name, areaCode)

    init {
        foreignKey(publisherId, publisherIsbn, target = Publishers.primaryKey)
    }
}

class Publisher(id: EntityID<CompositeID>): CompositeEntity(id) {
    companion object: CompositeEntityClass<Publisher>(Publishers)

    var name by Publishers.name
    val authors by Author referrersOn Authors
}

class Author(id: EntityID<Int>): IntEntity(id) {
    companion object: IntEntityClass<Author>(Authors)

    var publisher by Publisher referencedOn Authors
    var penName by Authors.penName
}

class Book(id: EntityID<CompositeID>): CompositeEntity(id) {
    companion object: CompositeEntityClass<Book>(Books)

    var title by Books.title
    var author by Author optionalReferencedOn Books.author
    val review by Review backReferencedOn Reviews
}

class Review(id: EntityID<CompositeID>): CompositeEntity(id) {
    companion object: CompositeEntityClass<Review>(Reviews)

    var book by Book referencedOn Reviews
}

class Office(id: EntityID<CompositeID>): CompositeEntity(id) {
    companion object: CompositeEntityClass<Office>(Offices)

    var staff by Offices.staff
    var publisher by Publisher optionalReferencedOn Offices
}
