package io.bluetape4k.workshop.commerce.order.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.order.query.OrderLifecycleQueryService
import org.springframework.beans.factory.DisposableBean
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class StreamCapacityExceeded : IllegalStateException("SSE connection capacity exceeded")

@Component
internal class OrderEventStream(
    private val queries: OrderLifecycleQueryService,
    private val orderLifecycleExecutor: ExecutorService,
    environment: Environment,
) : DisposableBean {
    private val timeout =
        environment.getProperty(
            "order-lifecycle.sse.timeout",
            Duration::class.java,
            Duration.ofSeconds(60)
        )
    private val pollInterval =
        environment.getProperty(
            "order-lifecycle.sse.poll-interval",
            Duration::class.java,
            Duration.ofMillis(250)
        )
    private val heartbeatInterval =
        environment.getProperty(
            "order-lifecycle.sse.heartbeat-interval",
            Duration::class.java,
            Duration.ofSeconds(15)
        )
    private val maxConnections = environment.getProperty("order-lifecycle.sse.max-connections", Int::class.java, 1_000)
    private val activeConnections = AtomicInteger()
    private val connections = ConcurrentHashMap<SseEmitter, StreamConnection>()

    fun open(
        orderId: UUID,
        lastEventId: Long,
    ): SseEmitter {
        if (activeConnections.incrementAndGet() > maxConnections) {
            activeConnections.decrementAndGet()
            log.warn { "sse_capacity_exceeded orderId=$orderId maxConnections=$maxConnections" }
            throw StreamCapacityExceeded()
        }

        val emitter = SseEmitter(timeout.toMillis())
        val connection = StreamConnection()
        connections[emitter] = connection
        val release = {
            if (connection.closed.compareAndSet(false, true)) {
                val remaining = activeConnections.decrementAndGet()
                log.debug { "sse_connection_released orderId=$orderId activeConnections=$remaining" }
            }
            connections.remove(emitter)
            Unit
        }
        emitter.onCompletion(release)
        emitter.onTimeout(release)
        emitter.onError { release() }

        try {
            emitter.send(
                SseEmitter
                    .event()
                    .id(lastEventId.toString())
                    .name("snapshot")
                    .reconnectTime(1_000)
                    .data(queries.snapshot(orderId))
            )

            connection.future =
                orderLifecycleExecutor.submit {
                    var cursor = lastEventId
                    var heartbeatAt = System.nanoTime() + heartbeatInterval.toNanos()
                    try {
                        while (!connection.closed.get()) {
                            queries.auditAfter(orderId, cursor).forEach { event ->
                                cursor = event.id
                                emitter.send(
                                    SseEmitter
                                        .event()
                                        .id(cursor.toString())
                                        .name("audit")
                                        .data(event)
                                )
                            }
                            if (System.nanoTime() >= heartbeatAt) {
                                emitter.send(SseEmitter.event().comment("heartbeat"))
                                heartbeatAt = System.nanoTime() + heartbeatInterval.toNanos()
                            }
                            Thread.sleep(pollInterval)
                        }
                        emitter.complete()
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        emitter.complete()
                    } catch (failure: Exception) {
                        emitter.completeWithError(failure)
                    } finally {
                        release()
                    }
                }
            log.info {
                "sse_connection_opened orderId=$orderId lastEventId=$lastEventId " +
                    "activeConnections=${activeConnections.get()}"
            }
        } catch (failure: Exception) {
            release()
            log.warn(failure) { "sse_connection_open_failed orderId=$orderId lastEventId=$lastEventId" }
            throw failure
        }
        return emitter
    }

    override fun destroy() {
        val draining = connections.entries.toList()
        draining.forEach { (_, connection) ->
            if (connection.closed.compareAndSet(false, true)) activeConnections.decrementAndGet()
        }
        draining.forEach { (emitter, connection) ->
            val future = connection.future
            if (future != null) {
                try {
                    future.get(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    future.cancel(true)
                }
            }
            emitter.complete()
        }
        connections.clear()
        log.info { "sse_stream_shutdown drainedConnections=${draining.size}" }
    }

    private class StreamConnection {
        val closed = AtomicBoolean()

        @Volatile
        var future: Future<*>? = null
    }

    companion object : KLogging()
}
