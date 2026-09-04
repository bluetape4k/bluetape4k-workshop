package io.bluetape4k.workshop.kafka.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.kafka.clients.producer.Callback
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * Kafka producer callback을 collection-scoped [Flow]로 노출합니다.
 *
 * 한 번의 [send] collection마다 producer를 하나 만들고, collection이 정상 완료되거나
 * 취소되면 in-flight callback을 정리한 뒤 bounded `flush`와 `close`를 실행합니다.
 * [maxInFlight]는 producer callback 수를 제한하고 [channelCapacity]는 downstream
 * backpressure를 보존합니다. producer는 호출자가 소유한 instance를 닫지 않고
 * [producerFactory]가 해당 collection에 만든 instance만 닫습니다.
 */
class KafkaProducerFlow(
    private val producerFactory: () -> Producer<String, String>,
    private val channelCapacity: Int = DEFAULT_CHANNEL_CAPACITY,
    private val maxInFlight: Int = DEFAULT_MAX_IN_FLIGHT,
) {

    init {
        require(channelCapacity in 1..MAX_CHANNEL_CAPACITY) {
            "channelCapacity must be between 1 and $MAX_CHANNEL_CAPACITY"
        }
        require(maxInFlight in 1..MAX_IN_FLIGHT) {
            "maxInFlight must be between 1 and $MAX_IN_FLIGHT"
        }
    }

    /**
     * [records]의 producer callback metadata를 cold [Flow]로 변환합니다.
     *
     * callback failure가 발생하면 첫 원인을 terminal cause로 유지하고, 늦게 도착한
     * callback은 이미 완료된 send state를 다시 변경하지 않습니다. callback이 metadata와
     * failure를 모두 전달하지 않는 malformed 결과도 명시적인 실패로 닫습니다.
     */
    fun send(records: Flow<ProducerRecord<String, String>>): Flow<RecordMetadata> =
        callbackFlow {
            var producer: Producer<String, String>? = null
            class DownstreamCancellation(val cause: CancellationException)

            val terminalState = AtomicReference<Any?>(null)
            val permits = Semaphore(maxInFlight)
            val callbackFlowJob = currentCoroutineContext()[Job]

            fun isDownstreamCancelled(): Boolean = terminalState.get() is DownstreamCancellation

            fun terminalCause(): Throwable? = when (val terminal = terminalState.get()) {
                is DownstreamCancellation -> terminal.cause
                is Throwable -> terminal
                else -> null
            }

            class SendState {
                val future = AtomicReference<Future<RecordMetadata>?>(null)
                val completed = AtomicBoolean()
            }

            val inFlight = ConcurrentHashMap.newKeySet<SendState>()
            val upstreamJobRef = AtomicReference<Job?>()

            fun cancelState(state: SendState) {
                state.future.get()?.cancel(false)
                if (state.completed.compareAndSet(false, true)) {
                    inFlight.remove(state)
                    permits.release()
                }
            }

            fun cancelInFlight() {
                inFlight.toList().forEach(::cancelState)
            }

            fun failOnce(cause: Throwable) {
                if (terminalState.compareAndSet(null, cause)) {
                    cancelInFlight()
                    upstreamJobRef.get()?.cancel(CancellationException("producer terminal failure", cause))
                    close(cause)
                }
            }

            fun complete(state: SendState, metadata: RecordMetadata?, cause: Exception?) {
                if (!state.completed.compareAndSet(false, true)) return
                try {
                    when {
                        cause != null -> failOnce(cause.unwrapRecoveredCoroutineCause())
                        metadata != null -> {
                            val result = trySend(metadata)
                            if (result.isFailure && !result.isClosed && !isDownstreamCancelled()) {
                                failOnce(IllegalStateException("Kafka callback buffer is full"))
                            }
                        }

                        else -> failOnce(
                            IllegalStateException("Kafka callback returned neither metadata nor failure")
                        )
                    }
                } finally {
                    inFlight.remove(state)
                    permits.release()
                }
            }

            fun callbackFor(state: SendState): Callback = Callback { metadata, cause ->
                complete(state, metadata, cause)
            }

            val upstreamJob = launch(context = Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    val activeProducer = producerFactory()
                    producer = activeProducer
                    records.collect { record ->
                        permits.acquire()
                        val state = SendState()
                        if (terminalCause() != null || isDownstreamCancelled()) {
                            cancelState(state)
                            ensureActive()
                            return@collect
                        }

                        inFlight += state
                        if (terminalCause() != null || isDownstreamCancelled()) {
                            cancelState(state)
                            ensureActive()
                            return@collect
                        }

                        try {
                            val future = activeProducer.send(record, callbackFor(state))
                            state.future.set(future)
                            if (terminalCause() != null || isDownstreamCancelled()) {
                                cancelState(state)
                            }
                        } catch (cause: CancellationException) {
                            cancelState(state)
                            if (!isDownstreamCancelled()) failOnce(cause.unwrapRecoveredCoroutineCause())
                            throw cause
                        } catch (cause: Throwable) {
                            cancelState(state)
                            failOnce(cause.unwrapRecoveredCoroutineCause())
                            throw cause
                        }
                        ensureActive()
                    }
                } catch (cause: CancellationException) {
                    if (!isDownstreamCancelled()) failOnce(cause.unwrapRecoveredCoroutineCause())
                    throw cause
                } catch (cause: Throwable) {
                    failOnce(cause.unwrapRecoveredCoroutineCause())
                    throw cause
                } finally {
                    producer?.let { activeProducer ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            var cleanupFailure: Throwable? = null
                            var cleanupCancellation: CancellationException? = null
                            try {
                                withTimeout(FLUSH_TIMEOUT) {
                                    while (inFlight.isNotEmpty()) delay(CLEANUP_POLL_MILLIS)
                                    runInterruptible { activeProducer.flush() }
                                }
                            } catch (cause: TimeoutCancellationException) {
                                cleanupFailure = cause.unwrapRecoveredCoroutineCause()
                                cancelInFlight()
                                if (!isDownstreamCancelled()) failOnce(cleanupFailure)
                            } catch (cause: CancellationException) {
                                cleanupFailure = cause.unwrapRecoveredCoroutineCause()
                                cleanupCancellation = cleanupFailure as? CancellationException
                                cancelInFlight()
                                if (!isDownstreamCancelled()) failOnce(cleanupFailure)
                            } catch (cause: Throwable) {
                                cleanupFailure = cause.unwrapRecoveredCoroutineCause()
                                cancelInFlight()
                                if (!isDownstreamCancelled()) failOnce(cleanupFailure)
                            }

                            var closeFailure: Throwable? = null
                            var closeCancellation: CancellationException? = null
                            try {
                                withTimeout(CLOSE_TIMEOUT) {
                                    runInterruptible { activeProducer.close(CLOSE_DURATION) }
                                }
                            } catch (cause: CancellationException) {
                                closeFailure = cause.unwrapRecoveredCoroutineCause()
                                closeCancellation = closeFailure as? CancellationException
                            } catch (cause: Throwable) {
                                closeFailure = cause.unwrapRecoveredCoroutineCause()
                            }

                            val first = terminalCause()
                            if (first != null && cleanupFailure != null && first !== cleanupFailure) {
                                first.addSuppressed(cleanupFailure)
                            }
                            if (first != null && closeFailure != null && first !== closeFailure) {
                                first.addSuppressed(closeFailure)
                            }
                            if (first == null && closeFailure != null && !isDownstreamCancelled()) {
                                failOnce(closeFailure)
                            }
                            if (terminalCause() == null && !isDownstreamCancelled()) close()
                            if (cleanupCancellation != null && terminalCause() == null) {
                                throw cleanupCancellation
                            }
                            if (closeCancellation != null && terminalCause() == null) {
                                throw closeCancellation
                            }
                        }
                    }
                }
            }

            upstreamJobRef.set(upstreamJob)
            upstreamJob.start()
            awaitClose {
                val cancellation = callbackFlowJob
                    ?.takeIf { it.isCancelled }
                    ?.getCancellationException()
                    ?: CancellationException("collector cancelled")
                terminalState.compareAndSet(null, DownstreamCancellation(cancellation))
                cancelInFlight()
                upstreamJob.cancel(cancellation)
            }
        }.buffer(channelCapacity, onBufferOverflow = BufferOverflow.SUSPEND)
            .catch { cause -> throw cause.unwrapRecoveredCoroutineCause() }

    private fun Throwable.unwrapRecoveredCoroutineCause(): Throwable {
        val nested = cause ?: return this
        val recoveredAtCoroutineBoundary = stackTrace.any { it.className == "_COROUTINE._BOUNDARY._" }
        if (!recoveredAtCoroutineBoundary || nested.javaClass != javaClass || nested.message != message) {
            return this
        }
        suppressed.forEach { suppressedCause ->
            if (suppressedCause !== nested && nested.suppressed.none { it === suppressedCause }) {
                nested.addSuppressed(suppressedCause)
            }
        }
        return nested
    }

    private companion object {
        private const val DEFAULT_CHANNEL_CAPACITY = 16
        private const val DEFAULT_MAX_IN_FLIGHT = 16
        private const val MAX_CHANNEL_CAPACITY = 64
        private const val MAX_IN_FLIGHT = 64
        private const val CLEANUP_POLL_MILLIS = 10L
        private val FLUSH_TIMEOUT = 30.seconds
        private val CLOSE_TIMEOUT = 5.seconds
        private val CLOSE_DURATION = Duration.ofSeconds(5)
    }
}
