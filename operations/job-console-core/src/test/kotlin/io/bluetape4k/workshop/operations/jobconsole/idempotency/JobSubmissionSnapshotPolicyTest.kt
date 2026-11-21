package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import org.junit.jupiter.api.Test

/** replay snapshot이 안전한 status/content type/header/body만 저장하도록 fail closed하는지 검증한다. */
/** replay snapshot이 안전한 status/content type/header/body만 저장하도록 fail closed하는지 검증한다. */
class JobSubmissionSnapshotPolicyTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "empty response header set is the only default replay header contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `empty response header set is the only default replay header contract`() {
        val prepared = prepared(body = "accepted".toUtf8Bytes())
        val validated = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy()).validate(prepared)

        log.debug { "validated=$validated" }
        validated.responseStatus shouldBeEqualTo 202
        validated.responseContentType shouldBeEqualTo "application/json"
        validated.responseHeaders shouldBeEqualTo emptyMap()
        validated.responseBody shouldBeEqualTo "accepted".toUtf8Bytes()
    }

    @Test
/**
 * 이 시나리오는 "sensitive reserved demo and arbitrary headers are rejected" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `sensitive reserved demo and arbitrary headers are rejected`() {
        val headers = listOf(
            mapOf("authorization" to listOf("Bearer secret")),
            mapOf("x-auth-token" to listOf("secret")),
            mapOf("client-secret" to listOf("secret")),
            mapOf("X-Demo-Id" to listOf("demo")),
            mapOf("x-safe" to listOf("value")),
        )

        headers.forEach { responseHeaders ->
            assertFailsWith<IllegalArgumentException> {
                JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy()).validate(
                    prepared(responseHeaders = responseHeaders),
                )
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "status content type body count and control character bounds fail before persistence" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `status content type body count and control character bounds fail before persistence`() {
        val policy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy(maxReplayBytes = 4))

        assertFailsWith<IllegalArgumentException> {
            JobSubmissionIdempotencyPolicy(statementTimeout = java.time.Duration.ofNanos(1))
        }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(responseStatus = 99)) }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(body = "12345".toUtf8Bytes())) }
        assertFailsWith<IllegalArgumentException> { policy.validate(prepared(contentType = "text/plain")) }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-demo" to listOf("line\nfeed"))))
        }
        assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = (1..9).associate { "x-safe-$it" to listOf("value") }))
        }
    }

    @Test
/**
 * 이 시나리오는 "production snapshots are 202 json while synthetic fixtures may use bounded statuses" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `production snapshots are 202 json while synthetic fixtures may use bounded statuses`() {
        val production = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy())
        assertFailsWith<IllegalArgumentException> {
            production.validate(prepared(responseStatus = 201))
        }
        assertFailsWith<IllegalArgumentException> {
            production.validate(prepared(responseStatus = 422, contentType = "application/problem+json"))
        }

        val synthetic = JobSubmissionSnapshotPolicy.syntheticForTests(JobSubmissionIdempotencyPolicy())

        synthetic.validate(prepared(responseStatus = 201))
        synthetic.validate(prepared(responseStatus = 422, contentType = "application/problem+json"))
        assertFailsWith<IllegalArgumentException> {
            synthetic.validate(prepared(responseStatus = 200))
        }
    }

    @Test
/**
 * 이 시나리오는 "header value control and size bounds are checked before replay allowlist" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `header value control and size bounds are checked before replay allowlist`() {
        val policy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy())

        val control = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("line\nfeed"))))
        }
        control.message shouldBeEqualTo "response header value contains a control character"

        val count = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to (1..5).map { "value" })))
        }
        count.message shouldBeEqualTo "response header value count exceeds replay limit"

        val value = assertFailsWith<IllegalArgumentException> {
            policy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("x".repeat(4 * 1024 + 1)))))
        }
        value.message shouldBeEqualTo "response header value exceeds replay limit"

        val aggregatePolicy = JobSubmissionSnapshotPolicy(JobSubmissionIdempotencyPolicy(maxAggregateHeaderBytes = 8))
        val aggregate = assertFailsWith<IllegalArgumentException> {
            aggregatePolicy.validate(prepared(responseHeaders = mapOf("x-safe" to listOf("value"))))
        }
        aggregate.message shouldBeEqualTo "response headers exceed aggregate replay limit"
    }

/**
 * prepared은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun prepared(
        responseStatus: Int = 202,
        body: ByteArray = "ok".toUtf8Bytes(),
        contentType: String = "application/json",
        responseHeaders: Map<String, List<String>> = emptyMap(),
    ): PreparedJobSubmission =
        PreparedJobSubmission(
            request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1),
            responseStatus = responseStatus,
            responseBody = body,
            responseContentType = contentType,
            responseHeaders = responseHeaders,
        )
}
