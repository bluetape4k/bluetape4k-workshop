package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.spring.sns.SnsFifoThroughputScope
import io.bluetape4k.aws.spring.sns.SnsBatchExecutionOptions
import io.bluetape4k.aws.spring.sns.SnsHttpMessage
import io.bluetape4k.aws.spring.sns.SnsOperations
import io.bluetape4k.aws.spring.sns.SnsPublishBatchRequest
import io.bluetape4k.aws.spring.sns.SnsPublishBatchResult
import io.bluetape4k.aws.spring.sns.SnsPublishBatchSuccess
import io.bluetape4k.aws.spring.sns.SnsPublishRequest
import io.bluetape4k.aws.spring.sns.SnsSmsRequest
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sns.model.ConfirmSubscriptionResponse
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 기본 `bootRun`과 smoke 테스트용 로컬 인메모리 AWS 메시징 bean입니다.
 */
@Configuration(proxyBeanMethods = false)
class LocalAwsMessagingConfig {

    @Bean
    @ConditionalOnMissingBean(SnsOperations::class)
    fun localSnsOperations(): SnsOperations =
        LocalSnsOperations()

    @Bean
    @ConditionalOnMissingBean(SqsOperations::class)
    fun localSqsOperations(): SqsOperations =
        LocalSqsOperations()
}

/**
 * 실제 bean이 제공되지 않을 때만 사용하는 인메모리 SNS 작업입니다.
 */
class LocalSnsOperations: SnsOperations {
    val publishedRequests: MutableList<SnsPublishRequest> = CopyOnWriteArrayList()
    val publishedBatchRequests: MutableList<SnsPublishBatchRequest> = CopyOnWriteArrayList()
    private val ids = AtomicInteger()

    override suspend fun createTopic(topicName: String, attributes: Map<String, String>): String =
        "arn:aws:sns:local:000000000000:$topicName"

    override suspend fun createFifoTopic(
        topicName: String,
        contentBasedDeduplication: Boolean,
        fifoThroughputScope: SnsFifoThroughputScope?,
        attributes: Map<String, String>,
    ): String =
        "arn:aws:sns:local:000000000000:${topicName.removeSuffix(".fifo")}.fifo"

    override suspend fun createConfiguredTopic(topicName: String): String =
        createTopic(topicName)

    override suspend fun findTopicArn(topicName: String): String? =
        createTopic(topicName)

    override suspend fun publish(request: SnsPublishRequest): PublishResponse {
        publishedRequests += request
        return PublishResponse.builder()
            .messageId("local-sns-${ids.incrementAndGet()}")
            .build()
    }

    override suspend fun publishBatch(
        request: SnsPublishBatchRequest,
        options: SnsBatchExecutionOptions,
    ): SnsPublishBatchResult {
        publishedBatchRequests += request
        return SnsPublishBatchResult(
            successful = request.entries.map { entry ->
                SnsPublishBatchSuccess(
                    entryId = entry.id,
                    messageId = "local-sns-${ids.incrementAndGet()}",
                )
            },
            failed = emptyList(),
        )
    }

    override suspend fun publishSms(request: SnsSmsRequest): PublishResponse =
        PublishResponse.builder()
            .messageId("local-sms-${ids.incrementAndGet()}")
            .build()

    override suspend fun confirmSubscription(
        topicArn: String,
        token: String,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        ConfirmSubscriptionResponse.builder()
            .subscriptionArn("$topicArn:subscription:$token")
            .build()

    override suspend fun confirmSubscription(
        message: SnsHttpMessage,
        authenticateOnUnsubscribe: Boolean,
    ): ConfirmSubscriptionResponse =
        confirmSubscription(message.topicArn.orEmpty(), message.token.orEmpty(), authenticateOnUnsubscribe)
}

/**
 * 실제 bean이 제공되지 않을 때만 사용하는 인메모리 SQS 작업입니다.
 */
class LocalSqsOperations: SqsOperations {
    private val messagesByQueue: MutableMap<String, CopyOnWriteArrayList<Message>> = ConcurrentHashMap()
    private val ids = AtomicInteger()

    override suspend fun getQueueUrl(queueName: String): String =
        queueUrl(queueName)

    override suspend fun createQueue(
        queueName: String,
        attributes: Map<QueueAttributeName, String>,
    ): String {
        val queueUrl = queueUrl(queueName)
        messagesByQueue.computeIfAbsent(queueUrl) { CopyOnWriteArrayList() }
        return queueUrl
    }

    override suspend fun createConfiguredQueue(queueName: String): String =
        createQueue(queueName)

    override suspend fun send(
        queueUrl: String,
        body: String,
        delaySeconds: Int?,
    ): SendMessageResponse =
        send(SqsSendRequest(queueUrl = queueUrl, body = body, delaySeconds = delaySeconds))

    override suspend fun send(request: SqsSendRequest): SendMessageResponse {
        val messageId = "local-sqs-${ids.incrementAndGet()}"
        val receiptHandle = "local-receipt-$messageId"
        val message = Message.builder()
            .messageId(messageId)
            .receiptHandle(receiptHandle)
            .body(request.body)
            .messageAttributes(request.messageAttributes)
            .attributes(mapOf(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to "0"))
            .build()
        messagesByQueue.computeIfAbsent(request.queueUrl) { CopyOnWriteArrayList() } += message
        return SendMessageResponse.builder().messageId(messageId).build()
    }

    override suspend fun receive(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): List<SqsReceivedMessage> {
        val queue = messagesByQueue.computeIfAbsent(queueUrl) { CopyOnWriteArrayList() }
        return queue.take(maxMessages).map { message ->
            val nextCount = ((message.attributes()[MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT]?.toIntOrNull() ?: 0) + 1)
            val updated = message.toBuilder()
                .attributes(message.attributes() + (MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT to nextCount.toString()))
                .build()
            queue[queue.indexOf(message)] = updated
            SqsReceivedMessage(queueUrl, updated)
        }
    }

    override suspend fun delete(
        queueUrl: String,
        receiptHandle: String,
    ): DeleteMessageResponse {
        messagesByQueue[queueUrl]?.removeIf { it.receiptHandle() == receiptHandle }
        return DeleteMessageResponse.builder().build()
    }

    override suspend fun changeVisibility(
        queueUrl: String,
        receiptHandle: String,
        timeoutSeconds: Int,
    ): ChangeMessageVisibilityResponse =
        ChangeMessageVisibilityResponse.builder().build()

    override fun receiveFlow(
        queueUrl: String,
        maxMessages: Int,
        waitTimeSeconds: Int,
        visibilityTimeoutSeconds: Int?,
    ): Flow<SqsReceivedMessage> = flow {
        receive(queueUrl, maxMessages, waitTimeSeconds, visibilityTimeoutSeconds).forEach { emit(it) }
    }

    private fun queueUrl(queueNameOrUrl: String): String =
        if (queueNameOrUrl.startsWith("http://") || queueNameOrUrl.startsWith("https://")) {
            queueNameOrUrl
        } else {
            "https://sqs.local/000000000000/$queueNameOrUrl"
        }
}
