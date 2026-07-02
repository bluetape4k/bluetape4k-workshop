package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.support.requireNotBlank
import org.springframework.stereotype.Service
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import tools.jackson.databind.ObjectMapper

/**
 * Coordinates SNS publishing and SQS coroutine consumption for order notifications.
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
     * Publishes an order notification event to the configured SNS topic.
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
     * Polls SQS once and processes each delivered notification.
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

        return try {
            metrics.recordConsume(OrderNotificationMetrics.RESULT_ACKED) {
                handler.handle(event)
                sqs.delete(message.queueUrl, message.receiptHandle)
                report(message, event, receiveCount, ConsumeState.ACKED, "acked")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            metrics.recordConsume(OrderNotificationMetrics.RESULT_RETRY) {
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
    }

    private fun validate(request: OrderNotificationRequest) {
        request.orderId.requireNotBlank("orderId")
        request.customerId.requireNotBlank("customerId")
        request.message.requireNotBlank("message")
        request.idempotencyKey.requireNotBlank("idempotencyKey")
        request.correlationId.requireNotBlank("correlationId")
    }

    private fun validateProperties() {
        properties.topicArn.requireNotBlank("topicArn")
        properties.queueUrl.requireNotBlank("queueUrl")
        properties.subject.requireNotBlank("subject")
        require(properties.maxMessages in 1..10) { "maxMessages must be in 1..10." }
        require(properties.waitTimeSeconds in 0..20) { "waitTimeSeconds must be in 0..20." }
        require(properties.visibilityTimeoutSeconds >= 0) { "visibilityTimeoutSeconds must be non-negative." }
        require(properties.maxReceiveCount > 0) { "maxReceiveCount must be positive." }
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
