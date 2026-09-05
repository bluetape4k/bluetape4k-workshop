package io.bluetape4k.workshop.messaging.nats

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.nats.client.ConsumeOptions
import io.nats.client.ConsumerContext
import io.nats.client.IterableConsumer
import io.nats.client.JetStream
import io.nats.client.JetStreamSubscription
import io.nats.client.Message
import io.nats.client.PushSubscribeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class JetStreamConsumerFlowsTest {

    @Test
    fun `pull flow is cold and creates a fresh adapter handle per collection`() = runTest {
        val context = mockk<ConsumerContext>()
        val firstConsumer = mockk<IterableConsumer>()
        val secondConsumer = mockk<IterableConsumer>()
        val firstMessage = mockk<Message>()
        val secondMessage = mockk<Message>()
        every { context.iterate(any<ConsumeOptions>()) } returnsMany listOf(firstConsumer, secondConsumer)
        every { firstConsumer.nextMessage(any<Duration>()) } returns firstMessage
        every { secondConsumer.nextMessage(any<Duration>()) } returns secondMessage
        every { firstConsumer.close() } just runs
        every { secondConsumer.close() } just runs

        val flow = JetStreamConsumerFlows.pull(context, NatsFlowLimits(capacity = 1))
        verify(exactly = 0) { context.iterate(any<ConsumeOptions>()) }

        flow.take(1).toList()
        flow.take(1).toList()

        verify(exactly = 2) { context.iterate(any<ConsumeOptions>()) }
        verify(exactly = 1) { firstConsumer.close() }
        verify(exactly = 1) { secondConsumer.close() }
    }

    @Test
    fun `pull cancellation closes the active handle and permits recollection`() = runBlocking {
        val context = mockk<ConsumerContext>()
        val cancelledConsumer = mockk<IterableConsumer>()
        val recollectedConsumer = mockk<IterableConsumer>()
        val message = mockk<Message>()
        val receiveStarted = CountDownLatch(1)
        val receiveInterrupted = CountDownLatch(1)
        every { context.iterate(any<ConsumeOptions>()) } returnsMany listOf(cancelledConsumer, recollectedConsumer)
        every { cancelledConsumer.nextMessage(any<Duration>()) } answers {
            receiveStarted.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5))
            } catch (_: InterruptedException) {
                receiveInterrupted.countDown()
                Thread.currentThread().interrupt()
            }
            null
        }
        every { recollectedConsumer.nextMessage(any<Duration>()) } returns message
        every { cancelledConsumer.close() } just runs
        every { recollectedConsumer.close() } just runs

        val flow = JetStreamConsumerFlows.pull(context, NatsFlowLimits(capacity = 1))
        val active = async(Dispatchers.Default) { flow.collect() }
        check(receiveStarted.await(5, TimeUnit.SECONDS))

        active.cancelAndJoin()
        check(receiveInterrupted.await(1, TimeUnit.SECONDS))
        val recollected = withTimeout(5.seconds) { flow.take(1).toList() }

        check(recollected == listOf(message))
        verify(exactly = 2) { context.iterate(any<ConsumeOptions>()) }
        verify(exactly = 1) { cancelledConsumer.close() }
        verify(exactly = 1) { recollectedConsumer.close() }
    }

    @Test
    fun `push flow is cold and unsubscribes its adapter handle`() = runTest {
        val jetStream = mockk<JetStream>()
        val subscription = mockk<JetStreamSubscription>()
        val message = mockk<Message>()
        every { jetStream.subscribe("orders.created", any<PushSubscribeOptions>()) } returns subscription
        every { subscription.pendingMessageLimit } returns 8
        every { subscription.pendingByteLimit } returns 65_536
        every { subscription.droppedCount } returns 0
        every { subscription.nextMessage(any<Duration>()) } returns message
        every { subscription.unsubscribe() } just runs

        val flow = JetStreamConsumerFlows.push(
            jetStream = jetStream,
            stream = "ORDERS",
            subject = "orders.created",
            limits = NatsFlowLimits(capacity = 1, pendingMessageLimit = 8, pendingByteLimit = 65_536),
        )
        verify(exactly = 0) { jetStream.subscribe(any(), any<PushSubscribeOptions>()) }

        flow.take(1).toList()

        verify(exactly = 1) { jetStream.subscribe("orders.created", any<PushSubscribeOptions>()) }
        verify(exactly = 1) { subscription.unsubscribe() }
    }

    @Test
    fun `push cancellation unsubscribes the active handle and permits recollection`() = runBlocking {
        val jetStream = mockk<JetStream>()
        val cancelledSubscription = mockk<JetStreamSubscription>()
        val recollectedSubscription = mockk<JetStreamSubscription>()
        val message = mockk<Message>()
        val receiveStarted = CountDownLatch(1)
        val receiveInterrupted = CountDownLatch(1)
        every {
            jetStream.subscribe("orders.created", any<PushSubscribeOptions>())
        } returnsMany listOf(cancelledSubscription, recollectedSubscription)
        listOf(cancelledSubscription, recollectedSubscription).forEach { subscription ->
            every { subscription.pendingMessageLimit } returns 8
            every { subscription.pendingByteLimit } returns 65_536
            every { subscription.droppedCount } returns 0
            every { subscription.unsubscribe() } just runs
        }
        every { cancelledSubscription.nextMessage(any<Duration>()) } answers {
            receiveStarted.countDown()
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(5))
            } catch (_: InterruptedException) {
                receiveInterrupted.countDown()
                Thread.currentThread().interrupt()
            }
            null
        }
        every { recollectedSubscription.nextMessage(any<Duration>()) } returns message

        val flow = JetStreamConsumerFlows.push(
            jetStream = jetStream,
            stream = "ORDERS",
            subject = "orders.created",
            limits = NatsFlowLimits(capacity = 1, pendingMessageLimit = 8, pendingByteLimit = 65_536),
        )
        val active = async(Dispatchers.Default) { flow.collect() }
        check(receiveStarted.await(5, TimeUnit.SECONDS))

        active.cancelAndJoin()
        check(receiveInterrupted.await(1, TimeUnit.SECONDS))
        val recollected = withTimeout(5.seconds) { flow.take(1).toList() }

        check(recollected == listOf(message))
        verify(exactly = 2) { jetStream.subscribe("orders.created", any<PushSubscribeOptions>()) }
        verify(exactly = 1) { cancelledSubscription.unsubscribe() }
        verify(exactly = 1) { recollectedSubscription.unsubscribe() }
    }

    @Test
    fun `caller decision maps to ack nak and term`() {
        val ack = mockk<Message>(relaxed = true)
        val nak = mockk<Message>(relaxed = true)
        val term = mockk<Message>(relaxed = true)

        JetStreamConsumerFlows.acknowledge(ack, AckDecision.ACK)
        JetStreamConsumerFlows.acknowledge(nak, AckDecision.NAK)
        JetStreamConsumerFlows.acknowledge(term, AckDecision.TERM)

        verify(exactly = 1) { ack.ack() }
        verify(exactly = 1) { nak.nak() }
        verify(exactly = 1) { term.term() }
    }
}
