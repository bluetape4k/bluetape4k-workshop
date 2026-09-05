package io.bluetape4k.workshop.messaging.nats

import io.bluetape4k.nats.client.consumeAsFlow
import io.bluetape4k.nats.client.pushSubscriptionOptions
import io.nats.client.ConsumerContext
import io.nats.client.JetStream
import io.nats.client.Message
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * NATS adapter와 Flow buffer가 보유할 수 있는 message 수와 receive 주기를 제한합니다.
 */
data class NatsFlowLimits(
    val capacity: Int = 32,
    val pendingMessageLimit: Long = 1_024,
    val pendingByteLimit: Long = 16L * 1024 * 1024,
    val receiveTimeout: Duration = 1.seconds,
)

/**
 * 업무 처리 결과에 따라 caller가 선택하는 JetStream 승인 동작입니다.
 */
enum class AckDecision {
    ACK,
    NAK,
    TERM,
}

/**
 * dependencies 2.0.0의 공개 NATS Flow를 소비자 경계에서 조립합니다.
 *
 * Flow adapter는 message를 자동 승인하지 않으며, adapter가 만든 handle만 collection
 * 종료 시 정리합니다. Connection과 [ConsumerContext]의 소유권은 caller에게 있습니다.
 */
object JetStreamConsumerFlows {

    /** Pull consumer를 bounded cold Flow로 노출합니다. */
    fun pull(
        consumer: ConsumerContext,
        limits: NatsFlowLimits = NatsFlowLimits(),
    ): Flow<Message> = consumer.consumeAsFlow(
        capacity = limits.capacity,
        receiveTimeout = limits.receiveTimeout,
    )

    /** Push subscription을 bounded cold Flow로 노출합니다. */
    fun push(
        jetStream: JetStream,
        stream: String,
        subject: String,
        limits: NatsFlowLimits = NatsFlowLimits(),
    ): Flow<Message> {
        val options = pushSubscriptionOptions {
            stream(stream)
            pendingMessageLimit(limits.pendingMessageLimit)
            pendingByteLimit(limits.pendingByteLimit)
        }
        return jetStream.consumeAsFlow(
            subject = subject,
            options = options,
            capacity = limits.capacity,
            receiveTimeout = limits.receiveTimeout,
        )
    }

    /** 처리 결과를 명시적인 JetStream 승인 동작으로 변환합니다. */
    fun acknowledge(message: Message, decision: AckDecision) {
        when (decision) {
            AckDecision.ACK -> message.ack()
            AckDecision.NAK -> message.nak()
            AckDecision.TERM -> message.term()
        }
    }
}
