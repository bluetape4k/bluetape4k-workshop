package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsBatchTransportException
import io.bluetape4k.aws.spring.sns.SnsPublishBatchEntry
import io.bluetape4k.aws.spring.sns.SnsPublishBatchRequest
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import tools.jackson.databind.ObjectMapper

/**
 * 주문 알림을 위한 SNS 발행과 SQS 코루틴 소비를 조율합니다.
 */
@Service
class OrderNotificationMessagingService(
    private val properties: SqsSnsMessagingProperties,
    private val sns: SnsOperations,
    private val sqs: SqsOperations,
    private val handler: OrderNotificationHandler,
    private val objectMapper: ObjectMapper,
    private val metrics: OrderNotificationMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 설정된 SNS 토픽으로 주문 알림 이벤트를 발행합니다.
     */
    suspend fun publish(request: OrderNotificationRequest): OrderNotificationPublishReport {
        validate(request)
        validateProperties()

        val event = eventFrom(request)
        val payload = objectMapper.writeValueAsString(event)

        return try {
            val response = metrics.recordPublish {
                sns.publish(
                    SnsPublishRequest(
                        topicArn = properties.topicArn,
                        subject = properties.subject,
                        message = payload,
                        messageAttributes = messageAttributes(event),
                    )
                )
            }
            OrderNotificationPublishReport.published(properties.topicArn, response.messageId(), event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OrderNotificationPublishReport.failed(properties.topicArn, event, e)
        }
    }

    /**
     * 최대 10개의 주문 알림을 SNS PublishBatch로 발행합니다.
     *
     * AWS의 entry별 성공·실패를 하나의 성공으로 축약하지 않습니다. 전송 또는
     * 응답 protocol 오류가 발생하면 자동 재시도하지 않고, 이미 완료된 entry와
     * 응답을 받지 못한 entry를 별도로 남겨 호출자가 idempotency 정책을 결정하게 합니다.
     */
    suspend fun publishBatch(
        requests: List<OrderNotificationRequest>,
    ): OrderNotificationBatchPublishReport {
        validateProperties()
        require(requests.isNotEmpty()) { "requests must not be empty." }
        require(requests.size <= SNS_BATCH_SIZE) {
            "requests must contain at most $SNS_BATCH_SIZE entries."
        }

        requests.forEach(::validate)
        val events = requests.map(::eventFrom)
        val eventsById = events.associateBy { it.idempotencyKey }
        val batchRequest = SnsPublishBatchRequest(
            topicArn = properties.topicArn,
            entries = events.map { event ->
                SnsPublishBatchEntry(
                    id = event.idempotencyKey,
                    message = objectMapper.writeValueAsString(event),
                    subject = properties.subject,
                    messageAttributes = messageAttributes(event),
                )
            },
        )

        return try {
            val result = metrics.recordPublish {
                sns.publishBatch(batchRequest)
            }
            batchReport(result, eventsById)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SnsBatchTransportException) {
            transportFailureReport(events, e)
        } catch (e: Exception) {
            transportFailureReport(events, e)
        }
    }

    /**
     * SQS를 한 번 폴링하고 전달된 각 알림을 처리합니다.
     */
    suspend fun consumeOnce(): List<OrderNotificationConsumeReport> {
        validateProperties()
        val messages = try {
            sqs.receive(
                queueUrl = properties.queueUrl,
                maxMessages = properties.maxMessages,
                waitTimeSeconds = properties.waitTimeSeconds,
                visibilityTimeoutSeconds = properties.visibilityTimeoutSeconds,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return listOf(
                OrderNotificationConsumeReport(
                    state = ConsumeState.FAILED,
                    messageId = null,
                    receiptHandle = "",
                    orderId = null,
                    idempotencyKey = null,
                    correlationId = null,
                    receiveCount = 0,
                    message = e.message ?: e::class.java.simpleName,
                )
            )
        }

        return messages.map { processMessage(it) }
    }

    private suspend fun processMessage(message: SqsReceivedMessage): OrderNotificationConsumeReport {
        val receiveCount = message.approximateReceiveCount ?: 1
        val event = try {
            decode(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return reportMalformed(message, receiveCount, e)
        }

        if (receiveCount >= properties.maxReceiveCount) {
            return metrics.recordConsume(OrderNotificationMetrics.RESULT_DEAD_LETTER) {
                sqs.delete(message.queueUrl, message.receiptHandle)
                report(message, event, receiveCount, ConsumeState.DEAD_LETTER, "max receive count reached")
            }
        }

        try {
            handler.handle(event)
        } catch (e: CancellationException) {
            return metrics.recordConsume(OrderNotificationMetrics.RESULT_CANCELLED) {
                throw e
            }
        } catch (e: Exception) {
            return metrics.recordConsume(OrderNotificationMetrics.RESULT_RETRY) {
                sqs.changeVisibility(message.queueUrl, message.receiptHandle, timeoutSeconds = 0)
                report(
                    message = message,
                    event = event,
                    receiveCount = receiveCount,
                    state = ConsumeState.RETRY_REQUESTED,
                    statusMessage = e.message ?: e::class.java.simpleName,
                )
            }
        }

        return metrics.recordConsume(OrderNotificationMetrics.RESULT_ACKED) {
            sqs.delete(message.queueUrl, message.receiptHandle)
            report(message, event, receiveCount, ConsumeState.ACKED, "acked")
        }
    }

    private fun validate(request: OrderNotificationRequest) {
        request.orderId.requireNotBlank("orderId")
        request.customerId.requireNotBlank("customerId")
        request.message.requireNotBlank("message")
        request.idempotencyKey.requireNotBlank("idempotencyKey")
        request.correlationId.requireNotBlank("correlationId")
    }

    private fun batchReport(
        result: io.bluetape4k.aws.spring.sns.SnsPublishBatchResult,
        eventsById: Map<String, OrderNotificationEvent>,
    ): OrderNotificationBatchPublishReport {
        val successful = result.successful.map { success ->
            val event = requireNotNull(eventsById[success.entryId])
            OrderNotificationBatchEntryReport(
                orderId = event.orderId,
                idempotencyKey = event.idempotencyKey,
                correlationId = event.correlationId,
                messageId = success.messageId,
                message = "published",
            )
        }
        val failed = result.failed.map { failure ->
            val event = requireNotNull(eventsById[failure.entryId])
            OrderNotificationBatchEntryReport(
                orderId = event.orderId,
                idempotencyKey = event.idempotencyKey,
                correlationId = event.correlationId,
                code = failure.code,
                senderFault = failure.senderFault,
                message = failure.message ?: "publish failed",
            )
        }
        val state = when {
            failed.isEmpty() -> BatchPublishState.PUBLISHED
            successful.isEmpty() -> BatchPublishState.FAILED
            else -> BatchPublishState.PARTIAL_FAILURE
        }
        return OrderNotificationBatchPublishReport(
            state = state,
            topicArn = properties.topicArn,
            successful = successful,
            failed = failed,
            message = when (state) {
                BatchPublishState.PUBLISHED -> "published"
                BatchPublishState.PARTIAL_FAILURE -> "partial failure"
                BatchPublishState.FAILED -> "all entries failed"
            },
        )
    }

    private fun transportFailureReport(
        events: List<OrderNotificationEvent>,
        error: Throwable,
    ): OrderNotificationBatchPublishReport {
        val completedEntryIds = (error as? SnsBatchTransportException)?.completedEntryIds.orEmpty()
        val allEntryIds = events.map { it.idempotencyKey }
        return OrderNotificationBatchPublishReport(
            state = BatchPublishState.FAILED,
            topicArn = properties.topicArn,
            successful = emptyList(),
            failed = emptyList(),
            completedEntryIds = completedEntryIds,
            unresolvedEntryIds = allEntryIds.filterNot(completedEntryIds::contains),
            message = error.message ?: error::class.java.simpleName,
        )
    }

    private fun validateProperties() {
        properties.topicArn.requireNotBlank("topicArn")
        properties.queueUrl.requireNotBlank("queueUrl")
        properties.subject.requireNotBlank("subject")
        properties.maxMessages.requireInRange(1, 10, "maxMessages")
        properties.waitTimeSeconds.requireInRange(0, 20, "waitTimeSeconds")
        properties.visibilityTimeoutSeconds.requireZeroOrPositiveNumber("visibilityTimeoutSeconds")
        properties.maxReceiveCount.requirePositiveNumber("maxReceiveCount")
    }

    private fun eventFrom(request: OrderNotificationRequest): OrderNotificationEvent =
        OrderNotificationEvent(
            orderId = request.orderId.trim(),
            customerId = request.customerId.trim(),
            eventType = request.eventType,
            message = request.message.trim(),
            idempotencyKey = request.idempotencyKey.trim(),
            correlationId = request.correlationId.trim(),
            publishedAt = clock.instant().toString(),
        )

    private fun decode(message: SqsReceivedMessage): OrderNotificationEvent =
        objectMapper.readValue(message.body, OrderNotificationEvent::class.java)

    private suspend fun reportMalformed(
        message: SqsReceivedMessage,
        receiveCount: Int,
        error: Exception,
    ): OrderNotificationConsumeReport {
        val statusMessage = error.message ?: error::class.java.simpleName
        return if (receiveCount >= properties.maxReceiveCount) {
            metrics.recordConsume(OrderNotificationMetrics.RESULT_DEAD_LETTER) {
                sqs.delete(message.queueUrl, message.receiptHandle)
                malformedReport(message, receiveCount, ConsumeState.DEAD_LETTER, statusMessage)
            }
        } else {
            metrics.recordConsume(OrderNotificationMetrics.RESULT_RETRY) {
                sqs.changeVisibility(message.queueUrl, message.receiptHandle, timeoutSeconds = 0)
                malformedReport(message, receiveCount, ConsumeState.RETRY_REQUESTED, statusMessage)
            }
        }
    }

    private fun messageAttributes(event: OrderNotificationEvent): Map<String, MessageAttributeValue> =
        mapOf(
            "correlationId" to stringAttribute(event.correlationId),
            "idempotencyKey" to stringAttribute(event.idempotencyKey),
            "eventType" to stringAttribute(event.eventType.name),
        )

    private fun stringAttribute(value: String): MessageAttributeValue =
        MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build()

    private fun report(
        message: SqsReceivedMessage,
        event: OrderNotificationEvent,
        receiveCount: Int,
        state: ConsumeState,
        statusMessage: String,
    ): OrderNotificationConsumeReport =
        OrderNotificationConsumeReport(
            state = state,
            messageId = message.messageId,
            receiptHandle = message.receiptHandle,
            orderId = event.orderId,
            idempotencyKey = event.idempotencyKey,
            correlationId = event.correlationId,
            receiveCount = receiveCount,
            message = statusMessage,
        )

    private fun malformedReport(
        message: SqsReceivedMessage,
        receiveCount: Int,
        state: ConsumeState,
        statusMessage: String,
    ): OrderNotificationConsumeReport =
        OrderNotificationConsumeReport(
            state = state,
            messageId = message.messageId,
            receiptHandle = message.receiptHandle,
            orderId = null,
            idempotencyKey = null,
            correlationId = null,
            receiveCount = receiveCount,
            message = statusMessage,
        )
}

private const val SNS_BATCH_SIZE: Int = 10
