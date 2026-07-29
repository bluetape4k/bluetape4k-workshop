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
 * ContentNegotiation 과 BookRoutes 의 SSE encoding 에서 함께 사용하는 shared [Json] instance 입니다.
 *
 * `BookRoutes.kt` 가 `import io.bluetape4k.workshop.ktor.AppJson` 형태로 import 할 수 있도록 `internal` visibility 를 사용합니다.
 */
internal val AppJson = Json {
    prettyPrint = false
    isLenient = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 모든 plugin, status page, route 를 포함해 Ktor [Application] 을 구성합니다.
 *
 * ## Behavior / Contract
 * - plugin install order 는 [CallLogging] → [ContentNegotiation] → [SSE] → [StatusPages] 입니다.
 * - StatusPages handler 는 specific 에서 general 순서로 등록하며, [Throwable] catch-all 은 마지막입니다.
 * - [repository] 와 [jackson3] 는 test 를 위해 injectable 하게 둡니다.
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
        // 구체적인 domain error 를 먼저 처리합니다.
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
        // catch-all 은 반드시 마지막이어야 하며 "type":"Internal" 을 포함해야 합니다.
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
