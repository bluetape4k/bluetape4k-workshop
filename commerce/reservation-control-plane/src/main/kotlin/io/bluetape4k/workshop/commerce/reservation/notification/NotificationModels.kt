package io.bluetape4k.workshop.commerce.reservation.notification

import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.io.Serializable

internal enum class NotificationChannel {
    IN_APP,
}

internal enum class NotificationDeliveryStatus {
    PENDING,
    IN_FLIGHT,
    RETRYING,
    DELIVERED,
    EXHAUSTED,
}

internal enum class NotificationFailureCode {
    FAKE_TRANSIENT,
}

internal data class NotificationRequest(
    val deliveryId: String,
    val channel: NotificationChannel,
    val templateCode: String,
    val aggregateId: String,
) : Serializable {
    init {
        deliveryId.requireNotBlank("deliveryId")
        templateCode.requireNotBlank("templateCode")
        aggregateId.requireNotBlank("aggregateId")
    }

    companion object { private const val serialVersionUID = 1L }
}

internal data class NotificationDelivery(
    val deliveryId: String,
    val channel: NotificationChannel,
    val templateCode: String,
    val aggregateId: String,
    val status: NotificationDeliveryStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val claimOwner: String?,
    val claimUntil: Instant?,
    val failureCode: NotificationFailureCode?,
) : Serializable {
    companion object { private const val serialVersionUID = 1L }
}

internal enum class FinalizeDisposition {
    APPLIED,
    NOT_FOUND,
    STALE_CLAIM,
    TERMINAL,
}

internal enum class RedriveDisposition {
    APPLIED,
    ALREADY_APPLIED,
    NOT_FOUND,
    NOT_EXHAUSTED,
}

internal sealed interface ProviderSendResult {
    data object Accepted : ProviderSendResult
    data object Duplicate : ProviderSendResult
    data class RetryableFailure(val code: NotificationFailureCode) : ProviderSendResult, Serializable {
        companion object { private const val serialVersionUID = 1L }
    }
}
