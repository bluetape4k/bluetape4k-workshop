package io.bluetape4k.workshop.aws.ktordynamodb

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

internal fun Application.orderSessionRoutes(service: OrderSessionService) {
    routing {
        post("/dynamodb/order-sessions") {
            val request = call.receive<CreateOrderSessionRequest>()
            call.respond(HttpStatusCode.Created, service.create(request))
        }

        get("/dynamodb/order-sessions") {
            val limit = call.optionalListLimit()
            val nextToken = call.request.queryParameters["nextToken"]
            call.respond(service.list(limit = limit, nextToken = nextToken))
        }

        get("/dynamodb/order-sessions/{id}") {
            call.respond(service.findById(call.requiredId()))
        }

        put("/dynamodb/order-sessions/{id}") {
            val request = call.receive<UpdateOrderSessionRequest>()
            call.respond(service.update(id = call.requiredId(), request = request))
        }

        delete("/dynamodb/order-sessions/{id}") {
            service.delete(call.requiredId())
            call.respond(HttpStatusCode.NoContent)
        }

        get("/health/readiness") {
            val readiness = service.readiness()
            if (readiness.status == "UP") {
                call.respond(HttpStatusCode.OK, readiness)
            } else {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ErrorResponse(
                        code = OrderSessionErrorCode.DYNAMODB_NOT_READY.code,
                        message = "DynamoDB table '${readiness.tableName}' is not ready.",
                    ),
                )
            }
        }
    }
}

private fun ApplicationCall.requiredId(): String =
    parameters["id"] ?: throw OrderSessionValidationException("id must be present.")

private fun ApplicationCall.optionalListLimit(): Int? {
    val rawLimit = request.queryParameters["limit"] ?: return null

    return rawLimit.toIntOrNull()
        ?: throw OrderSessionValidationException("limit must be a number.")
}
