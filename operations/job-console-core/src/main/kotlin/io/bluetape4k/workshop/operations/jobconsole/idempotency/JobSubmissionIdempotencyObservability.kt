package io.bluetape4k.workshop.operations.jobconsole.idempotency

/** Low-cardinality outcomes emitted by the coordinator; request data never enters a tag. */
/** Low-cardinality outcomes emitted by the coordinator; request data never enters a tag. */
internal enum class JobSubmissionObservationOutcome {
    /**
     * `JobSubmissionObservationOutcome.OWNER`은 `owner` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속 처리를
     * 분기합니다.
     */
    OWNER,
    /**
     * `JobSubmissionObservationOutcome.REPLAY`은 `replay` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속
     * 처리를 분기합니다.
     */
    REPLAY,
    /**
     * `JobSubmissionObservationOutcome.CONFLICT`은 현재 revision 또는 업무 상태와 충돌하여 자동 반영할 수 없는 값입니다.
     */
    CONFLICT,
    /**
     * `JobSubmissionObservationOutcome.TIMEOUT`은 `timeout` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속
     * 처리를 분기합니다.
     */
    TIMEOUT,
    /**
     * `JobSubmissionObservationOutcome.OVERFLOW`은 `overflow` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로
     * 후속 처리를
     * 분기합니다.
     */
    OVERFLOW,
    /**
     * `JobSubmissionObservationOutcome.ABANDON`은 `abandon` 업무 상황을 나타냅니다. 성공·재시도·보상·수동 대사 중 하나로 후속
     * 처리를 분기합니다.
     */
    ABANDON,
}

/**
 * JobSubmissionIdempotencyObservability은 운영 작업 콘솔 실무 예제에서 업무 경계와 협력 계약을 표현하는 타입입니다.
 *
 * 입력 검증, 권한·멱등성 확인, 상태 전이, 저장소 확정, 이벤트 발행 사이의 책임을 이 타입의 계약으로 명확히 하여 운영자가 현재 상태와 다음 조치를 추적할 수 있게
 * 합니다.
 */
internal fun interface JobSubmissionIdempotencyObservability {
    /**
     * record은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun record(outcome: JobSubmissionObservationOutcome)
}

/**
 * NoopJobSubmissionIdempotencyObservability은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행
 * 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object NoopJobSubmissionIdempotencyObservability : JobSubmissionIdempotencyObservability {
    /**
     * record은 운영 작업 콘솔 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    override fun record(outcome: JobSubmissionObservationOutcome) = Unit
}
