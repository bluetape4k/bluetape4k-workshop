package io.bluetape4k.workshop.operations.jobconsole.domain

import com.fasterxml.jackson.annotation.JsonValue
import java.time.Instant
import java.util.UUID

enum class JobState(
    @get:JsonValue val wireValue: String,
) {
    QUEUED("queued"),
    RUNNING("running"),
    CANCEL_REQUESTED("cancel_requested"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    DEAD_LETTERED("dead_lettered"),
    CANCELLED("cancelled"),
    ;

    val terminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == DEAD_LETTERED || this == CANCELLED

    val active: Boolean
        get() = this == RUNNING || this == CANCEL_REQUESTED
}

enum class JobSignal {
    CLAIM,
    CANCEL,
    CHECKPOINT,
    SUCCESS,
    RETRYABLE_FAILURE,
    RETRY_EXHAUSTED,
    NON_RETRYABLE_FAILURE,
}

enum class JobProblemCode(
    @get:JsonValue val wireValue: String,
) {
    VALIDATION_FAILED("validation_failed"),
    IDEMPOTENCY_KEY_REUSED("idempotency_key_reused"),
    JOB_NOT_FOUND("job_not_found"),
    SCOPE_DENIED("scope_denied"),
    INVALID_TRANSITION("invalid_transition"),
    STALE_REVISION("stale_revision"),
    DEPENDENCY_UNAVAILABLE("dependency_unavailable"),
    LEASE_LOST("lease_lost"),
}

data class JobLease(
    val jobId: UUID,
    val token: UUID,
    val attempt: Int,
    val expiresAt: Instant,
    val revision: Long,
)

class InvalidJobTransition(
    val code: JobProblemCode,
    message: String,
) : IllegalStateException(message)
