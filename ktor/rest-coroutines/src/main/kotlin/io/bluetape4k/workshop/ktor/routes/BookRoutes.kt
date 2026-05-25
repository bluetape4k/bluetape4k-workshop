package io.bluetape4k.workshop.ktor.routes

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.ktor.AppJson
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.json.Jackson3Support
import io.bluetape4k.workshop.ktor.service.BookService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException

// Extension functions cannot have companion objects — use a file-private holder.
private object BookRoutesLog : KLoggingChannel()

/**
 * Registers all book catalog routes on the [Application].
 *
 * ## Routes
 * - `GET  /books`             — list all books
 * - `GET  /books/{id}`        — get book by id
 * - `POST /books`             — create book (201 Created)
 * - `PUT  /books/{id}`        — update book
 * - `DELETE /books/{id}`      — delete book (204 No Content)
 * - `GET  /books/export`      — NDJSON export (application/x-ndjson)
 * - `GET  /books/stream`      — SSE live stream
 *
 * ## Behavior / Contract
 * - `sse("/books/stream")` is registered at the **top-level routing scope**, NOT inside
 *   `route("/books")`, to avoid the double-prefix `/books/books/stream`.
 * - SSE collect block rethrows [CancellationException] before any broad catch.
 * - NDJSON export uses [Jackson3Support.writeNdjson] via [respondBytesWriter] — no `withContext` needed.
 */
fun Application.bookRoutes(service: BookService, jackson3: Jackson3Support) {
    routing {
        route("/books") {
            get {
                call.respond(service.list())
            }

            get("/export") {
                val books = service.list()
                call.respondBytesWriter(contentType = ContentType("application", "x-ndjson")) {
                    jackson3.writeNdjson(this, books)
                }
            }

            get("/{id}") {
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id parameter")
                call.respond(service.get(id))
            }

            post {
                val book = call.receive<Book>()
                val created = service.create(book)
                call.respond(HttpStatusCode.Created, created)
            }

            put("/{id}") {
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id parameter")
                val book = call.receive<Book>()
                call.respond(service.update(id, book))
            }

            delete("/{id}") {
                val id = call.parameters["id"] ?: throw IllegalArgumentException("Missing id parameter")
                service.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // CRITICAL: sse("/books/stream") at top-level routing scope — NOT inside route("/books") { }
        // A bare sse { } would mount at application root instead of the intended path.
        sse("/books/stream") {
            BookRoutesLog.log.debug { "SSE client connected to /books/stream" }
            try {
                service.stream().collect { book ->
                    try {
                        send(ServerSentEvent(data = AppJson.encodeToString(book)))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        BookRoutesLog.log.warn(e) { "SSE send failed for book ${book.id}" }
                    }
                }
            } catch (e: CancellationException) {
                BookRoutesLog.log.debug { "SSE client disconnected from /books/stream" }
                throw e
            }
        }
    }
}
