package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * Ktor adapter의 request boundary가 Spring adapter와 같은 scope/key 우선순위,
 * strict JSON, body limit, status mapping을 유지하는지 live test application으로 검증한다.
 */
class JobConsoleKtorSubmissionHttpTest {

    companion object: KLogging()

    @Test
    /** 정상 request와 malformed/oversized/unauthorized request의 HTTP 상태 계약을 한 흐름에서 확인한다. */
    /** 정상 request와 malformed/oversized/unauthorized request의 HTTP 상태 계약을 한 흐름에서 확인한다. */
    fun `strict bounded submission parser is used before coordinator`() = testApplication {
        application {
            routing {
                post("/parse") {
                    try {
/**
 * scope은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                        val scope = JobConsoleKtorSubmissionHttp.scope(call)
/**
 * key은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * valid은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val valid = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("""{"jobType":"document_export","workUnits":3,"failureMode":"none"}""")
        }
        valid.status shouldBeEqualTo HttpStatusCode.OK
        valid.bodyAsText() shouldBeEqualTo "tenant-a:safe-key"

/**
 * duplicate은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val duplicate = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("""{"jobType":"document_export","jobType":"report_generation","workUnits":3}""")
        }
        duplicate.status shouldBeEqualTo HttpStatusCode.BadRequest

/**
 * tooLarge은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val tooLarge = client.post("/parse") {
            headers()
            contentType(ContentType.Application.Json)
            setBody("{" + "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES + 1) + "}")
        }
        tooLarge.status shouldBeEqualTo HttpStatusCode.PayloadTooLarge

/**
 * keyWinsOverBody은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val keyWinsOverBody = client.post("/parse") {
            header("Idempotency-Key", "bad,key")
            header("X-Demo-Tenant", "tenant-a")
            header("X-Demo-Submitter", "submitter-a")
            contentType(ContentType.Application.Json)
            setBody("{" + "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES + 1) + "}")
        }
        keyWinsOverBody.status shouldBeEqualTo HttpStatusCode.BadRequest

/**
 * denied은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
