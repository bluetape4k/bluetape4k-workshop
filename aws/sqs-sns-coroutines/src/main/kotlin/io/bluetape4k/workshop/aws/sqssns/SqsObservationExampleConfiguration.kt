package io.bluetape4k.workshop.aws.sqssns

import io.bluetape4k.aws.spring.sqs.SqsAcknowledgement
import io.bluetape4k.aws.spring.sqs.SqsAcknowledgementMode
import io.bluetape4k.aws.spring.sqs.SqsListener
import io.bluetape4k.aws.spring.sqs.SqsObservationContext
import io.bluetape4k.aws.spring.sqs.SqsObservationOutcome
import io.bluetape4k.aws.spring.sqs.SqsObservationStage
import io.bluetape4k.aws.spring.sqs.SqsReceivedMessage
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.CancellationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 2.0.0 SQS observation listener를 명시적으로 켰을 때만 등록하는 워크숍 설정입니다.
 *
 * 기본 one-shot 소비 경로에는 영향을 주지 않습니다. listener는 자동 시작하지 않으므로
 * 실습자는 [io.bluetape4k.aws.spring.sqs.SqsMessageListenerContainerRegistry]에서
 * 명시적으로 시작해 receive/process/ack 수명 주기를 관찰할 수 있습니다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bluetape4k.aws.sqs.observation", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(ObservationRegistry::class)
class SqsObservationExampleConfiguration {

    @Bean
    fun orderNotificationObservationRecorder(
        registry: ObservationRegistry,
    ): OrderNotificationObservationRecorder = OrderNotificationObservationRecorder(registry)

    @Bean
    fun orderNotificationObservationListener(
        objectMapper: ObjectMapper,
        handler: OrderNotificationHandler,
        registry: ObservationRegistry,
    ): OrderNotificationObservationListener = OrderNotificationObservationListener(objectMapper, handler, registry)
}

/**
 * SQS observation 단계별 결과를 학습자가 확인할 수 있는 제한된 recorder입니다.
 *
 * 원본 body, receipt handle, 전체 queue URL은 저장하지 않습니다.
 */
class OrderNotificationObservationRecorder(
    registry: ObservationRegistry,
) : ObservationHandler<SqsObservationContext> {

    private val records = CopyOnWriteArrayList<SqsObservationRecord>()

    init {
        registry.observationConfig().observationHandler(this)
    }

    val snapshots: List<SqsObservationRecord>
        get() = records.toList()

    override fun supportsContext(context: Observation.Context): Boolean = context is SqsObservationContext

    override fun onStop(context: SqsObservationContext) {
        records += SqsObservationRecord(
            stage = context.metadata.stage,
            outcome = context.outcome,
            retryCount = context.retryCount,
            attempt = context.attempt,
            acknowledgementAction = context.metadata.acknowledgementAction,
            delivery = context.metadata.delivery,
        )
    }
}

/** 관찰 recorder가 보존하는 비식별 SQS lifecycle 결과입니다. */
data class SqsObservationRecord(
    val stage: SqsObservationStage,
    val outcome: SqsObservationOutcome,
    val retryCount: Int,
    val attempt: Int?,
    val acknowledgementAction: io.bluetape4k.aws.spring.sqs.SqsAcknowledgementAction?,
    val delivery: io.bluetape4k.aws.spring.sqs.SqsObservationDelivery,
)

/**
 * Spring `@SqsListener`와 코루틴 trace context 전파를 함께 보여 주는 주문 알림 listener입니다.
 */
class OrderNotificationObservationListener(
    private val objectMapper: ObjectMapper,
    private val handler: OrderNotificationHandler,
    private val registry: ObservationRegistry,
) {

    private val parentMatched = AtomicBoolean(false)
    private val handled = CopyOnWriteArrayList<OrderNotificationEvent>()

    val lastParentObservationMatched: Boolean
        get() = parentMatched.get()

    val handledEvents: List<OrderNotificationEvent>
        get() = handled.toList()

    /**
     * 실습자는 registry에서 [LISTENER_ID]를 시작한 뒤 이 메서드의 child observation과
     * 실제 수동 ack를 확인합니다. heartbeat는 1초 간격으로 3초 visibility를 갱신합니다.
     */
    @SqsListener(
        queue = "\${bluetape4k.aws.sqs-sns.queue-url}",
        id = LISTENER_ID,
        maxMessages = 1,
        waitTimeSeconds = 1,
        messageVisibilityHeartbeatIntervalSeconds = 1,
        messageVisibilityHeartbeatSeconds = 3,
        autoStartup = false,
        acknowledgementMode = SqsAcknowledgementMode.MANUAL,
    )
    suspend fun handle(
        message: SqsReceivedMessage,
        acknowledgement: SqsAcknowledgement,
    ) {
        val processObservation = registry.currentObservation
        val childObservation = Observation.start("workshop.sqs.order-handler", registry)
        try {
            parentMatched.set(
                processObservation != null &&
                    childObservation !== Observation.NOOP &&
                    childObservation.context.parentObservation === processObservation,
            )
            val event = objectMapper.readValue(message.body, OrderNotificationEvent::class.java)
            handler.handle(event)
            handled += event
            acknowledgement.acknowledge()
        } catch (e: CancellationException) {
            childObservation.error(e)
            throw e
        } catch (e: Throwable) {
            childObservation.error(e)
            throw e
        } finally {
            childObservation.stop()
        }
    }

    companion object {
        const val LISTENER_ID: String = "order-notification-observation"
    }
}
