package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.modulith.AwsModulithEventsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import io.bluetape4k.jackson3.Jackson
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

class ModulithExternalizationExampleTest {

    @Test
    fun `modulith fixture stays disabled until the example property is enabled`() {
        runner(LocalSqsOperations())
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.enabled=false",
                "bluetape4k.aws.modulith.example.enabled=false",
            )
            .run { context ->
                assertTrue(context.startupFailure == null)
                assertFalse(context.containsBean("modulithExternalizationService"))
            }
    }

    @Test
    fun `externalized event is redacted, delivered, and acknowledged after dispatch`() {
        val operations = LocalSqsOperations()
        runner(operations)
            .withUserConfiguration(CapturingEventConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.targets.order-notifications.destination=order-notifications",
            )
            .run { context ->
                assertTrue(context.startupFailure == null)
                val service = context.getBean(ModulithExternalizationService::class.java)
                val handler = context.getBean(CapturingEventHandler::class.java)

                runBlocking {
                    val report = service.publish(
                        ModulithOrderPlacedEvent(
                            eventId = "event-100",
                            orderId = "order-100",
                            correlationId = "customer@example.test",
                            message = "order accepted",
                            privateNote = "never-send-this-secret",
                        ),
                    )

                    assertEquals(ModulithPublishState.PUBLISHED, report.state)
                    val queueUrl = operations.getQueueUrl("order-notifications")
                    val message = operations.receive(
                        queueUrl,
                        maxMessages = 1,
                        waitTimeSeconds = 0,
                        visibilityTimeoutSeconds = 0,
                    ).single()
                    assertFalse(message.body.contains("never-send-this-secret"))
                    assertFalse(message.body.contains("customer@example.test"))
                    assertEquals("order.placed", message.messageAttributes.entries
                        .single { it.key == "bt4k-event-type" }.value.stringValue())

                    val consume = service.consumeOnce()
                    assertEquals(ModulithConsumeState.ACKED, consume.state)
                    assertEquals("order-100", handler.events.single().orderId)
                    assertEquals(0, operations.receive(queueUrl, maxMessages = 1, waitTimeSeconds = 0).size)
                }
            }
    }

    @Test
    fun `handler failure requests visibility retry without acknowledgement`() {
        val operations = LocalSqsOperations()
        runner(operations)
            .withUserConfiguration(FailingEventConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.targets.order-notifications.destination=order-notifications",
            )
            .run { context ->
                assertTrue(context.startupFailure == null)
                val service = context.getBean(ModulithExternalizationService::class.java)

                runBlocking {
                    service.publish(sampleEvent()).also {
                        assertEquals(ModulithPublishState.PUBLISHED, it.state)
                    }
                    val report = service.consumeOnce()
                    assertEquals(ModulithConsumeState.RETRY_REQUESTED, report.state)
                    assertEquals(1, operations.visibilityChanges.size)
                    assertEquals(0, report.acknowledgementCalls)
                }
            }
    }

    @Test
    fun `fifo target carries order key and deterministic deduplication`() {
        val operations = LocalSqsOperations()
        runner(operations)
            .withPropertyValues(
                "bluetape4k.aws.modulith.events.targets.order-notifications.destination=order-notifications.fifo",
                "bluetape4k.aws.modulith.events.consumer.queue=order-notifications.fifo",
            )
            .run { context ->
                assertTrue(context.startupFailure == null)
                val service = context.getBean(ModulithExternalizationService::class.java)

                runBlocking {
                    assertEquals(ModulithPublishState.PUBLISHED, service.publish(sampleEvent()).state)
                    val message = operations.receive(
                        operations.getQueueUrl("order-notifications.fifo"),
                        maxMessages = 1,
                        waitTimeSeconds = 0,
                    ).single()
                    assertEquals("order-100", message.messageGroupId)
                    assertNotNull(message.messageDeduplicationId)
                }
            }
    }

    private fun runner(operations: LocalSqsOperations): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    AwsModulithEventsAutoConfiguration::class.java,
                ),
            )
            .withBean(SqsOperations::class.java, Supplier { operations })
            .withBean(tools.jackson.databind.ObjectMapper::class.java, Supplier { Jackson.defaultJsonMapper })
            .withBean(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider::class.java, Supplier {
                staticCredentialsProviderOf("test-access-key", "test-secret-key")
            })
            .withUserConfiguration(AwsModulithMessagingExampleConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.enabled=true",
                "bluetape4k.aws.region=us-east-1",
                "bluetape4k.aws.sqs.region=us-east-1",
                "bluetape4k.aws.sqs.listener.enabled=false",
                "bluetape4k.aws.modulith.events.enabled=true",
                "bluetape4k.aws.modulith.events.producer.enabled=true",
                "bluetape4k.aws.modulith.events.targets.order-notifications.service=sqs",
                "bluetape4k.aws.modulith.events.targets.order-notifications.destination=order-notifications",
                "bluetape4k.aws.modulith.events.consumer.enabled=true",
                "bluetape4k.aws.modulith.events.consumer.queue=order-notifications",
                "bluetape4k.aws.modulith.events.consumer.source-mode=direct",
                "bluetape4k.aws.modulith.events.consumer.redrive-required=false",
                "bluetape4k.aws.modulith.example.enabled=true",
            )

    private fun sampleEvent() = ModulithOrderPlacedEvent(
        eventId = "event-100",
        orderId = "order-100",
        correlationId = "customer@example.test",
        message = "order accepted",
        privateNote = "never-send-this-secret",
    )

    @Configuration(proxyBeanMethods = false)
    internal class CapturingEventConfiguration {
        @Bean
        fun capturingEventHandler() = CapturingEventHandler()
    }

    @Configuration(proxyBeanMethods = false)
    internal class FailingEventConfiguration {
        @Bean
        fun failingEventHandler() = FailingEventHandler()
    }

    internal class CapturingEventHandler {
        val events = CopyOnWriteArrayList<ModulithOrderPlacedIntegrationEvent>()

        @EventListener
        fun on(event: ModulithOrderPlacedIntegrationEvent) {
            events += event
        }
    }

    internal class FailingEventHandler {
        @EventListener
        fun on(@Suppress("UNUSED_PARAMETER") event: ModulithOrderPlacedIntegrationEvent): Nothing =
            error("handler failure")
    }
}
