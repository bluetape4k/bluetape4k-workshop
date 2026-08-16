package io.bluetape4k.workshop.operations.jobconsole.idempotency

/** Low-cardinality outcomes emitted by the coordinator; request data never enters a tag. */
internal enum class JobSubmissionObservationOutcome {
    OWNER,
    REPLAY,
    CONFLICT,
    TIMEOUT,
    OVERFLOW,
    ABANDON,
}

internal fun interface JobSubmissionIdempotencyObservability {
    fun record(outcome: JobSubmissionObservationOutcome)
}

internal object NoopJobSubmissionIdempotencyObservability : JobSubmissionIdempotencyObservability {
    override fun record(outcome: JobSubmissionObservationOutcome) = Unit
}
