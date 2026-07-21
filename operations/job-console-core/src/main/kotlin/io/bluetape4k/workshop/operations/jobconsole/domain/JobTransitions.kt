package io.bluetape4k.workshop.operations.jobconsole.domain

object JobTransitions {

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

    private fun invalid(state: JobState, signal: JobSignal): InvalidJobTransition =
        InvalidJobTransition(
            JobProblemCode.INVALID_TRANSITION,
            "Signal $signal is not valid from state ${state.wireValue}",
        )
}
