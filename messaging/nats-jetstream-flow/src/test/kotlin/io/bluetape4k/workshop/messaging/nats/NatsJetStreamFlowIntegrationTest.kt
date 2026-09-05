package io.bluetape4k.workshop.messaging.nats

import io.bluetape4k.nats.client.consumerContextOf
import io.bluetape4k.nats.client.createOrReplaceStream
import io.bluetape4k.nats.client.api.consumerConfiguration
import io.bluetape4k.nats.client.NatsConsumerFlowException
import io.bluetape4k.testcontainers.mq.NatsServer
import io.nats.client.Nats
import io.nats.client.api.AckPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NatsJetStreamFlowIntegrationTest {

    @Test
    fun `pull and push flows preserve order and caller acknowledges`() = runBlocking {
        withTimeout(20.seconds) {
            Nats.connect(nats.url).use { connection ->
                val pullNames = names("pull")
                connection.createOrReplaceStream(pullNames.stream, pullNames.subject)
                val jetStream = connection.jetStream()
                repeat(3) { jetStream.publish(pullNames.subject, "pull-$it".encodeToByteArray()) }
                val consumer = consumerContextOf(
                    connection,
                    pullNames.stream,
                    consumerConfiguration {
                        durable(pullNames.durable)
                        ackPolicy(AckPolicy.Explicit)
                    },
                )

                val pull = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 2))
                    .take(3)
                    .onEach { it.ack() }
                    .toList()
                awaitAckDrain(consumer)
                check(pull.map { it.data.decodeToString() } == listOf("pull-0", "pull-1", "pull-2"))

                val pushNames = names("push")
                connection.createOrReplaceStream(pushNames.stream, pushNames.subject)
                val pushFlow = JetStreamConsumerFlows.push(
                    jetStream,
                    pushNames.stream,
                    pushNames.subject,
                    NatsFlowLimits(capacity = 2),
                )
                val collector = async(Dispatchers.Default) {
                    pushFlow.take(3).onEach { it.ack() }.toList()
                }
                try {
                    repeat(3) { jetStream.publish(pushNames.subject, "push-$it".encodeToByteArray()) }
                    val push = collector.await()
                    check(push.map { it.data.decodeToString() } == listOf("push-0", "push-1", "push-2"))
                } finally {
                    collector.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `nak redelivers once and caller acknowledges the retry`() = runBlocking {
        withTimeout(20.seconds) {
            Nats.connect(nats.url).use { connection ->
                val names = names("nak")
                connection.createOrReplaceStream(names.stream, names.subject)
                val jetStream = connection.jetStream()
                val consumer = consumerContextOf(
                    connection,
                    names.stream,
                    consumerConfiguration {
                        durable(names.durable)
                        ackPolicy(AckPolicy.Explicit)
                        ackWait(Duration.ofMillis(250))
                        maxDeliver(3)
                        maxAckPending(1)
                    },
                )
                val flow = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 1))

                jetStream.publish(names.subject, "retry".encodeToByteArray())
                var deliveryIndex = 0
                val deliveries = flow.take(2).onEach { message ->
                    val decision = if (deliveryIndex++ == 0) AckDecision.NAK else AckDecision.ACK
                    JetStreamConsumerFlows.acknowledge(message, decision)
                }.toList()

                check(deliveries.map { it.data.decodeToString() } == listOf("retry", "retry"))
                check(deliveries.map { it.metaData().deliveredCount() } == listOf(1L, 2L))
                awaitAckDrain(consumer)
            }
        }
    }

    @Test
    fun `term finalizes a poison message without redelivery`() = runBlocking {
        withTimeout(20.seconds) {
            Nats.connect(nats.url).use { connection ->
                val names = names("term")
                connection.createOrReplaceStream(names.stream, names.subject)
                val jetStream = connection.jetStream()
                val consumer = consumerContextOf(
                    connection,
                    names.stream,
                    consumerConfiguration {
                        durable(names.durable)
                        ackPolicy(AckPolicy.Explicit)
                        ackWait(Duration.ofMillis(250))
                        maxDeliver(3)
                        maxAckPending(1)
                    },
                )
                val flow = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 1))
                jetStream.publish(names.subject, "poison".encodeToByteArray())
                val poison = flow.first()
                check(poison.data.decodeToString() == "poison")
                check(poison.metaData().deliveredCount() == 1L)
                JetStreamConsumerFlows.acknowledge(poison, AckDecision.TERM)
                val redelivered = withTimeoutOrNull(1.seconds) { flow.first() }
                check(redelivered == null) { "term() 처리 뒤 message가 재전달되었습니다." }
                awaitAckDrain(consumer)
                check(consumer.consumerInfo.redelivered == 0L)
            }
        }
    }

    @Test
    fun `push flow reports a real pending queue drop without hanging`() = runBlocking {
        withTimeout(20.seconds) {
            Nats.connect(nats.url).use { connection ->
                val names = names("drop")
                connection.createOrReplaceStream(names.stream, names.subject)
                val jetStream = connection.jetStream()
                val firstDelivery = CompletableDeferred<Unit>()
                val releaseDelivery = CompletableDeferred<Unit>()
                supervisorScope {
                    val collector = async(Dispatchers.Default) {
                        JetStreamConsumerFlows.push(
                            jetStream,
                            names.stream,
                            names.subject,
                            NatsFlowLimits(
                                capacity = 1,
                                pendingMessageLimit = 1,
                                pendingByteLimit = 65_536,
                                receiveTimeout = 250.milliseconds,
                            ),
                        ).collect { message ->
                            if (firstDelivery.complete(Unit)) releaseDelivery.await()
                            message.ack()
                        }
                    }
                    try {
                        jetStream.publish(names.subject, "seed".encodeToByteArray())
                        withTimeout(3.seconds) { firstDelivery.await() }
                        repeat(20_000) { connection.publish(names.subject, "burst-$it".encodeToByteArray()) }
                        connection.flush(Duration.ofSeconds(5))
                        releaseDelivery.complete(Unit)
                        val failure = runCatching { withTimeout(8.seconds) { collector.await() } }.exceptionOrNull()
                        check(failure is NatsConsumerFlowException && failure.droppedMessages > 0) {
                            "pending queue drop을 bounded 시간 안에 관찰하지 못했습니다: $failure"
                        }
                    } finally {
                        releaseDelivery.complete(Unit)
                        collector.cancelAndJoin()
                    }
                }
            }
        }
    }

    private suspend fun awaitAckDrain(consumer: io.nats.client.ConsumerContext) {
        withTimeout(5.seconds) {
            while (consumer.consumerInfo.numAckPending != 0L) {
                delay(50.milliseconds)
            }
        }
    }

    private data class Names(val stream: String, val subject: String, val durable: String)

    private fun names(prefix: String): Names {
        val id = sequence.incrementAndGet()
        return Names("W923_${prefix.uppercase()}_$id", "w923.$prefix.$id", "w923-$prefix-$id")
    }

    companion object {
        private val nats: NatsServer by lazy { NatsServer.Launcher.nats }
        private val sequence = AtomicLong()
    }
}
