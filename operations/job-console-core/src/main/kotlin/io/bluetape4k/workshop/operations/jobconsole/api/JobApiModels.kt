package io.bluetape4k.workshop.operations.jobconsole.api

import com.fasterxml.jackson.annotation.JsonValue
import io.bluetape4k.workshop.operations.jobconsole.domain.JobProblemCode
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import java.io.Serializable
import java.time.Instant
import java.util.UUID

enum class JobType(
    @get:JsonValue val wireValue: String,
) {
    DOCUMENT_EXPORT("document_export"),
    REPORT_GENERATION("report_generation"),
}

enum class FailureMode(
    @get:JsonValue val wireValue: String,
) {
    NONE("none"),
    RETRY_ONCE("retry_once"),
    NON_RETRYABLE("non_retryable"),
    ALWAYS_RETRYABLE("always_retryable"),
}

enum class EtaConfidence(
    @get:JsonValue val wireValue: String,
) {
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

enum class JobEventType(
    @get:JsonValue val wireValue: String,
) {
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
