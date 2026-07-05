package io.bluetape4k.workshop.aws.observability

import java.io.Serializable

/**
 * Request body for publishing a local order telemetry event.
 */
data class OrderTelemetryRequest(
    val eventId: String = "local-order",
    val outcome: TelemetryOutcome = TelemetryOutcome.SUCCESS,
    val message: String = "accepted",
    val includeMetadata: Boolean = false,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8240441594879902134L
    }
}

/**
 * Report showing which observability boundaries accepted the event.
 */
data class OrderTelemetryReport(
    val outcome: TelemetryOutcome,
    val metric: PublishStatus,
    val logs: PublishStatus,
    val meterSnapshot: PublishStatus,
    val metadata: MetadataSnapshot,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8682049839979115067L
    }
}

/**
 * Business outcome used as the metric tag value.
 */
enum class TelemetryOutcome(val tagValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
}

/**
 * Local publish state for a single AWS boundary.
 */
enum class PublishState {
    PUBLISHED,
    FAILED,
    SKIPPED,
}

/**
 * Publish result for CloudWatch metrics, logs, or meter snapshots.
 */
data class PublishStatus(
    val state: PublishState,
    val message: String = "",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 8352173228614796523L

        /**
         * Builds a published status.
         */
        fun published(message: String = ""): PublishStatus =
            PublishStatus(PublishState.PUBLISHED, message)

        /**
         * Builds a failed status from the underlying boundary exception.
         */
        fun failed(error: Throwable): PublishStatus =
            PublishStatus(PublishState.FAILED, error.message ?: error::class.simpleName.orEmpty())

        /**
         * Builds a skipped status for disabled or dependent boundaries.
         */
        fun skipped(message: String = ""): PublishStatus =
            PublishStatus(PublishState.SKIPPED, message)
    }
}

/**
 * Safe subset of EC2 metadata exposed by the workshop.
 */
data class MetadataSnapshot(
    val state: PublishState,
    val instanceId: String = "",
    val region: String = "",
    val availabilityZone: String = "",
    val message: String = "",
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -3970765835872248833L

        /**
         * Builds a skipped metadata snapshot when metadata lookup is disabled.
         */
        fun skipped(): MetadataSnapshot =
            MetadataSnapshot(PublishState.SKIPPED, message = "metadata lookup disabled")

        /**
         * Builds a failed metadata snapshot from the IMDS boundary exception.
         */
        fun failed(error: Throwable): MetadataSnapshot =
            MetadataSnapshot(PublishState.FAILED, message = error.message ?: error::class.simpleName.orEmpty())
    }
}

/**
 * Error payload returned by the REST controller advice.
 */
data class TelemetryErrorResponse(
    val error: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 2403698664729277661L
    }
}
