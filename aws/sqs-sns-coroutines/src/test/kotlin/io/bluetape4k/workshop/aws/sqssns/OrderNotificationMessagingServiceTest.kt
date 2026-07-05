package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.aws.spring.sns.SnsFifoThroughputScope
import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderNotificationMessagingServiceTest {

    @Test
    fun `maps order notification to SNS publish request and metrics`() = runSuspendIO {
        val fixture = serviceFixture()

        val report = fixture.service.publish(sampleRequest())

        report.state shouldBeEqualTo PublishState.PUBLISHED
        report.messageId shouldBeEqualTo "sns-message-1"
        report.idempotencyKey shouldBeEqualTo "order-100-notification"
        report.correlationId shouldBeEqualTo "corr-100"

        val publish = fixture.sns.requests.single()
        publish.topicArn shouldBeEqualTo TOPIC_ARN
        publish.subject shouldBeEqualTo "Order notification"
        publish.message shouldContain "\"orderId\":\"order-100\""
        publish.message shouldContain "\"publishedAt\":\"2026-07-02T01:02:03Z\""
        publish.messageAttributes["correlationId"]?.stringValue() shouldBeEqualTo "corr-100"
        publish.messageAttributes["idempotencyKey"]?.stringValue() shouldBeEqualTo "order-100-notification"
        publish.messageAttributes["eventType"]?.stringValue() shouldBeEqualTo "ORDER_PLACED"

        fixture.meterRegistry.counter(OrderNotificationMetrics.PUBLISH_ATTEMPTS, "result", "success").count()
            .shouldBeGreaterThan(0.0)
        fixture.meterRegistry.get(OrderNotificationMetrics.PUBLISH_LATENCY)
            .tag("operation", "publish")
            .tag("result", "success")
            .timer()
            .count() shouldBeEqualTo 1L
    }

    @Test
    fun `consume success invokes handler deletes message and records ack metric`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 1)

        val reports = fixture.service.consumeOnce()

        reports.single().state shouldBeEqualTo ConsumeState.ACKED
        reports.single().orderId shouldBeEqualTo "order-100"
        fixture.handler.events.single().correlationId shouldBeEqualTo "corr-100"
        fixture.sqs.deletedHandles.single() shouldBeEqualTo "receipt-1"
        fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "acked").count()
            .shouldBeGreaterThan(0.0)
    }

    @Test
    fun `handler failure requests retry with immediate visibility change`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 1)
        fixture.handler.failure = IllegalStateException("downstream unavailable")

        val report = fixture.service.consumeOnce().single()

        report.state shouldBeEqualTo ConsumeState.RETRY_REQUESTED
        report.message shouldContain "downstream unavailable"
        fixture.sqs.visibilityChanges.single() shouldBeEqualTo VisibilityChange("orders-queue", "receipt-1", 0)
        fixture.sqs.deletedHandles.size shouldBeEqualTo 0
        val ackedCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "acked").count()
        val failureCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "failure").count()
        val retryCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "retry").count()
        ackedCount shouldBeEqualTo 0.0
        failureCount shouldBeEqualTo 0.0
        retryCount shouldBeGreaterThan 0.0
    }

    @Test
    fun `delete failure records failure without ack or retry counters`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 1)
        fixture.sqs.deleteFailure = IllegalStateException("delete unavailable")

        assertFailsWith<IllegalStateException> {
            fixture.service.consumeOnce()
        }

        val ackedCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "acked").count()
        val retryCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "retry").count()
        val failureCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "failure").count()
        ackedCount shouldBeEqualTo 0.0
        retryCount shouldBeEqualTo 0.0
        failureCount shouldBeEqualTo 1.0
    }

    @Test
    fun `visibility change failure records failure without retry counter`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 1)
        fixture.handler.failure = IllegalStateException("downstream unavailable")
        fixture.sqs.visibilityFailure = IllegalStateException("visibility unavailable")

        assertFailsWith<IllegalStateException> {
            fixture.service.consumeOnce()
        }

        val ackedCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "acked").count()
        val retryCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "retry").count()
        val failureCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "failure").count()
        ackedCount shouldBeEqualTo 0.0
        retryCount shouldBeEqualTo 0.0
        failureCount shouldBeEqualTo 1.0
    }

    @Test
    fun `malformed SQS payload requests retry without invoking handler`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage("{not-json", receiveCount = 1)

        val report = fixture.service.consumeOnce().single()

        report.state shouldBeEqualTo ConsumeState.RETRY_REQUESTED
        report.orderId shouldBeEqualTo null
        fixture.handler.events.size shouldBeEqualTo 0
        fixture.sqs.visibilityChanges.single() shouldBeEqualTo VisibilityChange("orders-queue", "receipt-1", 0)
        fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "retry").count()
            .shouldBeGreaterThan(0.0)
    }

    @Test
    fun `max receive count classifies message as local dead letter`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 3)

        val report = fixture.service.consumeOnce().single()

        report.state shouldBeEqualTo ConsumeState.DEAD_LETTER
        report.receiveCount shouldBeEqualTo 3
        fixture.handler.events.size shouldBeEqualTo 0
        fixture.sqs.deletedHandles.single() shouldBeEqualTo "receipt-1"
        fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "dead-letter").count()
            .shouldBeGreaterThan(0.0)
    }

    @Test
    fun `rethrows cancellation from SNS publish boundary`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sns.failure = CancellationException("publish cancelled")

        assertFailsWith<CancellationException> {
            fixture.service.publish(sampleRequest())
        }
        val successCount = fixture.meterRegistry.counter(OrderNotificationMetrics.PUBLISH_ATTEMPTS, "result", "success").count()
        val cancelledCount = fixture.meterRegistry.counter(OrderNotificationMetrics.PUBLISH_ATTEMPTS, "result", "cancelled").count()
        successCount shouldBeEqualTo 0.0
        cancelledCount shouldBeEqualTo 1.0
    }

    @Test
    fun `rethrows cancellation from handler and records cancelled consume metric`() = runSuspendIO {
        val fixture = serviceFixture()
        fixture.sqs.messages += sqsMessage(fixture.eventJson(), receiveCount = 1)
        fixture.handler.failure = CancellationException("handler cancelled")

        assertFailsWith<CancellationException> {
            fixture.service.consumeOnce()
        }

        fixture.sqs.deletedHandles.size shouldBeEqualTo 0
        fixture.sqs.visibilityChanges.size shouldBeEqualTo 0
        val ackedCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "acked").count()
        val cancelledCount = fixture.meterRegistry.counter(OrderNotificationMetrics.CONSUME_MESSAGES, "result", "cancelled").count()
        ackedCount shouldBeEqualTo 0.0
        cancelledCount shouldBeEqualTo 1.0
    }

    @Test
    fun `rejects blank request fields`() = runSuspendIO {
        val fixture = serviceFixture()

        assertFailsWith<IllegalArgumentException> {
            fixture.service.publish(sampleRequest().copy(orderId = " "))
        }
    }

    private fun serviceFixture(): ServiceFixture {
        val sns = CapturingSnsOperations()
        val sqs = CapturingSqsOperations()
        val handler = CapturingHandler()
        val meterRegistry = SimpleMeterRegistry()
        val objectMapper = Jackson.defaultJsonMapper
        val properties = SqsSnsMessagingProperties(
            topicArn = TOPIC_ARN,
            queueUrl = QUEUE_URL,
            subject = "Order notification",
            maxReceiveCount = 3,
        )
        val service = OrderNotificationMessagingService(
            properties = properties,
            sns = sns,
            sqs = sqs,
            handler = handler,
            objectMapper = objectMapper,
            metrics = OrderNotificationMetrics(meterRegistry),
            clock = Clock.fixed(Instant.parse("2026-07-02T01:02:03Z"), ZoneOffset.UTC),
        )
        return ServiceFixture(service, sns, sqs, handler, meterRegistry, objectMapper)
    }

    private fun sampleRequest(): OrderNotificationRequest =
        OrderNotificationRequest(
            orderId = "order-100",
            customerId = "customer-200",
            eventType = OrderNotificationType.ORDER_PLACED,
            message = "Order was accepted",
            idempotencyKey = "order-100-notification",
            correlationId = "corr-100",
        )

    private fun sqsMessage(body: String, receiveCount: Int): SqsReceivedMessage =
        SqsReceivedMessage(
            queueUrl = QUEUE_URL,
            message = Message.builder()
                .messageId("message-1")
                .receiptHandle("receipt-1")
                .body(body)
                .attributes(mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to receiveCount.toString()))
                .build(),
        )

    private data class ServiceFixture(
        val service: OrderNotificationMessagingService,
        val sns: CapturingSnsOperations,
        val sqs: CapturingSqsOperations,
        val handler: CapturingHandler,
        val meterRegistry: SimpleMeterRegistry,
        val objectMapper: tools.jackson.databind.ObjectMapper,
    ) : Serializable {
        fun eventJson(): String =
            objectMapper.writeValueAsString(
                OrderNotificationEvent(
                    orderId = "order-100",
                    customerId = "customer-200",
                    eventType = OrderNotificationType.ORDER_PLACED,
                    message = "Order was accepted",
                    idempotencyKey = "order-100-notification",
                    correlationId = "corr-100",
                    publishedAt = "2026-07-02T01:02:03Z",
                )
            )

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class CapturingHandler: OrderNotificationHandler {
        val events = mutableListOf<OrderNotificationEvent>()
        var failure: Throwable? = null

        override suspend fun handle(event: OrderNotificationEvent) {
            failure?.let { throw it }
            events += event
        }
    }

    private class CapturingSnsOperations: SnsOperations {
        val requests = mutableListOf<SnsPublishRequest>()
        var failure: Throwable? = null

        override suspend fun createTopic(topicName: String, attributes: Map<String, String>): String = TOPIC_ARN

        override suspend fun createFifoTopic(
            topicName: String,
            contentBasedDeduplication: Boolean,
            fifoThroughputScope: SnsFifoThroughputScope?,
            attributes: Map<String, String>,
        ): String = "$TOPIC_ARN.fifo"

        override suspend fun createConfiguredTopic(topicName: String): String = TOPIC_ARN

        override suspend fun findTopicArn(topicName: String): String? = TOPIC_ARN

        override suspend fun publish(request: SnsPublishRequest): PublishResponse {
            failure?.let { throw it }
            requests += request
            return PublishResponse.builder().messageId("sns-message-${requests.size}").build()
        }

        override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
            PublishResponse.builder().messageId("sms-message-1").build()

        override suspend fun confirmSubscription(
            topicArn: String,
            token: String,
            authenticateOnUnsubscribe: Boolean,
        ): ConfirmSubscriptionResponse =
            ConfirmSubscriptionResponse.builder().subscriptionArn("subscription").build()

        override suspend fun confirmSubscription(
            message: SnsHttpMessage,
            authenticateOnUnsubscribe: Boolean,
        ): ConfirmSubscriptionResponse =
            ConfirmSubscriptionResponse.builder().subscriptionArn("subscription").build()
    }

    private class CapturingSqsOperations: SqsOperations {
        val messages = mutableListOf<SqsReceivedMessage>()
        val deletedHandles = mutableListOf<String>()
        val visibilityChanges = mutableListOf<VisibilityChange>()
        var deleteFailure: Throwable? = null
        var visibilityFailure: Throwable? = null

        override suspend fun getQueueUrl(queueName: String): String = QUEUE_URL

        override suspend fun createQueue(
            queueName: String,
            attributes: Map<QueueAttributeName, String>,
        ): String = QUEUE_URL

        override suspend fun createConfiguredQueue(queueName: String): String = QUEUE_URL

        override suspend fun send(queueUrl: String, body: String, delaySeconds: Int?): SendMessageResponse =
            SendMessageResponse.builder().messageId("sqs-message-1").build()

        override suspend fun send(request: SqsSendRequest): SendMessageResponse =
            SendMessageResponse.builder().messageId("sqs-message-1").build()

        override suspend fun receive(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): List<SqsReceivedMessage> =
            messages.take(maxMessages)

        override suspend fun delete(queueUrl: String, receiptHandle: String): DeleteMessageResponse {
            deleteFailure?.let { throw it }
            deletedHandles += receiptHandle
            return DeleteMessageResponse.builder().build()
        }

        override suspend fun changeVisibility(
            queueUrl: String,
            receiptHandle: String,
            timeoutSeconds: Int,
        ): ChangeMessageVisibilityResponse {
            visibilityFailure?.let { throw it }
            visibilityChanges += VisibilityChange(queueUrl, receiptHandle, timeoutSeconds)
            return ChangeMessageVisibilityResponse.builder().build()
        }

        override fun receiveFlow(
            queueUrl: String,
            maxMessages: Int,
            waitTimeSeconds: Int,
            visibilityTimeoutSeconds: Int?,
        ): Flow<SqsReceivedMessage> = emptyFlow()
    }

    private data class VisibilityChange(
        val queueUrl: String,
        val receiptHandle: String,
        val timeoutSeconds: Int,
    ) : Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    companion object {
        private const val TOPIC_ARN = "arn:aws:sns:ap-northeast-2:123456789012:order-notifications"
        private const val QUEUE_URL = "orders-queue"
    }
}
