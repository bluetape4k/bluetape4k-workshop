package io.bluetape4k.workshop.aws.sqssns

import java.io.Serializable

/**
 * SQS/SNS 워크숍에서 사용하는 도메인 이벤트 종류입니다.
 */
enum class OrderNotificationType {
    ORDER_PLACED,
    PAYMENT_CAPTURED,
    SHIPMENT_READY,
}

/**
 * 학습자 대상 서비스 API가 받는 발행 명령입니다.
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
 * SNS로 발행하고 SQS에서 소비하는 JSON 페이로드입니다.
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
 * SNS 발행 경계 상태입니다.
 */
enum class PublishState {
    PUBLISHED,
    FAILED,
}

/**
 * SQS 소비 결과 분류입니다.
 */
enum class ConsumeState {
    ACKED,
    RETRY_REQUESTED,
    DEAD_LETTER,
    FAILED,
}

/**
 * SNS 발행 시도 뒤 반환하는 보고서입니다.
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

/** SNS 배치 발행 전체 결과의 상태입니다. */
enum class BatchPublishState {
    PUBLISHED,
    PARTIAL_FAILURE,
    FAILED,
}

/** SNS 배치 응답에서 한 entry를 호출자에게 전달하는 bounded 결과입니다. */
data class OrderNotificationBatchEntryReport(
    val orderId: String,
    val idempotencyKey: String,
    val correlationId: String,
    val messageId: String? = null,
    val code: String? = null,
    val senderFault: Boolean? = null,
    val message: String,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** SNS PublishBatch의 성공·부분 실패·전송 실패 경계입니다. */
data class OrderNotificationBatchPublishReport(
    val state: BatchPublishState,
    val topicArn: String,
    val successful: List<OrderNotificationBatchEntryReport>,
    val failed: List<OrderNotificationBatchEntryReport>,
    val completedEntryIds: List<String> = emptyList(),
    val unresolvedEntryIds: List<String> = emptyList(),
    val message: String,
) : Serializable {
    val isFullySuccessful: Boolean
        get() = state == BatchPublishState.PUBLISHED

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SQS 메시지 하나를 처리한 뒤 반환하는 보고서입니다.
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
