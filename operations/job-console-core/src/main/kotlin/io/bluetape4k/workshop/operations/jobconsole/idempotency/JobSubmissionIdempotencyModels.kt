package io.bluetape4k.workshop.operations.jobconsole.idempotency

import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import java.time.Instant
import java.util.UUID

/** The canonical command passed from an HTTP boundary to the idempotency coordinator. */
internal data class JobSubmissionCommand(
    val scope: DemoCallerScope,
    val keyHash: String,
    val requestFingerprint: String,
    val request: SubmitJobRequest,
    val policyFingerprint: String,
)

/** Server-owned lease identity. Client-supplied tokens must never be accepted here. */
internal data class JobSubmissionOwnership(
    val scope: DemoCallerScope,
    val keyHash: String,
    val generation: Long,
    val jobId: UUID,
    val ownerToken: UUID,
    val leaseExpiresAt: Instant,
)

/** Response bytes prepared by an owner before the terminal snapshot is committed. */
internal data class PreparedJobSubmission(
    val request: SubmitJobRequest,
    val responseStatus: Int = 202,
    val responseBody: ByteArray,
    val responseContentType: String = "application/json",
    val responseHeaders: Map<String, List<String>> = emptyMap(),
)

/** Immutable response snapshot that can be replayed without re-running the job. */
internal data class ReplayableJobSubmission(
    val jobId: UUID,
    val enqueueSequence: Long,
    val responseStatus: Int,
    val responseBody: ByteArray,
    val responseContentType: String,
    val responseHeaders: Map<String, List<String>>,
)

internal sealed interface JobSubmissionOutcome {
    data class OwnerCompleted(val snapshot: ReplayableJobSubmission) : JobSubmissionOutcome
    data class Replayed(val snapshot: ReplayableJobSubmission) : JobSubmissionOutcome
    data object Conflict : JobSubmissionOutcome
    data object InFlightTimeout : JobSubmissionOutcome
    data object WaiterOverflow : JobSubmissionOutcome
    data object Abandoned : JobSubmissionOutcome
}

internal sealed interface Reservation {
    data class Owner(val ownership: JobSubmissionOwnership) : Reservation
    /** Existing in-flight owner plus the transaction's database-authoritative timestamp. */
    data class Wait(
        val ownership: JobSubmissionOwnership,
        val databaseNow: Instant,
    ) : Reservation
    data class Replay(val snapshot: ReplayableJobSubmission) : Reservation
    data object Conflict : Reservation
    data object Overflow : Reservation
    data object Abandoned : Reservation
}

internal data class InFlightOwnership(val ownership: JobSubmissionOwnership)

internal sealed interface WaiterRegistration {
    data class Registered(val waiterToken: UUID, val generation: Long) : WaiterRegistration
    data object Overflow : WaiterRegistration
    data object DeadlineExceeded : WaiterRegistration
}

internal sealed interface PollResult {
    data class Terminal(val snapshot: ReplayableJobSubmission) : PollResult
    data class Abandoned(val generation: Long) : PollResult
    data object StillInFlight : PollResult
}

internal enum class AbandonReason {
    PREPARE_FAILED,
    PREPARE_DEADLINE,
    CANCELLED,
    OWNER_DISCONNECTED,
}

internal data class CleanupReport(
    val waitersDeleted: Int,
    val requestsDeleted: Int,
)
