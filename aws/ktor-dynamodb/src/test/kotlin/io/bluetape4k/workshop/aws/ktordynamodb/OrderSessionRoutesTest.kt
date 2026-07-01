package io.bluetape4k.workshop.aws.ktordynamodb

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class OrderSessionRoutesTest {

    @Test
    fun `POST order sessions creates a session`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService())
            }

            val response = client.post("/dynamodb/order-sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":"order-1001","customerId":"customer-42","notes":"new order"}""")
            }

            response.status shouldBeEqualTo HttpStatusCode.Created
            response.bodyAsText() shouldContain "order-1001"
        }
    }

    @Test
    fun `POST order sessions maps malformed JSON to the documented error code`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService())
            }

            val response = client.post("/dynamodb/order-sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"id":""")
            }

            response.status shouldBeEqualTo HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain OrderSessionErrorCode.MALFORMED_JSON.code
        }
    }

    @Test
    fun `POST order sessions rejects oversized bodies before JSON decoding`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService())
            }

            val response = client.post("/dynamodb/order-sessions") {
                contentType(ContentType.Application.Json)
                setBody("x".repeat((ORDER_SESSION_REQUEST_BODY_LIMIT_BYTES + 1).toInt()))
            }

            response.status shouldBeEqualTo HttpStatusCode.PayloadTooLarge
            response.bodyAsText() shouldContain OrderSessionErrorCode.REQUEST_TOO_LARGE.code
        }
    }

    @Test
    fun `GET order sessions rejects non numeric limit`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService())
            }

            val response = client.get("/dynamodb/order-sessions?limit=abc")

            response.status shouldBeEqualTo HttpStatusCode.BadRequest
            response.bodyAsText() shouldContain OrderSessionErrorCode.VALIDATION_FAILED.code
            response.bodyAsText() shouldContain "limit must be a number"
        }
    }

    @Test
    fun `GET readiness returns service status`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService())
            }

            val response = client.get("/health/readiness")

            response.status shouldBeEqualTo HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\": \"UP\""
        }
    }

    @Test
    fun `GET readiness maps a down table to the documented error code`() = runSuspendIO {
        testApplication {
            application {
                installOrderSessionHttpPlugins()
                orderSessionRoutes(FakeOrderSessionService(tableReady = false))
            }

            val response = client.get("/health/readiness")

            response.status shouldBeEqualTo HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain OrderSessionErrorCode.DYNAMODB_NOT_READY.code
            response.bodyAsText() shouldContain "test-order-sessions"
        }
    }

    private class FakeOrderSessionService(
        private val tableReady: Boolean = true,
    ) : OrderSessionService {
        override suspend fun create(request: CreateOrderSessionRequest): OrderSessionResponse =
            OrderSessionResponse(
                id = request.id,
                customerId = request.customerId,
                status = request.status,
                notes = request.notes,
                version = 1L,
            )

        override suspend fun findById(id: String): OrderSessionResponse =
            error("findById is not used by this route slice")

        override suspend fun list(limit: Int?, nextToken: String?): OrderSessionListResponse =
            OrderSessionListResponse(items = emptyList())

        override suspend fun update(id: String, request: UpdateOrderSessionRequest): OrderSessionResponse =
            error("update is not used by this route slice")

        override suspend fun delete(id: String) {
            error("delete is not used by this route slice")
        }

        override suspend fun readiness(): ReadinessResponse =
            ReadinessResponse(
                status = if (tableReady) "UP" else "DOWN",
                mode = "LOCAL",
                emulator = "FLOCI",
                region = "ap-northeast-2",
                tableName = "test-order-sessions",
                tableReady = tableReady,
                checkedAt = "2026-07-01T00:00:00Z",
            )
    }
}
