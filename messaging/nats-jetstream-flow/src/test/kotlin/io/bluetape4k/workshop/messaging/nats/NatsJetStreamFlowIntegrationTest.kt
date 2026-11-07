package io.bluetape4k.workshop.messaging.nats

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.coroutines.flow.extensions.log
import io.bluetape4k.coroutines.support.awaitUntil
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.nats.client.NatsConsumerFlowException
import io.bluetape4k.nats.client.api.consumerConfiguration
import io.bluetape4k.nats.client.consumerContextOf
import io.bluetape4k.nats.client.createOrReplaceStream
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.testcontainers.mq.NatsServer
import io.nats.client.Nats
import io.nats.client.api.AckPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NatsJetStreamFlowIntegrationTest {

    companion object: KLoggingChannel() {
        private val nats: NatsServer by lazy { NatsServer.Launcher.nats }
        private val sequence = AtomicLong()
    }

    @Test
    fun `pull and push flows preserve order and caller acknowledges`() = runSuspendIO(timeout = 20.seconds) {

        Nats.connect(nats.url).use { connection ->
            val pullNames = names("pull")
            connection.createOrReplaceStream(pullNames.stream, pullNames.subject)
            val jetStream = connection.jetStream()

            repeat(3) {
                jetStream.publish(pullNames.subject, "pull-$it".toUtf8Bytes())
            }
            val consumer = consumerContextOf(
                connection,
                pullNames.stream,
                consumerConfiguration {
                    durable(pullNames.durable)
                    ackPolicy(AckPolicy.Explicit)
                },
            )

            val pull = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 2))
                .log("Pull")
                .take(3)
                .onEach { it.ack() }
                .toList()
            awaitAckDrain(consumer)
            check(pull.map { it.data.toUtf8String() } == listOf("pull-0", "pull-1", "pull-2"))

            val pushNames = names("push")
            connection.createOrReplaceStream(pushNames.stream, pushNames.subject)
            val pushFlow = JetStreamConsumerFlows.push(
                jetStream,
                pushNames.stream,
                pushNames.subject,
                NatsFlowLimits(capacity = 2),
            ).log("Push")

            val collector = async(Dispatchers.Default) {
                pushFlow
                    .take(3)
                    .onEach { it.ack() }
                    .toList()
            }
            try {
                repeat(3) {
                    jetStream.publish(pushNames.subject, "push-$it".toUtf8Bytes())
                }
                val push = collector.await().onEach { log.debug { "message=${it.data.toUtf8String()}" } }
                push.map { it.data.toUtf8String() } shouldBeEqualTo listOf("push-0", "push-1", "push-2")
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `nak redelivers once and caller acknowledges the retry`() = runSuspendIO(timeout = 20.seconds) {
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
                    ackWait(250.millis())
                    maxDeliver(3)
                    maxAckPending(1)
                },
            )
            val flow = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 1)).log("Pull")

            jetStream.publish(names.subject, "retry".toUtf8Bytes())
            var deliveryIndex = 0
            val deliveries = flow.take(2)
                .onEach { message ->
                    val decision = if (deliveryIndex++ == 0) AckDecision.NAK else AckDecision.ACK
                    JetStreamConsumerFlows.acknowledge(message, decision)
                }
                .toList()

            deliveries.map { it.data.toUtf8String() } shouldBeEqualTo listOf("retry", "retry")
            deliveries.map { it.metaData().deliveredCount() } shouldBeEqualTo listOf(1L, 2L)
            awaitAckDrain(consumer)
        }
    }

    @Test
    fun `term finalizes a poison message without redelivery`() = runSuspendIO(timeout = 20.seconds) {
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
                    ackWait(250.millis())
                    maxDeliver(3)
                    maxAckPending(1)
                },
            )

            val flow = JetStreamConsumerFlows.pull(consumer, NatsFlowLimits(capacity = 1)).log("Pull")

            jetStream.publish(names.subject, "poison".toUtf8Bytes())

            val poison = flow.first()
            poison.data.toUtf8String() shouldBeEqualTo "poison"
            poison.metaData().deliveredCount() shouldBeEqualTo 1L

            JetStreamConsumerFlows.acknowledge(poison, AckDecision.TERM)

            val redelivered = withTimeoutOrNull(1.seconds) { flow.first() }
            redelivered.shouldBeNull() // "term() 처리 뒤 message가 재전달되면 안됩니다."

            awaitAckDrain(consumer)
            consumer.consumerInfo.redelivered shouldBeEqualTo 0L
        }
    }

    @Test
    fun `push flow reports a real pending queue drop without hanging`() = runSuspendIO(timeout = 20.seconds) {

        Nats.connect(nats.url).use { connection ->
            val names = names("drop")
            connection.createOrReplaceStream(names.stream, names.subject)
            val jetStream = connection.jetStream()
            val firstDelivery = CompletableDeferred<Unit>()
            val releaseDelivery = CompletableDeferred<Unit>()

            supervisorScope {
                val collector = async(Dispatchers.Default) {
                    JetStreamConsumerFlows
                        .push(
                            jetStream,
                            names.stream,
                            names.subject,
                            NatsFlowLimits(
                                capacity = 1,
                                pendingMessageLimit = 1,
                                pendingByteLimit = 65_536,
                                receiveTimeout = 250.milliseconds,
                            ),
                        )
                        .log("Push")
                        .collect { message ->
                            if (firstDelivery.complete(Unit)) releaseDelivery.await()
                            message.ack()
                        }
                }
                try {
                    jetStream.publish(names.subject, "seed".toUtf8Bytes())
                    firstDelivery.awaitUntil(3.seconds)

                    repeat(20_000) {
                        connection.publish(names.subject, "burst-$it".toUtf8Bytes())
                    }
                    connection.flush(5.seconds())
                    releaseDelivery.complete(Unit)

                    val failure = runCatching { collector.awaitUntil(8.seconds) }.exceptionOrNull()
                    failure.shouldBeInstanceOf<NatsConsumerFlowException>()
                        .droppedMessages shouldBeGreaterThan 0L
                } finally {
                    releaseDelivery.complete(Unit)
                    collector.cancelAndJoin()
                }
            }
        }
    }

    private fun awaitAckDrain(consumer: io.nats.client.ConsumerContext) {
        await atMost 5.seconds withPollInterval 50.milliseconds until {
            consumer.consumerInfo.numAckPending == 0L
        }
    }

    private data class Names(val stream: String, val subject: String, val durable: String)

    private fun names(prefix: String): Names {
        val id = sequence.incrementAndGet()
        return Names("W923_${prefix.uppercase()}_$id", "w923.$prefix.$id", "w923-$prefix-$id")
    }

}
