package io.bluetape4k.workshop.kafka.flow

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class KafkaProducerFlowTest {

    @Test
    fun `producer callback metadata is exposed as a flow`() = runSuspendIO {
        val producer = mockk<Producer<String, String>>(relaxed = true)
        val metadata = mockk<RecordMetadata>(relaxed = true)
        every { producer.send(any<ProducerRecord<String, String>>(), any<Callback>()) } answers {
            secondArg<Callback>().onCompletion(metadata, null)
            CompletableFuture.completedFuture(metadata)
        }

        val records = flowOf(ProducerRecord("pingpong", "key", "value"))
        val result = KafkaProducerFlow(
            producerFactory = { producer },
            channelCapacity = 1,
            maxInFlight = 1,
        ).send(records).toList()

        result shouldBeEqualTo listOf(metadata)
    }

    @Test
    fun `normal completion drains callbacks and closes collection owned producer once`() = runSuspendIO {
        val tracking = TrackingProducer()
        val result = KafkaProducerFlow(
            producerFactory = { tracking.producer },
            channelCapacity = 2,
            maxInFlight = 1,
        ).send(flowOf(record("one"), record("two"))).toList()

        result.size shouldBeEqualTo 2
        tracking.callbackCount.get() shouldBeEqualTo 2
        tracking.flushCount.get() shouldBeEqualTo 1
        tracking.closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `callback failure preserves first cause and closes producer once`() = runSuspendIO {
        val failure = IllegalStateException("callback failure")
        val tracking = TrackingProducer(callbackError = failure)

        val observed = io.bluetape4k.assertions.assertFailsWith<IllegalStateException> {
            KafkaProducerFlow(producerFactory = { tracking.producer })
                .send(flowOf(record("failure")))
                .toList()
        }

        observed.message shouldBeEqualTo failure.message
        (observed === failure || observed.cause === failure).shouldBeTrue()
        tracking.closeCount.get() shouldBeEqualTo 1
        tracking.callbackCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `malformed callback without metadata or failure is terminal`() = runSuspendIO {
        val tracking = TrackingProducer(callbackWithoutMetadata = true)

        val observed = io.bluetape4k.assertions.assertFailsWith<IllegalStateException> {
            KafkaProducerFlow(producerFactory = { tracking.producer })
                .send(flowOf(record("malformed")))
                .toList()
        }

        observed.message shouldBeEqualTo "Kafka callback returned neither metadata nor failure"
        tracking.closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `backpressure failure does not drop callback and closes producer once`() = runSuspendIO {
        supervisorScope {
            val tracking = TrackingProducer(holdCallbacks = true)
            val collectorGate = CompletableDeferred<Unit>()
            val firstItemReceived = CompletableDeferred<Unit>()
            val collection = async(start = CoroutineStart.UNDISPATCHED) {
                KafkaProducerFlow(
                    producerFactory = { tracking.producer },
                    channelCapacity = 1,
                    maxInFlight = 2,
                ).send(flowOf(record("one"), record("two"), record("three")))
                    .collectIndexed { index, _ ->
                        if (index == 0) {
                            firstItemReceived.complete(Unit)
                            collectorGate.await()
                        }
                    }
            }

            withTimeout(5.seconds) { tracking.twoSendsStarted.await() }
            tracking.fireCallback()
            withTimeout(5.seconds) { firstItemReceived.await() }
            withTimeout(5.seconds) { tracking.allSendsStarted.await() }
            tracking.fireCallback()
            tracking.fireCallback()
            collectorGate.complete(Unit)

            val observed = io.bluetape4k.assertions.assertFailsWith<IllegalStateException> { collection.await() }
            observed.message shouldBeEqualTo "Kafka callback buffer is full"
            tracking.callbackCount.get() shouldBeEqualTo 3
            tracking.closeCount.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `collector cancellation cancels pending send and ignores late callback`() = runSuspendIO {
        val tracking = TrackingProducer(holdCallbacks = true)
        val cancellation = CancellationException("collector cancelled")
        val collection = async(start = CoroutineStart.UNDISPATCHED) {
            KafkaProducerFlow(producerFactory = { tracking.producer })
                .send(flowOf(record("cancel")))
                .toList()
        }

        withTimeout(5.seconds) { tracking.sendStarted.await() }
        collection.cancel(cancellation)
        io.bluetape4k.assertions.assertFailsWith<CancellationException> { collection.await() }
        waitUntil { tracking.closeCount.get() == 1 }

        tracking.pendingFuture.isCancelled.shouldBeTrue()
        tracking.fireLateCallback()
        tracking.lateCallbackCount.get() shouldBeEqualTo 1
        tracking.callbackCount.get() shouldBeEqualTo 0
    }

    @Test
    fun `upstream failure remains primary and close failure is suppressed`() = runSuspendIO {
        val upstreamFailure = IllegalArgumentException("upstream failure")
        val closeFailure = IllegalStateException("close failure")
        val tracking = TrackingProducer(closeError = closeFailure)

        val observed = io.bluetape4k.assertions.assertFailsWith<IllegalArgumentException> {
            KafkaProducerFlow(producerFactory = { tracking.producer })
                .send(flow {
                    emit(record("before-failure"))
                    throw upstreamFailure
                })
                .toList()
        }

        observed.message shouldBeEqualTo upstreamFailure.message
        (observed === upstreamFailure || observed.cause === upstreamFailure).shouldBeTrue()
        (observed.suppressed + (observed.cause?.suppressed ?: emptyArray()))
            .any { it === closeFailure || it.cause === closeFailure }
            .shouldBeTrue()
        tracking.closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `flush failure becomes terminal after callbacks drain`() = runSuspendIO {
        val flushFailure = IllegalStateException("flush failure")
        val tracking = TrackingProducer(flushError = flushFailure)

        val observed = io.bluetape4k.assertions.assertFailsWith<IllegalStateException> {
            KafkaProducerFlow(producerFactory = { tracking.producer })
                .send(flowOf(record("flush-failure")))
                .toList()
        }

        observed.message shouldBeEqualTo flushFailure.message
        observed.assertIdentityOrEquivalentCause(flushFailure)
        tracking.flushCount.get() shouldBeEqualTo 1
        tracking.closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `invalid channel and in-flight bounds are rejected`() = runTest {
        val records = flowOf(record("invalid"))
        io.bluetape4k.assertions.assertFailsWith<IllegalArgumentException> {
            KafkaProducerFlow({ mockk(relaxed = true) }, channelCapacity = 0).send(records)
        }
        io.bluetape4k.assertions.assertFailsWith<IllegalArgumentException> {
            KafkaProducerFlow({ mockk(relaxed = true) }, maxInFlight = 65).send(records)
        }
    }

    @Test
    fun `real Kafka callbacks become metadata flow`() = runSuspendIO(timeout = 120.seconds) {
        val topic = "callback-flow-${java.util.UUID.randomUUID()}"
        val records = (0 until 4).map { index ->
            ProducerRecord(topic, "key-$index", "value-$index")
        }
        val consumer = KafkaServer.Launcher.createStringConsumer()
        try {
            consumer.subscribe(listOf(topic))
            val metadata = KafkaProducerFlow(
                producerFactory = { KafkaServer.Launcher.createStringProducer() },
            ).send(records.asFlow()).toList()

            metadata.size shouldBeEqualTo records.size
            withTimeout(10.seconds) {
                var received = 0
                while (received < records.size) {
                    received += consumer.poll(Duration.ofMillis(250)).count()
                }
            }
        } finally {
            consumer.close()
        }
    }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        withTimeout(5.seconds) {
            while (!predicate()) delay(10)
        }
    }

    private fun record(value: String): ProducerRecord<String, String> =
        ProducerRecord("callback-flow-test", "key", value)

    /**
     * 코루틴 suspend 경계가 같은 예외를 복구하며 복사할 수 있으므로 identity 또는
     * 동일한 type/message의 cause가 전달되었는지 확인합니다.
     */
    private fun Throwable.assertIdentityOrEquivalentCause(expected: Throwable) {
        var current: Throwable? = this
        repeat(MAX_CAUSE_DEPTH) {
            current?.let { candidate ->
                if (candidate === expected ||
                    (candidate::class == expected::class && candidate.message == expected.message)
                ) return
            }
            current = current?.cause
        }
        error("Expected ${expected::class.simpleName} cause '${expected.message}' in the exception chain")
    }

    private class TrackingProducer(
        private val callbackError: Exception? = null,
        private val sendError: Exception? = null,
        private val flushError: Exception? = null,
        private val closeError: Exception? = null,
        private val callbackWithoutMetadata: Boolean = false,
        private val holdCallbacks: Boolean = false,
    ) {
        val producer: Producer<String, String> = mockk(relaxed = true)
        val callbackCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val flushCount = AtomicInteger()
        val lateCallbackCount = AtomicInteger()
        private val sendCount = AtomicInteger()
        val sendStarted = CompletableDeferred<Unit>()
        val twoSendsStarted = CompletableDeferred<Unit>()
        val allSendsStarted = CompletableDeferred<Unit>()
        val pendingFuture: CompletableFuture<RecordMetadata>
            get() = pendingSends.peek() ?: error("no pending Kafka send")

        private val callbackClosed = AtomicBoolean()
        private val metadata = mockk<RecordMetadata>(relaxed = true)
        private val pendingSends = ConcurrentLinkedQueue<CompletableFuture<RecordMetadata>>()
        private val pendingCallbacks = ConcurrentLinkedQueue<Callback>()

        init {
            every { producer.send(any<ProducerRecord<String, String>>(), any()) } answers {
                sendError?.let { throw it }
                val callback = secondArg<Callback>()
                val index = sendCount.incrementAndGet()
                if (holdCallbacks) {
                    val future = CompletableFuture<RecordMetadata>()
                    pendingSends += future
                    pendingCallbacks += callback
                    sendStarted.complete(Unit)
                    when (index) {
                        2 -> twoSendsStarted.complete(Unit)
                        3 -> allSendsStarted.complete(Unit)
                    }
                    future
                } else {
                    callbackCount.incrementAndGet()
                    callback.onCompletion(if (callbackWithoutMetadata) null else metadata, callbackError)
                    CompletableFuture.completedFuture(metadata)
                }
            }
            every { producer.flush() } answers {
                flushCount.incrementAndGet()
                flushError?.let { throw it }
            }
            every { producer.close(any<Duration>()) } answers {
                closeCount.incrementAndGet()
                callbackClosed.set(true)
                closeError?.let { throw it }
            }
        }

        fun fireCallback() {
            val callback = pendingCallbacks.poll() ?: return
            val future = pendingSends.poll() ?: error("no pending Kafka future")
            callbackCount.incrementAndGet()
            callback.onCompletion(metadata, null)
            future.complete(metadata)
        }

        fun fireLateCallback() {
            val callback = pendingCallbacks.poll() ?: return
            val future = pendingSends.poll() ?: error("no pending Kafka future")
            if (callbackClosed.get()) lateCallbackCount.incrementAndGet()
            callback.onCompletion(metadata, null)
            future.complete(metadata)
        }
    }

    private companion object {
        private const val MAX_CAUSE_DEPTH = 8
    }
}
