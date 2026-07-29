package io.bluetape4k.workshop.ktor.routes

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * `/health` liveness endpoint 를 등록합니다.
 *
 * ## Behavior / Contract
 * - `GET /health` 는 항상 HTTP 200 과 body `OK` 를 반환합니다.
 */
fun Application.healthRoutes() {
    routing {
        get("/health") {
            call.respondText("OK")
        }
    }
}
