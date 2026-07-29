package io.bluetape4k.workshop.optimization.planning.domain

import java.io.Serializable
import java.util.UUID

@JvmInline
internal value class DatasetId(val value: String): Serializable {
    init {
        require(value.isNotBlank()) { "datasetId must not be blank" }
        require(value.length <= 160) { "datasetId is too long" }
    }
}

@JvmInline
internal value class AggregateId(val value: String): Serializable {
    init {
        require(value.isNotBlank()) { "aggregateId must not be blank" }
        require(value.length <= 160) { "aggregateId is too long" }
    }
}

@JvmInline
internal value class ProviderRequestId(val value: String): Serializable {
    init {
        require(value.isNotBlank()) { "providerRequestId must not be blank" }
        require(value.length <= 200) { "providerRequestId is too long" }
    }
}

@JvmInline
internal value class PlanningRevision(val value: Long): Serializable {
    init {
        require(value >= 0) { "planning revision must not be negative" }
    }
}

internal data class AggregateVersion(
    val aggregateId: AggregateId,
    val version: Long,
): Serializable {
    init {
        require(version >= 0) { "aggregate version must not be negative" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PlanningSubmission(
    val requestId: UUID,
    val datasetId: DatasetId,
    val aggregate: AggregateVersion,
    val parentRevision: PlanningRevision?,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal enum class PlanningStatus {
    QUEUED,
    SUBMITTED,
    SOLVING,
    SUCCEEDED,
    FAILED,
}

internal data class PlanningSubmissionResult(
    val providerRequestId: ProviderRequestId,
    val status: PlanningStatus,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class PlanningResult(
    val requestId: UUID,
    val providerRequestId: ProviderRequestId,
    val revision: PlanningRevision,
    val status: PlanningStatus,
    val scoreSummary: String,
    val constraintExplanations: List<String>,
): Serializable {
    init {
        require(scoreSummary.length <= 160) { "scoreSummary is too long" }
        require(constraintExplanations.size <= 20) { "too many constraint explanations" }
        require(constraintExplanations.all { it.length <= 240 }) { "constraint explanation is too long" }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal enum class PlanningProvider {
    FAKE,
    TIMEFOLD_PLATFORM,
    CUSTOM_SOLVER,
}
