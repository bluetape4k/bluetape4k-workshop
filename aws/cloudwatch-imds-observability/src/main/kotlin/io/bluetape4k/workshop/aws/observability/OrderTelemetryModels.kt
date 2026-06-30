package io.bluetape4k.workshop.aws.observability

import java.io.Serializable

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

enum class TelemetryOutcome(val tagValue: String) {
    SUCCESS("success"),
    FAILURE("failure"),
}

enum class PublishState {
    PUBLISHED,
    FAILED,
    SKIPPED,
}

data class PublishStatus(
    val state: PublishState,
    val message: String = "",
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 8352173228614796523L

        fun published(message: String = ""): PublishStatus =
            PublishStatus(PublishState.PUBLISHED, message)

        fun failed(error: Throwable): PublishStatus =
            PublishStatus(PublishState.FAILED, error.message ?: error::class.simpleName.orEmpty())

        fun skipped(message: String = ""): PublishStatus =
            PublishStatus(PublishState.SKIPPED, message)
    }
}

data class MetadataSnapshot(
    val state: PublishState,
    val instanceId: String = "",
    val region: String = "",
    val availabilityZone: String = "",
    val message: String = "",
): Serializable {

    companion object {
        private const val serialVersionUID: Long = -3970765835872248833L

        fun skipped(): MetadataSnapshot =
            MetadataSnapshot(PublishState.SKIPPED, message = "metadata lookup disabled")

        fun failed(error: Throwable): MetadataSnapshot =
            MetadataSnapshot(PublishState.FAILED, message = error.message ?: error::class.simpleName.orEmpty())
    }
}

data class TelemetryErrorResponse(
    val error: String,
): Serializable {

    companion object {
        private const val serialVersionUID: Long = 2403698664729277661L
    }
}
