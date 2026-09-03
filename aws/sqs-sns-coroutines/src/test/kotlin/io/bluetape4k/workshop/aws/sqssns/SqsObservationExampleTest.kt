package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.auth.staticCredentialsProviderOf
import io.bluetape4k.aws.spring.AwsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgementAction
import io.bluetape4k.aws.spring.sqs.SqsAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsObservationAutoConfiguration
import io.bluetape4k.aws.spring.sqs.SqsObservationOutcome
import io.bluetape4k.aws.spring.sqs.SqsObservationStage
import io.bluetape4k.aws.spring.sqs.SqsOperations
import io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerRegistry
import io.bluetape4k.aws.spring.sqs.SqsSendRequest
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

class SqsObservationExampleTest {

    @Test
    fun `disabled observation keeps the optional listener and runtime absent`() {
        val operations = LocalSqsOperations()
        observationRunner(operations, ObservationRegistry.create())
            .withUserConfiguration(SuccessfulHandlerConfiguration::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=false")
            .run { context ->
                (context.startupFailure == null).shouldBeTrue()
                context.getBeansOfType(OrderNotificationObservationListener::class.java).size shouldBeEqualTo 0
                context.getBeansOfType(OrderNotificationObservationRecorder::class.java).size shouldBeEqualTo 0
                context.containsBean("sqsObservationRuntime").shouldBeFalse()
                operations.visibilityChanges.isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `enabled listener propagates process parent and records heartbeat plus acknowledgement`() {
        val operations = LocalSqsOperations()
        val registry = ObservationRegistry.create()
        observationRunner(operations, registry)
            .withUserConfiguration(SuccessfulHandlerConfiguration::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .run { context ->
                val listener = context.getBean(OrderNotificationObservationListener::class.java)
                val handler = context.getBean(DelayedOrderNotificationHandler::class.java)
                val recorder = context.getBean(OrderNotificationObservationRecorder::class.java)
                val listenerRegistry = context.getBean(SqsMessageListenerContainerRegistry::class.java)

                runBlocking {
                    operations.send(SqsSendRequest(QUEUE_URL, eventJson()))
                }
                listenerRegistry.start(OrderNotificationObservationListener.LISTENER_ID)

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.handledEvents.size shouldBeEqualTo 1
                    listener.lastParentObservationMatched.shouldBeTrue()
                    handler.events.single().orderId shouldBeEqualTo "order-100"
                }
                listenerRegistry.stop(OrderNotificationObservationListener.LISTENER_ID)

                val records = recorder.snapshots
                records.any { it.stage == SqsObservationStage.RECEIVE }.shouldBeTrue()
                records.count { it.stage == SqsObservationStage.PROCESS } shouldBeEqualTo 1
                records.any {
                    it.stage == SqsObservationStage.PROCESS &&
                        it.outcome == SqsObservationOutcome.SUCCESS
                }.shouldBeTrue()
                records.any {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.CHANGE_VISIBILITY
                }.shouldBeTrue()
                records.count {
                    it.stage == SqsObservationStage.ACKNOWLEDGEMENT &&
                        it.acknowledgementAction == SqsAcknowledgementAction.ACK
                } shouldBeEqualTo 1
                operations.visibilityChanges.isNotEmpty().shouldBeTrue()
                (registry.currentObservation == null).shouldBeTrue()
            }
    }

    @Test
    fun `NOOP registry keeps listener usable without creating observations`() {
        val operations = LocalSqsOperations()
        val registry = ObservationRegistry.NOOP
        observationRunner(operations, registry)
            .withUserConfiguration(SuccessfulHandlerConfiguration::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .run { context ->
                val listener = context.getBean(OrderNotificationObservationListener::class.java)
                val recorder = context.getBean(OrderNotificationObservationRecorder::class.java)
                val listenerRegistry = context.getBean(SqsMessageListenerContainerRegistry::class.java)
                context.containsBean("sqsObservationRuntime").shouldBeFalse()

                runBlocking {
                    operations.send(SqsSendRequest(QUEUE_URL, eventJson()))
                }
                listenerRegistry.start(OrderNotificationObservationListener.LISTENER_ID)
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    listener.handledEvents.size shouldBeEqualTo 1
                }
                listenerRegistry.stop(OrderNotificationObservationListener.LISTENER_ID)

                recorder.snapshots.isEmpty().shouldBeTrue()
                listener.lastParentObservationMatched.shouldBeFalse()
                (registry.currentObservation == null).shouldBeTrue()
            }
    }

    @Test
    fun `handler cancellation is recorded as cancelled process without acknowledgement`() {
        val operations = LocalSqsOperations()
        val registry = ObservationRegistry.create()
        observationRunner(operations, registry)
            .withUserConfiguration(CancellingHandlerConfiguration::class.java)
            .withPropertyValues("bluetape4k.aws.sqs.observation.enabled=true")
            .run { context ->
                val recorder = context.getBean(OrderNotificationObservationRecorder::class.java)
                val listenerRegistry = context.getBean(SqsMessageListenerContainerRegistry::class.java)
                runBlocking {
                    operations.send(SqsSendRequest(QUEUE_URL, eventJson()))
                }
                listenerRegistry.start(OrderNotificationObservationListener.LISTENER_ID)

                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    recorder.snapshots.any {
                        it.stage == SqsObservationStage.PROCESS &&
                            it.outcome == SqsObservationOutcome.CANCELLED
                    }.shouldBeTrue()
                }
                assertDoesNotThrow {
                    listenerRegistry.stop(OrderNotificationObservationListener.LISTENER_ID)
                }
                operations.visibilityChanges.isEmpty().shouldBeTrue()
                (registry.currentObservation == null).shouldBeTrue()
            }
    }

    private fun observationRunner(
        operations: LocalSqsOperations,
        registry: ObservationRegistry,
    ): ApplicationContextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    AwsAutoConfiguration::class.java,
                    SqsObservationAutoConfiguration::class.java,
                    SqsAutoConfiguration::class.java,
                ),
            )
            .withBean(SqsOperations::class.java, Supplier { operations })
            .withBean(ObservationRegistry::class.java, Supplier { registry })
            .withBean(tools.jackson.databind.ObjectMapper::class.java, Supplier { Jackson.defaultJsonMapper })
            .withBean(SqsSnsMessagingProperties::class.java, Supplier {
                SqsSnsMessagingProperties(queueUrl = QUEUE_URL)
            })
            .withBean(software.amazon.awssdk.auth.credentials.AwsCredentialsProvider::class.java, Supplier {
                staticCredentialsProviderOf("test-access-key", "test-secret-key")
            })
            .withUserConfiguration(SqsObservationExampleConfiguration::class.java)
            .withPropertyValues(
                "bluetape4k.aws.region=us-east-1",
                "bluetape4k.aws.sqs.region=us-east-1",
                "bluetape4k.aws.sqs.endpoint-override=http://localhost:4566",
                "bluetape4k.aws.sqs.listener.auto-startup=false",
                "bluetape4k.aws.sqs.listener.wait-time-seconds=1",
                "bluetape4k.aws.sqs.listener.stop-timeout-millis=5000",
                "bluetape4k.aws.sqs-sns.queue-url=$QUEUE_URL",
            )

    private fun eventJson(): String =
        Jackson.defaultJsonMapper.writeValueAsString(
            OrderNotificationEvent(
                orderId = "order-100",
                customerId = "customer-200",
                eventType = OrderNotificationType.ORDER_PLACED,
                message = "Order was accepted",
                idempotencyKey = "order-100-notification",
                correlationId = "corr-100",
                publishedAt = "2026-07-02T01:02:03Z",
            ),
        )

    @Configuration(proxyBeanMethods = false)
    internal class SuccessfulHandlerConfiguration {
        @Bean
        fun orderNotificationHandler(): DelayedOrderNotificationHandler = DelayedOrderNotificationHandler()
    }

    @Configuration(proxyBeanMethods = false)
    internal class CancellingHandlerConfiguration {
        @Bean
        fun orderNotificationHandler(): CancellingOrderNotificationHandler = CancellingOrderNotificationHandler()
    }

    internal class DelayedOrderNotificationHandler : OrderNotificationHandler {
        val events = CopyOnWriteArrayList<OrderNotificationEvent>()

        override suspend fun handle(event: OrderNotificationEvent) {
            events += event
            delay(1_500)
        }
    }

    internal class CancellingOrderNotificationHandler : OrderNotificationHandler {
        override suspend fun handle(@Suppress("UNUSED_PARAMETER") event: OrderNotificationEvent) {
            throw CancellationException("handler cancelled")
        }
    }

    private companion object {
        const val QUEUE_URL = "https://sqs.local/000000000000/order-notifications"
    }
}
