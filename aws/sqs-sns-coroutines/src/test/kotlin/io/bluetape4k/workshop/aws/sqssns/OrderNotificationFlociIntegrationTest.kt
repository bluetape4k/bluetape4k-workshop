package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.spring.sns.SnsCoroutinesTemplate
import io.bluetape4k.aws.spring.sns.SnsProperties
import io.bluetape4k.aws.spring.sqs.SqsCoroutinesTemplate
import io.bluetape4k.aws.spring.sqs.SqsProperties
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import io.bluetape4k.codec.Base58
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.aws.AwsEmulatorServer
import io.bluetape4k.testcontainers.aws.FlociServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.future.await
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sns.SnsAsyncClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderNotificationFlociIntegrationTest {

    private val awsEmulator: AwsEmulatorServer by lazy { FlociServer.Launcher.floci }

    private val snsAsyncClientHolder = lazy {
        SnsAsyncClient.builder()
            .endpointOverride(awsEmulator.awsEndpoint)
            .region(Region.of(awsEmulator.regionName))
            .credentialsProvider(staticCredentialsProviderOf(awsEmulator.awsAccessKey, awsEmulator.awsSecretKey))
            .build()
    }

    private val sqsAsyncClientHolder = lazy {
        SqsAsyncClient.builder()
            .endpointOverride(awsEmulator.awsEndpoint)
            .region(Region.of(awsEmulator.regionName))
            .credentialsProvider(staticCredentialsProviderOf(awsEmulator.awsAccessKey, awsEmulator.awsSecretKey))
            .build()
    }

    private val snsAsyncClient: SnsAsyncClient by snsAsyncClientHolder
    private val sqsAsyncClient: SqsAsyncClient by sqsAsyncClientHolder

    private val sns = SnsCoroutinesTemplate(
        snsAsyncClient = snsAsyncClient,
        properties = SnsProperties(region = "ap-northeast-2", endpointOverride = awsEmulator.awsEndpoint),
    )

    private val sqs = SqsCoroutinesTemplate(
        sqsAsyncClient = sqsAsyncClient,
        properties = SqsProperties(region = "ap-northeast-2", endpointOverride = awsEmulator.awsEndpoint),
    )

    private val objectMapper = Jackson.defaultJsonMapper

    @AfterAll
    fun closeClients() {
        if (snsAsyncClientHolder.isInitialized()) {
            runCatching { snsAsyncClient.close() }
        }
        if (sqsAsyncClientHolder.isInitialized()) {
            runCatching { sqsAsyncClient.close() }
        }
    }

    @Test
    fun `publishes SNS and consumes SQS through Floci backed bluetape4k operations`() = runSuspendIO {
        val topicName = awsName("order-notifications")
        val queueName = awsName("order-notification-queue")
        var topicArn: String? = null
        var queueUrl: String? = null

        try {
            val createdTopicArn = sns.createTopic(topicName)
            val createdQueueUrl = sqs.createQueue(queueName)
            topicArn = createdTopicArn
            queueUrl = createdQueueUrl
            val handler = CapturingHandler()
            val service = OrderNotificationMessagingService(
                properties = SqsSnsMessagingProperties(
                    topicArn = createdTopicArn,
                    queueUrl = createdQueueUrl,
                    maxMessages = 1,
                    waitTimeSeconds = 1,
                    visibilityTimeoutSeconds = 5,
                    maxReceiveCount = 3,
                ),
                sns = sns,
                sqs = sqs,
                handler = handler,
                objectMapper = objectMapper,
                metrics = OrderNotificationMetrics(SimpleMeterRegistry()),
                clock = Clock.fixed(Instant.parse("2026-07-02T01:02:03Z"), ZoneOffset.UTC),
            )

            val publishReport = service.publish(sampleRequest())

            publishReport.state shouldBeEqualTo PublishState.PUBLISHED
            publishReport.messageId.shouldNotBeNull().shouldNotBeBlank()

            sqs.send(SqsSendRequest(queueUrl = createdQueueUrl, body = eventJson()))

            var consumeReports = emptyList<OrderNotificationConsumeReport>()
            await atMost 30.seconds untilSuspending {
                consumeReports = service.consumeOnce()
                consumeReports.any { it.state == ConsumeState.ACKED && it.orderId == "order-100" }
            }

            consumeReports.single().state shouldBeEqualTo ConsumeState.ACKED
            handler.events.single().correlationId shouldBeEqualTo "corr-100"
        } finally {
            topicArn?.let { arn ->
                snsAsyncClient.deleteTopic { it.topicArn(arn) }.await()
            }
            queueUrl?.let { url ->
                sqsAsyncClient.deleteQueue { it.queueUrl(url) }.await()
            }
        }
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

    private fun eventJson(): String =
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

    private fun awsName(prefix: String): String =
        "workshop-$prefix-${Base58.randomString(8).lowercase()}"

    private class CapturingHandler: OrderNotificationHandler {
        val events = mutableListOf<OrderNotificationEvent>()

        override suspend fun handle(event: OrderNotificationEvent) {
            events += event
        }
    }
}
