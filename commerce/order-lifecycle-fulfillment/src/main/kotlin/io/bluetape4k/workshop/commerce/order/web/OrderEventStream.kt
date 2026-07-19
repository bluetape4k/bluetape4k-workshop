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
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class StreamCapacityExceeded : IllegalStateException("SSE connection capacity exceeded")

internal class StreamShuttingDown : IllegalStateException("SSE stream is shutting down")

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
    private val maxConcurrentPolls =
        environment.getProperty("order-lifecycle.sse.max-concurrent-polls", Int::class.java, 4).also {
            require(it > 0) { "order-lifecycle.sse.max-concurrent-polls must be positive" }
        }
    private val pollPermits = Semaphore(maxConcurrentPolls, true)
    private val activeConnections = AtomicInteger()
    private val connections = ConcurrentHashMap<SseEmitter, StreamConnection>()
    private val feeds = ConcurrentHashMap<UUID, OrderFeed>()
    private val draining = AtomicBoolean()
    private val lifecycleLock = Any()

    fun open(
        orderId: UUID,
        lastEventId: Long,
    ): SseEmitter {
        val emitter = SseEmitter(timeout.toMillis())
        val connection = StreamConnection(emitter, orderId, lastEventId, heartbeatInterval)
        synchronized(lifecycleLock) {
            if (draining.get()) throw StreamShuttingDown()
            if (activeConnections.incrementAndGet() > maxConnections) {
                activeConnections.decrementAndGet()
                log.warn { "sse_capacity_exceeded orderId=$orderId maxConnections=$maxConnections" }
                throw StreamCapacityExceeded()
            }
            connections[emitter] = connection
        }
        val release = {
            release(connection)
            Unit
        }
        emitter.onCompletion(release)
        emitter.onTimeout(release)
        emitter.onError { release() }

        try {
            val snapshot = queries.snapshot(orderId)
            if (draining.get()) throw StreamShuttingDown()
            // The snapshot already contains the current audit view, so its cursor is authoritative.
            // Trusting an arbitrary future Last-Event-ID would suppress valid subsequent events.
            val snapshotCursor = snapshot.audit.maxOfOrNull { it.id } ?: 0L
            connection.cursor.set(snapshotCursor)
            emitter.send(
                SseEmitter
                    .event()
                    .id(snapshotCursor.toString())
                    .name("snapshot")
                    .reconnectTime(1_000)
                    .data(snapshot)
            )

            synchronized(lifecycleLock) {
                if (draining.get()) throw StreamShuttingDown()
                val feed =
                    feeds.compute(orderId) { _, current ->
                        current?.takeUnless { it.closed.get() } ?: OrderFeed(orderId)
                    }!!
                connection.feed = feed
                feed.connections[emitter] = connection
                start(feed)
            }
            log.info {
                "sse_connection_opened orderId=$orderId lastEventId=$lastEventId cursor=$snapshotCursor " +
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
        val (drainingConnections, drainingFeeds) =
            synchronized(lifecycleLock) {
                if (!draining.compareAndSet(false, true)) return
                val registeredConnections = connections.values.toList()
                registeredConnections.forEach { connection ->
                    if (connection.closed.compareAndSet(false, true)) {
                        activeConnections.decrementAndGet()
                    }
                }
                val registeredFeeds = feeds.values.toList()
                registeredFeeds.forEach { it.closed.set(true) }
                registeredConnections to registeredFeeds
            }
        // Cancel every poller before completing emitters so shutdown has one bounded deadline,
        // independent of the number of connected clients.
        drainingFeeds.forEach { it.future?.cancel(true) }
        drainingConnections.forEach { it.emitter.complete() }
        connections.clear()
        feeds.clear()
        log.info { "sse_stream_shutdown drainedConnections=${drainingConnections.size}" }
    }

    private fun start(feed: OrderFeed) {
        if (!feed.started.compareAndSet(false, true)) return
        feed.future = orderLifecycleExecutor.submit { poll(feed) }
        if (feed.closed.get()) feed.future?.cancel(true)
    }

    private fun poll(feed: OrderFeed) {
        try {
            while (!feed.closed.get()) {
                val subscribers = feed.connections.values.filterNot { it.closed.get() }
                if (subscribers.isEmpty()) return
                val cursor = subscribers.minOf { it.cursor.get() }
                pollPermits.acquire()
                val events =
                    try {
                        queries.auditAfter(feed.orderId, cursor)
                    } finally {
                        pollPermits.release()
                    }
                val now = System.nanoTime()
                subscribers.forEach { connection ->
                    if (!connection.closed.get()) {
                        try {
                            events.asSequence().filter { it.id > connection.cursor.get() }.forEach { event ->
                                connection.emitter.send(
                                    SseEmitter
                                        .event()
                                        .id(event.id.toString())
                                        .name("audit")
                                        .data(event)
                                )
                                connection.cursor.set(event.id)
                            }
                            if (now >= connection.heartbeatAt.get()) {
                                connection.emitter.send(SseEmitter.event().comment("heartbeat"))
                                connection.heartbeatAt.set(now + heartbeatInterval.toNanos())
                            }
                        } catch (failure: Exception) {
                            log.warn(failure) {
                                "sse_connection_failed orderId=${feed.orderId} cursor=${connection.cursor.get()}"
                            }
                            connection.emitter.completeWithError(failure)
                            release(connection)
                        }
                    }
                }
                Thread.sleep(pollInterval)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Exception) {
            log.warn(failure) { "sse_feed_failed orderId=${feed.orderId}" }
            feed.connections.values.forEach { connection ->
                connection.emitter.completeWithError(failure)
                release(connection)
            }
        } finally {
            feeds.remove(feed.orderId, feed)
            feed.connections.values.forEach { connection ->
                connection.emitter.complete()
                release(connection)
            }
        }
    }

    private fun release(connection: StreamConnection) {
        if (connection.closed.compareAndSet(false, true)) {
            val remaining = activeConnections.decrementAndGet()
            log.debug {
                "sse_connection_released orderId=${connection.orderId} activeConnections=$remaining"
            }
        }
        connections.remove(connection.emitter, connection)
        connection.feed?.let { feed ->
            feed.connections.remove(connection.emitter, connection)
            if (feed.connections.isEmpty() && feed.closed.compareAndSet(false, true)) {
                feeds.remove(feed.orderId, feed)
                feed.future?.cancel(true)
            }
        }
    }

    private class StreamConnection(
        val emitter: SseEmitter,
        val orderId: UUID,
        initialCursor: Long,
        heartbeatInterval: Duration,
    ) {
        val closed = AtomicBoolean()
        val cursor = AtomicLong(initialCursor)
        val heartbeatAt = AtomicLong(System.nanoTime() + heartbeatInterval.toNanos())

        @Volatile
        var feed: OrderFeed? = null
    }

    private class OrderFeed(
        val orderId: UUID,
    ) {
        val started = AtomicBoolean()
        val closed = AtomicBoolean()
        val connections = ConcurrentHashMap<SseEmitter, StreamConnection>()

        @Volatile
        var future: Future<*>? = null
    }

    companion object : KLogging()
}
