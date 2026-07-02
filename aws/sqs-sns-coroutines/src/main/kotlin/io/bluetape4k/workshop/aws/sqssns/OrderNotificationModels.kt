package io.bluetape4k.workshop.aws.sqssns

import java.io.Serializable

/**
 * Domain event kinds used by the SQS/SNS workshop.
 */
enum class OrderNotificationType {
    ORDER_PLACED,
    PAYMENT_CAPTURED,
    SHIPMENT_READY,
}

/**
 * Publish command accepted from the learner-facing service API.
 */
data class OrderNotificationRequest(
    val orderId: String,
    val customerId: String,
    val eventType: OrderNotificationType,
    val message: String,
    val idempotencyKey: String,
    val correlationId: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -7798180989410879962L
    }
}

/**
 * JSON payload published to SNS and consumed from SQS.
 */
data class OrderNotificationEvent(
    val orderId: String,
    val customerId: String,
    val eventType: OrderNotificationType,
    val message: String,
    val idempotencyKey: String,
    val correlationId: String,
    val publishedAt: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -8325777112842256462L
    }
}

/**
 * Publish boundary state for SNS.
 */
enum class PublishState {
    PUBLISHED,
    FAILED,
}

/**
 * SQS consume result classification.
 */
enum class ConsumeState {
    ACKED,
    RETRY_REQUESTED,
    DEAD_LETTER,
    FAILED,
}

/**
 * Report returned after an SNS publish attempt.
 */
data class OrderNotificationPublishReport(
    val state: PublishState,
    val topicArn: String,
    val messageId: String?,
    val idempotencyKey: String,
    val correlationId: String,
    val message: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = -3818962872050825752L

        fun published(
            topicArn: String,
            messageId: String?,
            event: OrderNotificationEvent,
        ): OrderNotificationPublishReport =
            OrderNotificationPublishReport(
                state = PublishState.PUBLISHED,
                topicArn = topicArn,
                messageId = messageId,
                idempotencyKey = event.idempotencyKey,
                correlationId = event.correlationId,
                message = "published",
            )

        fun failed(
            topicArn: String,
            event: OrderNotificationEvent,
            error: Throwable,
        ): OrderNotificationPublishReport =
            OrderNotificationPublishReport(
                state = PublishState.FAILED,
                topicArn = topicArn,
                messageId = null,
                idempotencyKey = event.idempotencyKey,
                correlationId = event.correlationId,
                message = error.message ?: error::class.java.simpleName,
            )
    }
}

/**
 * Report returned after processing one SQS message.
 */
data class OrderNotificationConsumeReport(
    val state: ConsumeState,
    val messageId: String?,
    val receiptHandle: String,
    val orderId: String?,
    val idempotencyKey: String?,
    val correlationId: String?,
    val receiveCount: Int,
    val message: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 5757992933073737377L
    }
}
