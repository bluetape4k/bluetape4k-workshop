package io.bluetape4k.workshop.exposed.mvc.vt.author.repository

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.BookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.dto.CreateBookRequest
import io.bluetape4k.workshop.exposed.mvc.vt.author.mapper.toBookDTO
import io.bluetape4k.workshop.exposed.mvc.vt.author.schema.BookTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository
import java.util.concurrent.ExecutorService

@Repository
class BookRepository(
    private val db: Database,
    private val executor: ExecutorService,
) {
    companion object : KLogging()

    fun findAll(): VirtualFuture<List<BookDTO>> = virtualFuture(executor) {
        transaction(db) {
            BookTable.selectAll().map { it.toBookDTO() }
        }
    }

    fun findById(id: Long): VirtualFuture<BookDTO?> = virtualFuture(executor) {
        transaction(db) {
            BookTable.selectAll()
                .where { BookTable.id eq id }
                .singleOrNull()
                ?.toBookDTO()
        }
    }

    fun findByAuthorId(authorId: Long): VirtualFuture<List<BookDTO>> = virtualFuture(executor) {
        transaction(db) {
            BookTable.selectAll()
                .where { BookTable.authorId eq authorId }
                .map { it.toBookDTO() }
        }
    }

    fun insert(req: CreateBookRequest): VirtualFuture<BookDTO> = virtualFuture(executor) {
        transaction(db) {
            val newId = BookTable.insert {
                it[title] = req.title
                it[publishDate] = req.publishDate
                it[this.authorId] = req.authorId
            }[BookTable.id]
            BookTable.selectAll()
                .where { BookTable.id eq newId }
                .single()
                .toBookDTO()
        }
    }
}
