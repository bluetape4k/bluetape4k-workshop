package io.bluetape4k.workshop.ktor.exposedrest

import io.bluetape4k.exposed.ktor.exposedJdbcTransaction
import io.bluetape4k.ktor.core.respondApiError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import java.sql.SQLException

internal fun Route.bookRoutes(resources: KtorExposedRestResources) {
    route("/api/books") {
        post {
            val request = call.receive<BookRequest>().validated()
            val book = call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.create(request)
            }
            call.respond(HttpStatusCode.Created, book)
        }

        get {
            val books = call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.list()
            }
            call.respond(books)
        }

        get("/{id}") {
            val id = call.requireBookId()
            val book = call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.findById(id)
            } ?: return@get call.respondBookNotFound(id)
            call.respond(book)
        }

        put("/{id}") {
            val id = call.requireBookId()
            val request = call.receive<BookRequest>().validated()
            val book = call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.update(id, request)
            } ?: return@put call.respondBookNotFound(id)
            call.respond(book)
        }

        delete("/{id}") {
            val id = call.requireBookId()
            val deleted = call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.delete(id)
            }
            if (!deleted) {
                return@delete call.respondBookNotFound(id)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/rollback") {
            val request = call.receive<BookRequest>().validated()
            call.exposedJdbcTransaction(
                db = resources.jdbcDatabase,
                blockingDispatcher = resources.jdbcDispatcher,
            ) {
                BookRepository.createThenFail(request)
            }
        }
    }

    route("/api/failures") {
        get("/sql") {
            throw SQLException(
                "jdbc:postgresql://db.internal:5432/secret; user=workshop password=top-secret; " +
                    "select * from secret_books"
            )
        }

        get("/cancelled") {
            throw CancellationException("client disconnected during PostgreSQL request")
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.requireBookId(): Long =
    requireNotNull(parameters["id"]?.toLongOrNull()) {
        "id must be a numeric path parameter"
    }

private suspend fun io.ktor.server.application.ApplicationCall.respondBookNotFound(id: Long) {
    respondApiError(
        status = HttpStatusCode.NotFound,
        error = "not_found",
        message = "Book $id was not found",
    )
}
