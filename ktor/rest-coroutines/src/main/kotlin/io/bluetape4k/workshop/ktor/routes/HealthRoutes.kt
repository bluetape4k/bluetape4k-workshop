package io.bluetape4k.workshop.ktor.routes

import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Registers the `/health` liveness endpoint.
 *
 * ## Behavior / Contract
 * - `GET /health` always returns HTTP 200 with body `OK`.
 */
fun Application.healthRoutes() {
    routing {
        get("/health") {
            call.respondText("OK")
        }
    }
}
