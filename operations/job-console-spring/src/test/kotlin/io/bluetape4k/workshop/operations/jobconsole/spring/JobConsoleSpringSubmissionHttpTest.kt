package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.FailureMode
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Spring MVC adapter가 body를 읽기 전에 caller scope와 idempotency key를 검증하고,
 * strict JSON/UTF-8/64KiB 경계를 core 계약과 동일하게 적용하는지 확인한다.
 */
class JobConsoleSpringSubmissionHttpTest {

    companion object: KLogging()

    @Test
    /** 잘못된 key는 대용량 또는 malformed body를 읽기 전에 거부되어 비용을 제한한다. */
    /** 잘못된 key는 대용량 또는 malformed body를 읽기 전에 거부되어 비용을 제한한다. */
    fun `scope and key are validated before bounded JSON parsing`() {
        val request = request("{}", key = "bad,key")

        assertFailsWith<JobSubmissionInvalidRequestException> {
            JobConsoleSpringSubmissionHttp.idempotencyKey(request)
        }
    }

    @Test
    /** 정상 request가 허용된 enum과 workUnits로 파싱되고 UTF-8 content type을 요구한다. */
    /** 정상 request가 허용된 enum과 workUnits로 파싱되고 UTF-8 content type을 요구한다. */
    fun `valid request uses strict JSON and UTF-8 content type`() {
        val request = request(
            body = """{"jobType":"document_export","workUnits":3,"failureMode":"none"}""",
            key = "spring-key",
        )

        val parsed = JobConsoleSpringSubmissionHttp.readSubmitRequest(request)
        parsed.jobType shouldBeEqualTo JobType.DOCUMENT_EXPORT
        parsed.workUnits shouldBeEqualTo 3
        parsed.failureMode shouldBeEqualTo FailureMode.NONE
    }

    @Test
    /** duplicate/unknown/trailing JSON token을 받아들이지 않아 canonical digest drift를 막는다. */
    /** duplicate/unknown/trailing JSON token을 받아들이지 않아 canonical digest drift를 막는다. */
    fun `duplicate unknown and trailing JSON are rejected`() {
        listOf(
            """{"jobType":"document_export","jobType":"report_generation","workUnits":3}""",
            """{"jobType":"document_export","workUnits":3,"unexpected":true}""",
            """{"jobType":"document_export","workUnits":3} {}""",
        ).forEach { body ->
            assertFailsWith<JobSubmissionInvalidRequestException> {
                JobConsoleSpringSubmissionHttp.readSubmitRequest(request(body, "spring-key"))
            }
        }
    }

    @Test
    /** 64KiB 초과 body는 별도 problem으로 분류되어 호출자가 무한 retry하지 않게 한다. */
    /** 64KiB 초과 body는 별도 problem으로 분류되어 호출자가 무한 retry하지 않게 한다. */
    fun `body over 64 KiB returns the dedicated size failure`() {
        val body = "{" + "\"jobType\":\"document_export\",\"workUnits\":3,\"failureMode\":\"none\",\"padding\":\"" +
            "x".repeat(MAX_JOB_SUBMISSION_BODY_BYTES) + "\"}"

        assertFailsWith<JobSubmissionRequestTooLargeException> {
            JobConsoleSpringSubmissionHttp.readSubmitRequest(request(body, "spring-key"))
        }
    }

    @Test
    /** caller identity가 없으면 body parsing보다 먼저 scope denial을 반환한다. */
    /** caller identity가 없으면 body parsing보다 먼저 scope denial을 반환한다. */
    fun `missing caller scope is denied before body access`() {
        val request = request("not-json", "spring-key")
        request.removeHeader("X-Demo-Tenant")

        assertFailsWith<JobSubmissionScopeDeniedException> {
            JobConsoleSpringSubmissionHttp.scope(request)
        }
    }

/**
 * request은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(body: String, key: String): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            addHeader("X-Demo-Tenant", "tenant-a")
            addHeader("X-Demo-Submitter", "submitter-a")
            addHeader("Idempotency-Key", key)
            contentType = "application/json; charset=UTF-8"
            setContent(body.toUtf8Bytes())
        }
}
