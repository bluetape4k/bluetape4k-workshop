package io.bluetape4k.workshop.ktor.routes

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotNull
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

// extension function 은 companion object 를 가질 수 없으므로 file-private holder 를 사용합니다.
private object BookRoutesLog : KLoggingChannel()

/**
 * [Application] 에 모든 book catalog route 를 등록합니다.
 *
 * ## Routes
 * - `GET  /books`             — 모든 book 조회
 * - `GET  /books/{id}`        — id 로 book 조회
 * - `POST /books`             — book 생성(201 Created)
 * - `PUT  /books/{id}`        — book 갱신
 * - `DELETE /books/{id}`      — book 삭제(204 No Content)
 * - `GET  /books/export`      — NDJSON export(application/x-ndjson)
 * - `GET  /books/stream`      — SSE live stream
 *
 * ## Behavior / Contract
 * - double-prefix `/books/books/stream` 을 피하려고 `sse("/books/stream")` 은 `route("/books")` 내부가 아니라 **top-level routing scope** 에 등록합니다.
 * - SSE collect block 은 broad catch 전에 [CancellationException] 을 다시 throw 합니다.
 * - NDJSON export 는 [respondBytesWriter] 를 통해 [Jackson3Support.writeNdjson] 를 사용하며 `withContext` 는 필요 없습니다.
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
                val id = call.parameters["id"].requireNotNull("id")
                call.respond(service.get(id))
            }

            post {
                val book = call.receive<Book>()
                val created = service.create(book)
                call.respond(HttpStatusCode.Created, created)
            }

            put("/{id}") {
                val id = call.parameters["id"].requireNotNull("id")
                val book = call.receive<Book>()
                call.respond(service.update(id, book))
            }

            delete("/{id}") {
                val id = call.parameters["id"].requireNotNull("id")
                service.delete(id)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // 중요: sse("/books/stream") 은 top-level routing scope 에 둡니다. route("/books") { } 내부가 아닙니다.
        // bare sse { } 는 의도한 path 가 아니라 application root 에 mount 됩니다.
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
