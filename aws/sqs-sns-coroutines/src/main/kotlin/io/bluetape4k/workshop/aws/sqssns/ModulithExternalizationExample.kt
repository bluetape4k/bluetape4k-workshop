package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistration
import io.bluetape4k.aws.spring.modulith.AwsModulithEventTypeRegistry
import io.bluetape4k.aws.spring.modulith.AwsModulithEventsProperties
import io.bluetape4k.aws.spring.modulith.AwsModulithSqsEventConsumer
import io.bluetape4k.aws.spring.sqs.SqsOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.core.EventSerializer
import org.springframework.modulith.events.support.EventExternalizationTransport
import org.springframework.modulith.events.support.EventExternalizerModuleListener
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * 기존 SQS/SNS 예제에서 Spring Modulith 외부화를 선택적으로 켜는 설정입니다.
 *
 * 기본 messaging service와 분리된 opt-in fixture이므로 기존 예제의 동작이나 AWS 계정
 * 설정을 변경하지 않습니다. producer와 consumer는 bluetape4k AWS 자동 설정이 조립합니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "bluetape4k.aws.modulith.example",
    name = ["enabled"],
    havingValue = "true",
)
class AwsModulithMessagingExampleConfiguration {

    @Bean
    fun awsModulithWorkshopEventTypeRegistry(): AwsModulithEventTypeRegistry =
        AwsModulithEventTypeRegistry.of(
            AwsModulithEventTypeRegistration(
                type = MODULITH_EVENT_TYPE,
                version = 1,
                eventClass = ModulithOrderPlacedIntegrationEvent::class.java,
                eventId = ModulithOrderPlacedIntegrationEvent::eventId,
                allowedHeaderNames = setOf(CORRELATION_HEADER),
                headers = { event -> mapOf(CORRELATION_HEADER to event.correlationRef) },
            ),
        )

    @Bean
    @Primary
    fun awsModulithWorkshopEventSerializer(objectMapper: ObjectMapper): EventSerializer =
        object : EventSerializer {
            override fun serialize(event: Any): Any = objectMapper.writeValueAsString(event)

            override fun <T : Any> deserialize(serialized: Any, type: Class<T>): T =
                objectMapper.readValue(serialized.toString(), type)
        }

    @Bean
    @Primary
    fun awsModulithWorkshopExternalizationConfiguration(
        properties: AwsModulithEventsProperties,
    ): EventExternalizationConfiguration {
        require(TARGET_ALIAS in properties.targets) {
            "Configure bluetape4k.aws.modulith.events.targets.$TARGET_ALIAS before enabling the example."
        }
        return object : EventExternalizationConfiguration {
            override fun supports(event: Any): Boolean = event is ModulithOrderPlacedEvent

            override fun map(event: Any): Any = (event as ModulithOrderPlacedEvent).toIntegrationEvent()

            override fun determineTarget(event: Any): RoutingTarget {
                val source = event as ModulithOrderPlacedEvent
                val destination = properties.targets.getValue(TARGET_ALIAS).destination
                val builder = RoutingTarget.forTarget(TARGET_ALIAS)
                return if (destination.endsWith(".fifo")) {
                    builder.andKey(source.orderId)
                } else {
                    builder.withoutKey()
                }
            }

            override fun getHeadersFor(event: Any): Map<String, Any> =
                mapOf(CORRELATION_HEADER to correlationRef((event as ModulithOrderPlacedEvent).correlationId))

            override fun serializeExternalization(): Boolean = true
        }
    }

    /** Spring Modulith listener를 명시적으로 노출해 publication completion을 관찰합니다. */
    @Bean
    fun awsModulithWorkshopExternalizer(
        configuration: EventExternalizationConfiguration,
        transport: EventExternalizationTransport,
    ): EventExternalizerModuleListener = EventExternalizerModuleListener(configuration, transport)

    @Bean
    fun modulithExternalizationService(
        externalizer: EventExternalizerModuleListener,
        consumer: AwsModulithSqsEventConsumer,
        sqs: SqsOperations,
        properties: AwsModulithEventsProperties,
    ): ModulithExternalizationService =
        ModulithExternalizationService(externalizer, consumer, sqs, properties)
}

/**
 * 실제 transport future가 완료된 뒤에만 publication 성공을 반환하고, consumer의 정상
 * outcome 뒤에만 SQS delete를 수행하는 작은 workshop facade입니다.
 */
class ModulithExternalizationService internal constructor(
    private val externalizer: EventExternalizerModuleListener,
    private val consumer: AwsModulithSqsEventConsumer,
    private val sqs: SqsOperations,
    private val properties: AwsModulithEventsProperties,
) {

    suspend fun publish(event: ModulithOrderPlacedEvent): ModulithPublishReport {
        require(event.eventId.isNotBlank()) { "eventId must not be blank." }
        require(event.orderId.isNotBlank()) { "orderId must not be blank." }
        return try {
            externalizer.externalize(event).await()
            ModulithPublishReport(
                state = ModulithPublishState.PUBLISHED,
                eventId = event.eventId,
                targetAlias = TARGET_ALIAS,
                message = "published",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ModulithPublishReport(
                state = ModulithPublishState.FAILED,
                eventId = event.eventId,
                targetAlias = TARGET_ALIAS,
                message = "publish-failed",
            )
        }
    }

    suspend fun consumeOnce(): ModulithConsumeReport {
        val queueName = requireNotNull(properties.consumer.queue) {
            "Configure bluetape4k.aws.modulith.events.consumer.queue before consuming."
        }
        val queueUrl = sqs.getQueueUrl(queueName)
        val message = sqs.receive(
            queueUrl = queueUrl,
            maxMessages = 1,
            waitTimeSeconds = 0,
            visibilityTimeoutSeconds = 30,
        ).firstOrNull() ?: return ModulithConsumeReport(state = ModulithConsumeState.EMPTY)

        val eventId = message.messageAttributes[EVENT_ID_ATTRIBUTE]?.stringValue()
        val receiveCount = message.approximateReceiveCount ?: 1
        return try {
            val outcome = consumer.consume(message)
            sqs.delete(message.queueUrl, message.receiptHandle)
            ModulithConsumeReport(
                state = ModulithConsumeState.ACKED,
                messageId = message.messageId,
                eventId = eventId,
                receiveCount = receiveCount,
                outcome = outcome.name,
                acknowledgementCalls = 1,
                message = "acked",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            sqs.changeVisibility(message.queueUrl, message.receiptHandle, timeoutSeconds = 0)
            ModulithConsumeReport(
                state = ModulithConsumeState.RETRY_REQUESTED,
                messageId = message.messageId,
                eventId = eventId,
                receiveCount = receiveCount,
                acknowledgementCalls = 0,
                message = "retry-requested",
            )
        }
    }

    private companion object {
        const val EVENT_ID_ATTRIBUTE = "bt4k-event-id"
    }
}

private fun ModulithOrderPlacedEvent.toIntegrationEvent(): ModulithOrderPlacedIntegrationEvent =
    ModulithOrderPlacedIntegrationEvent(
        eventId = eventId,
        orderId = orderId,
        message = message,
        correlationRef = correlationRef(correlationId),
    )

private fun correlationRef(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
        .take(CORRELATION_REF_LENGTH)

private const val TARGET_ALIAS = "order-notifications"
private const val MODULITH_EVENT_TYPE = "order.placed"
private const val CORRELATION_HEADER = "correlationRef"
private const val CORRELATION_REF_LENGTH = 16
