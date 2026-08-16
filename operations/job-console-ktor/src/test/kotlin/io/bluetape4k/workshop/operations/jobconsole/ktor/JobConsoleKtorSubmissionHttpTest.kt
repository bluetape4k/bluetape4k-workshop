package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class JobConsoleKtorSubmissionHttpTest {
    @Test
    fun `strict bounded submission parser is used before coordinator`() = testApplication {
        application {
            routing {
                post("/parse") {
                    try {
                        val scope = JobConsoleKtorSubmissionHttp.scope(call)
                        val key = JobConsoleKtorSubmissionHttp.idempotencyKey(call)
                        JobConsoleKtorSubmissionHttp.readSubmitRequest(call)
                        call.respondText("${scope.tenantId}:$key")
                    } catch (failure: KtorJobSubmissionScopeDeniedException) {
                        call.respondText("scope_denied", status = HttpStatusCode.Forbidden)
                    } catch (failure: KtorJobSubmissionRequestTooLargeException) {
                        call.respondText("too_large", status = HttpStatusCode.PayloadTooLarge)
                    } catch (failure: KtorJobSubmissionInvalidRequestException) {
                        call.respondText("invalid", status = HttpStatusCode.BadRequest)
                    }
                }
            }
        }

        val valid = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("""{"jobType":"document_export","workUnits":3,"failureMode":"none"}""")
        }
        valid.status shouldBeEqualTo HttpStatusCode.OK
        valid.bodyAsText() shouldBeEqualTo "tenant-a:safe-key"

        val duplicate = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("""{"jobType":"document_export","jobType":"report_generation","workUnits":3}""")
        }
        duplicate.status shouldBeEqualTo HttpStatusCode.BadRequest

        val tooLarge = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("{" + "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES + 1) + "}")
        }
        tooLarge.status shouldBeEqualTo HttpStatusCode.PayloadTooLarge

        val keyWinsOverBody = client.post("/parse") {
            header("Idempotency-Key", "bad,key")
            header("X-Demo-Tenant", "tenant-a")
            header("X-Demo-Submitter", "submitter-a")
            contentType(ContentType.Application.Json)
            setBody("{" + "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES + 1) + "}")
        }
        keyWinsOverBody.status shouldBeEqualTo HttpStatusCode.BadRequest

        val denied = client.post("/parse") {
            header("Idempotency-Key", "safe-key")
            contentType(ContentType.Application.Json)
            setBody("not-json")
        }
        denied.status shouldBeEqualTo HttpStatusCode.Forbidden
    }

    private fun io.ktor.client.request.HttpRequestBuilder.headers() {
        header("X-Demo-Tenant", "tenant-a")
        header("X-Demo-Submitter", "submitter-a")
        header("Idempotency-Key", "safe-key")
    }
}
