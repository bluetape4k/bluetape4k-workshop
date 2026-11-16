package io.bluetape4k.workshop.operations.jobconsole.api

import com.fasterxml.jackson.annotation.JsonValue
import io.bluetape4k.ToStringBuilder
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class JobType(@get:JsonValue val wireValue: String) {
    DOCUMENT_EXPORT("document_export"),
    REPORT_GENERATION("report_generation"),
}

enum class FailureMode(@get:JsonValue val wireValue: String) {
    NONE("none"),
    RETRY_ONCE("retry_once"),
    NON_RETRYABLE("non_retryable"),
    ALWAYS_RETRYABLE("always_retryable"),
}

enum class EtaConfidence(@get:JsonValue val wireValue: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    INSUFFICIENT_DATA("insufficient_data"),
}

/** Closed request contract for the deterministic workshop workload. */
data class SubmitJobRequest(
    val jobType: JobType,
    val workUnits: Int,
    val failureMode: FailureMode = FailureMode.NONE,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class TimeRange(
    val earliest: Instant,
    val latest: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class QueueProjection(
    val position: Int,
    val jobsAhead: Int,
    val estimatedStartRange: TimeRange?,
    val estimatedCompletionRange: TimeRange?,
    val confidence: EtaConfidence,
    val sampleSize: Int,
    val queueVersion: Long,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Redacted REST source-of-truth snapshot shared by both HTTP adapters. */
data class JobSnapshot(
    val jobId: UUID,
    val jobType: JobType,
    val state: JobState,
    val progress: Int,
    val checkpoint: Long?,
    val queue: QueueProjection?,
    val version: Long,
    val updatedAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Public result of an idempotent job submission, before an HTTP adapter adds wire details. */
sealed interface JobSubmissionOutcome : Serializable {
    data class OwnerCompleted(
        val snapshot: JobSnapshot,
        val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : JobSubmissionOutcome

    data class Replayed(
        val snapshot: JobSnapshot,
        val responseHeaders: Map<String, List<String>> = emptyMap(),
    ) : JobSubmissionOutcome

    data object Conflict : JobSubmissionOutcome

    data object InFlightTimeout : JobSubmissionOutcome

    data object WaiterOverflow : JobSubmissionOutcome

    data object Abandoned : JobSubmissionOutcome
}

/** Framework-neutral HTTP response assembled from a submission outcome. */
data class JobSubmissionHttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val replayed: Boolean,
) : Serializable {
    init {
        status.requireInRange(100, 599, "status")
        contentType.requireNotBlank("contentType")
    }

    override fun equals(other: Any?): Boolean =
        other is JobSubmissionHttpResponse &&
            status == other.status &&
            body.contentEquals(other.body) &&
            contentType == other.contentType &&
            headers == other.headers &&
            replayed == other.replayed

    override fun hashCode(): Int = hashOf(status, body.contentHashCode(), contentType, headers, replayed)

    override fun toString(): String =
        ToStringBuilder(this)
            .add("status", status)
            .add("bodySize", body.size)
            .add("contentType", contentType)
            .add("headers", headers)
            .add("replayed", replayed)
            .toString()
}

/** Stable, redacted problem payload. */
data class JobProblem(
    val status: Int,
    val code: JobProblemCode,
    val title: String,
    val requestId: String,
    val retryAfterSeconds: Long? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class JobEventType(@get:JsonValue val wireValue: String) {
    JOB_UPDATED("job.updated"),
    QUEUE_UPDATED("queue.updated"),
    HEARTBEAT("heartbeat"),
}

/** Notification-only SSE envelope. Clients refresh the REST snapshot after receiving it. */
data class JobEvent(
    val eventId: UUID,
    val eventType: JobEventType,
    val jobId: UUID,
    val queueVersion: Long,
    val occurredAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
