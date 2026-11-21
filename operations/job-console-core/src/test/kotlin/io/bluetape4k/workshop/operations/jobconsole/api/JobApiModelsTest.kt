package io.bluetape4k.workshop.operations.jobconsole.api

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.jackson3.writeAsString
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * API 모델이 운영자/고객 화면에 노출할 wire 계약을 검증한다.
 * identity와 내부 예외를 redaction하고 두 adapter가 공유할 JSON 필드를
 * 고정하는 테스트 모음이다.
 */
class JobApiModelsTest {

    companion object: KLogging()

/**
 * mapper은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val mapper = Jackson.defaultJsonMapper

    @Test
    /** queue projection은 안정적인 필드만 직렬화하고 caller identity를 누출하지 않는다. */
    /** queue projection은 안정적인 필드만 직렬화하고 caller identity를 누출하지 않는다. */
    fun `snapshot JSON exposes stable queue and ETA fields without caller identity`() {
        val snapshot = JobSnapshot(
            jobId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
            jobType = JobType.DOCUMENT_EXPORT,
            state = JobState.QUEUED,
            progress = 0,
            checkpoint = null,
            queue = QueueProjection(
                position = 3,
                jobsAhead = 2,
                estimatedStartRange = TimeRange(
                    Instant.parse("2026-07-21T00:00:10Z"),
                    Instant.parse("2026-07-21T00:00:20Z")
                ),
                estimatedCompletionRange = TimeRange(
                    Instant.parse("2026-07-21T00:00:30Z"),
                    Instant.parse("2026-07-21T00:00:50Z")
                ),
                confidence = EtaConfidence.MEDIUM,
                sampleSize = 12,
                queueVersion = 7,
                updatedAt = Instant.parse("2026-07-21T00:00:00Z"),
            ),
            version = 1,
            updatedAt = Instant.parse("2026-07-21T00:00:00Z"),
        )

        val tree = mapper.readTree(mapper.writeAsString(snapshot))

        log.debug { "tree=$tree" }
        tree["jobId"].asString() shouldBeEqualTo snapshot.jobId.toString()
        tree["queue"]["jobsAhead"].asInt() shouldBeEqualTo 2
        tree["queue"]["confidence"].asString() shouldBeEqualTo "medium"
        tree.has("tenantId").shouldBeFalse()
        tree.has("submitterId").shouldBeFalse()
        tree.has("idempotencyKey").shouldBeFalse()
    }

    @Test
    /** 문제 응답은 재시도 판단용 stable code만 전달하고 내부 exception detail은 숨긴다. */
    /** 문제 응답은 재시도 판단용 stable code만 전달하고 내부 exception detail은 숨긴다. */
    fun `problem response carries a stable code without internal exception detail`() {
        val problem = JobProblem(
            status = 409,
            code = JobProblemCode.IDEMPOTENCY_KEY_REUSED,
            title = "Idempotency key reused",
            requestId = "request-1",
        )

        val tree = mapper.readTree(mapper.writeAsString(problem))

        log.debug { "tree=$tree" }
        tree["code"].asString() shouldBeEqualTo "idempotency_key_reused"
        tree.has("stackTrace").shouldBeFalse()
        tree.has("exception").shouldBeFalse()
    }

    @Test
    /** 제출 request가 허용된 job type/work unit/failure mode의 닫힌 계약인지 확인한다. */
    /** 제출 request가 허용된 job type/work unit/failure mode의 닫힌 계약인지 확인한다. */
    fun `submit request is a closed deterministic workload contract`() {
        val request = SubmitJobRequest(JobType.DOCUMENT_EXPORT, workUnits = 10, failureMode = FailureMode.NONE)

        request.workUnits shouldBeEqualTo 10
        request.failureMode shouldBeEqualTo FailureMode.NONE
    }
}
