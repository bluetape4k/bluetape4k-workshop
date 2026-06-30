package io.bluetape4k.workshop.ktor.exposedrest

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

internal object Books: LongIdTable("ktor_exposed_books") {
    val title = varchar("title", 160)
    val author = varchar("author", 120)
    val isbn = varchar("isbn", 64).uniqueIndex()
}

internal object BookRepository {

    fun resetSchema() {
        SchemaUtils.drop(Books)
        SchemaUtils.create(Books)
    }

    fun create(request: BookRequest): BookResponse {
        val validated = request
        val id = Books.insertAndGetId { row ->
            row[title] = validated.title
            row[author] = validated.author
            row[isbn] = validated.isbn
        }
        return findById(id.value) ?: error("Inserted book ${id.value} was not found")
    }

    fun list(): List<BookResponse> =
        Books
            .selectAll()
            .orderBy(Books.id to SortOrder.ASC)
            .map { it.toBookResponse() }

    fun findById(id: Long): BookResponse? =
        Books
            .selectAll()
            .where { Books.id eq id }
            .singleOrNull()
            ?.toBookResponse()

    fun update(id: Long, request: BookRequest): BookResponse? {
        val validated = request
        val updated = Books.update({ Books.id eq id }) { row ->
            row[title] = validated.title
            row[author] = validated.author
            row[isbn] = validated.isbn
        }
        return if (updated == 0) null else findById(id)
    }

    fun delete(id: Long): Boolean =
        Books.deleteWhere { Books.id eq id } > 0

    fun createThenFail(request: BookRequest): Nothing {
        create(request)
        error("Simulated failure after insert for rollback demonstration")
    }
}

internal fun initializeBookSchema(resources: KtorExposedRestResources) {
    transaction(db = resources.jdbcDatabase) {
        BookRepository.resetSchema()
    }
}

private fun ResultRow.toBookResponse(): BookResponse =
    BookResponse(
        id = this[Books.id].value,
        title = this[Books.title],
        author = this[Books.author],
        isbn = this[Books.isbn],
    )
