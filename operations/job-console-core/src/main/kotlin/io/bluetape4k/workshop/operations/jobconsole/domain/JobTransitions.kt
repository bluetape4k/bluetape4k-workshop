package io.bluetape4k.workshop.operations.jobconsole.domain

/**
 * JobTransitions은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object JobTransitions {

    /**
     * 현재 상태와 signal을 검증해 다음 durable 상태를 계산합니다.
     *
     * `expectedRevision`이 현재 revision과 다르면 상태를 평가하기 전에
     * `STALE_REVISION` 예외를 던집니다. 따라서 오래된 worker가 유효한 signal을
     * 보냈더라도 새 worker의 결과를 덮어쓸 수 없습니다.
     * 예:
     * ```kotlin
     * JobTransitions.next(JobState.RUNNING, JobSignal.RETRYABLE_FAILURE) == JobState.QUEUED
     * JobTransitions.next(JobState.RUNNING, JobSignal.SUCCESS) == JobState.SUCCEEDED
     * ```
     */
    fun next(
        state: JobState,
        signal: JobSignal,
        currentRevision: Long = 0,
        expectedRevision: Long = currentRevision,
    ): JobState {
        if (expectedRevision != currentRevision) {
            throw InvalidJobTransition(
                JobProblemCode.STALE_REVISION,
                "Expected revision $expectedRevision but current revision is $currentRevision",
            )
        }
        if (state.terminal) {
            throw invalid(state, signal)
        }
        return when (state) {
            JobState.QUEUED ->
                when (signal) {
                    JobSignal.CLAIM -> JobState.RUNNING
                    JobSignal.CANCEL -> JobState.CANCELLED
                    else -> throw invalid(state, signal)
                }

            JobState.RUNNING ->
                when (signal) {
                    JobSignal.CANCEL -> JobState.CANCEL_REQUESTED
                    JobSignal.SUCCESS -> JobState.SUCCEEDED
                    JobSignal.RETRYABLE_FAILURE -> JobState.QUEUED
                    JobSignal.RETRY_EXHAUSTED -> JobState.DEAD_LETTERED
                    JobSignal.NON_RETRYABLE_FAILURE -> JobState.FAILED
                    else -> throw invalid(state, signal)
                }

            JobState.CANCEL_REQUESTED ->
                when (signal) {
                    JobSignal.CHECKPOINT, JobSignal.CANCEL -> JobState.CANCELLED
                    else -> throw invalid(state, signal)
                }

            JobState.SUCCEEDED,
            JobState.FAILED,
            JobState.DEAD_LETTERED,
            JobState.CANCELLED,
            -> throw invalid(state, signal)
        }
    }

    /**
     * invalid은 운영 작업 콘솔의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param state 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param signal 업무 처리에 필요한 signal 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    private fun invalid(state: JobState, signal: JobSignal): InvalidJobTransition =
        InvalidJobTransition(
            JobProblemCode.INVALID_TRANSITION,
            "Signal $signal is not valid from state ${state.wireValue}",
        )
}
