package io.bluetape4k.workshop.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.ktor.domain.DomainError
import io.bluetape4k.workshop.ktor.json.Jackson3Support
import io.bluetape4k.workshop.ktor.repository.BookRepository
import io.bluetape4k.workshop.ktor.repository.InMemoryBookRepository
import io.bluetape4k.workshop.ktor.routes.bookRoutes
import io.bluetape4k.workshop.ktor.routes.healthRoutes
import io.bluetape4k.workshop.ktor.service.BookService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

private object ApplicationModuleLog : KLogging()

/**
 * Shared [Json] instance used by ContentNegotiation and SSE encoding in BookRoutes.
 *
 * `internal` visibility so `BookRoutes.kt` can import it as
 * `import io.bluetape4k.workshop.ktor.AppJson`.
 */
internal val AppJson = Json {
    prettyPrint = false
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Configures the Ktor [Application] with all plugins, status pages, and routes.
 *
 * ## Behavior / Contract
 * - Plugin install order: [CallLogging] → [ContentNegotiation] → [SSE] → [StatusPages].
 * - StatusPages handlers are registered from specific to general; [Throwable] catch-all is last.
 * - [repository] and [jackson3] are injectable for testing.
 *
 * ```kotlin
 * embeddedServer(Netty, port = 8080) { module() }.start(wait = true)
 * ```
 */
fun Application.module(
    repository: BookRepository = InMemoryBookRepository(),
    jackson3: Jackson3Support = Jackson3Support(),
) {
    val service = BookService(repository)

    install(CallLogging)

    install(ContentNegotiation) {
        json(AppJson)
    }

    install(SSE)

    install(StatusPages) {
        // Specific domain errors first
        exception<DomainError.NotFound> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to cause.message, "type" to "NotFound"),
            )
        }
        exception<DomainError.Conflict> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to cause.message, "type" to "Conflict"),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Bad request"), "type" to "BadRequest"),
            )
        }
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.message ?: "Bad request"), "type" to "BadRequest"),
            )
        }
        // Catch-all must be last and must include "type":"Internal"
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to "Internal server error", "type" to "Internal"),
            )
        }
    }

    healthRoutes()
    bookRoutes(service, jackson3)
}
