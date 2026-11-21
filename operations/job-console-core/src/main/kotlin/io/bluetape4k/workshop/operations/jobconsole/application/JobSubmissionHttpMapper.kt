package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.jackson3.writeAsBytes
import io.bluetape4k.workshop.operations.jobconsole.api.JobProblem
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionHttpResponse
import io.bluetape4k.workshop.operations.jobconsole.api.JobSubmissionOutcome
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode

/** Maps the core submission result to the single wire contract consumed by both adapters. */
/** Maps the core submission result to the single wire contract consumed by both adapters. */
object JobSubmissionHttpMapper {
    /**
     * 기술 구현을 위한 내부 상태이며 업무 데이터·비밀값을 외부 응답에 포함하지 않습니다.
     */
    private val mapper = Jackson.defaultJsonMapper

    /**
     * `map`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = map(outcome = outcome, requestId = "demo")
     * ```
     * @param outcome 요청 경계에서 검증된 `outcome`입니다.
     * @param requestId 요청 경계에서 검증된 `requestId`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun map(
        outcome: JobSubmissionOutcome,
        requestId: String = Uuid.V7.nextId().toString(),
    ): JobSubmissionHttpResponse = when (outcome) {
        is JobSubmissionOutcome.OwnerCompleted ->
            snapshotResponse(
                outcome.snapshot,
                outcome.responseHeaders,
                replayed = false
            )
        is JobSubmissionOutcome.Replayed       ->
            snapshotResponse(
                outcome.snapshot,
                outcome.responseHeaders,
                replayed = true
            )
        JobSubmissionOutcome.Conflict          ->
            problem(
                JobProblemCode.IDEMPOTENCY_KEY_REUSED,
                409,
                "Conflict",
                requestId
            )
        JobSubmissionOutcome.InFlightTimeout   ->
            problem(
                JobProblemCode.IDEMPOTENCY_IN_FLIGHT,
                409,
                "Conflict",
                requestId,
                retryAfterSeconds = 1
            )
        JobSubmissionOutcome.WaiterOverflow    ->
            problem(
                JobProblemCode.IDEMPOTENCY_WAITERS_EXCEEDED,
                429,
                "Too Many Requests",
                requestId,
                retryAfterSeconds = 2
            )
        JobSubmissionOutcome.Abandoned         ->
            problem(
                JobProblemCode.DEPENDENCY_UNAVAILABLE,
                503,
                "Service Unavailable",
                requestId
            )
    }

    /**
     * `problem`은 애플리케이션 공개 계약의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 예:
     * ```kotlin
     * val response = problem(code = code, status = 25, title = "demo", requestId = "demo",
     * retryAfterSeconds = 1L)
     * ```
     * @param code 요청 경계에서 검증된 `code`입니다.
     * @param status 요청 경계에서 검증된 `status`입니다.
     * @param title 요청 경계에서 검증된 `title`입니다.
     * @param requestId 요청 경계에서 검증된 `requestId`입니다.
     * @param retryAfterSeconds 요청 경계에서 검증된 `retryAfterSeconds`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    fun problem(
        code: JobProblemCode,
        status: Int,
        title: String,
        requestId: String = Uuid.V7.nextId().toString(),
        retryAfterSeconds: Long? = null,
    ): JobSubmissionHttpResponse {
        /**
         * `body`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val body = mapper.writeAsBytes(JobProblem(status, code, title, requestId, retryAfterSeconds))
        /**
         * `headers`은 작업 운영 콘솔에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
         *
         * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
         */
        val headers = retryAfterSeconds?.let { mapOf("Retry-After" to listOf(it.toString())) } ?: emptyMap()

        return JobSubmissionHttpResponse(
            status = status,
            body = body!!,
            contentType = "application/problem+json",
            headers = headers,
            replayed = false,
        )
    }

    /**
     * snapshotResponse은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param snapshot 업무 처리에 필요한 snapshot 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param responseHeaders 업무 처리에 필요한 responseHeaders 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param replayed 업무 처리에 필요한 replayed 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun snapshotResponse(
        snapshot: io.bluetape4k.workshop.operations.jobconsole.api.JobSnapshot,
        responseHeaders: Map<String, List<String>>,
        replayed: Boolean,
    ): JobSubmissionHttpResponse =
        JobSubmissionHttpResponse(
            status = 202,
            body = mapper.writeValueAsBytes(snapshot),
            contentType = "application/json",
            headers = responseHeaders + ("Idempotency-Replayed" to listOf(replayed.toString())),
            replayed = replayed,
        )
}
