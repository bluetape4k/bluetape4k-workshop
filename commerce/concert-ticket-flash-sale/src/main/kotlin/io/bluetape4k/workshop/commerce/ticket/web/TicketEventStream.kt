package io.bluetape4k.workshop.commerce.ticket.web

import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface StreamScope {
    data class PublicSale(val saleId: UUID) : StreamScope
    data class OwnerAttempt(val attemptId: UUID, val buyerSubjectId: UUID) : StreamScope
}

data class TicketStreamEvent(
    val sequence: Long,
    val type: String,
    val payload: Map<String, String>,
    val serverTime: Instant,
)

data class TicketStreamSnapshot(
    val payload: Map<String, String>,
    val highWater: Long,
    val serverTime: Instant,
)

class TicketStreamCapacityExceeded : IllegalStateException("ticket_stream_capacity_exceeded")
class TicketStreamSlowConsumer : IllegalStateException("ticket_stream_slow_consumer")

/** Snapshot-first, bounded broadcaster. No JDBC resource is retained while a client is connected. */
class TicketEventStream(
    private val queueSize: Int,
    maxConnections: Int,
    private val retainedEvents: Int = 1_024,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = Any()
    private val sequence = AtomicLong()
    private val permits = Semaphore(maxConnections, true)
    private val retained = ArrayDeque<ScopedEvent>()
    private val subscriptions = linkedSetOf<TicketSubscription>()
    private val accepting = AtomicBoolean(true)

    init {
        require(queueSize > 0 && maxConnections > 0 && retainedEvents >= queueSize)
    }

    fun publish(scope: StreamScope, type: String, payload: Map<String, String>): TicketStreamEvent {
        require(type.isNotBlank())
        require(payload.keys.none(SENSITIVE_FIELDS::contains)) { "stream payload contains a forbidden field" }
        synchronized(lock) {
            val event = TicketStreamEvent(sequence.incrementAndGet(), type, payload.toMap(), clock.instant())
            retained += ScopedEvent(scope, event)
            while (retained.size > retainedEvents) retained.removeFirst()
            subscriptions.filter { it.scope == scope }.forEach { it.offer(event) }
            return event
        }
    }

    /** Registers before catch-up under one monitor, closing the snapshot-to-subscribe race. */
    fun subscribe(scope: StreamScope, snapshot: () -> Map<String, String>): TicketSubscription {
        if (!accepting.get() || !permits.tryAcquire()) throw TicketStreamCapacityExceeded()
        val highWater = synchronized(lock) { sequence.get() }
        val snapshotPayload = try {
            snapshot().toMap()
        } catch (failure: Exception) {
            permits.release()
            throw failure
        }
        synchronized(lock) {
            val subscription = TicketSubscription(scope, queueSize, permits) {
                synchronized(lock) { subscriptions.remove(it) }
            }
            subscriptions += subscription
            subscription.snapshot = TicketStreamSnapshot(snapshotPayload, highWater, clock.instant())
            retained.asSequence()
                .filter { it.scope == scope && it.event.sequence > highWater }
                .forEach { subscription.offer(it.event) }
            return subscription
        }
    }

    fun activeConnections(): Int = synchronized(lock) { subscriptions.size }

    fun stopNewConnections() {
        accepting.set(false)
    }

    override fun close() {
        accepting.set(false)
        synchronized(lock) { subscriptions.toList().forEach(TicketSubscription::close) }
    }

    private data class ScopedEvent(val scope: StreamScope, val event: TicketStreamEvent)

    companion object {
        private val SENSITIVE_FIELDS = setOf(
            "buyerSubjectId", "ipSubjectId", "authorizationOperationId", "refundOperationId", "paymentOutcome",
        )
    }
}

class TicketSubscription internal constructor(
    internal val scope: StreamScope,
    queueSize: Int,
    private val permits: Semaphore,
    private val detach: (TicketSubscription) -> Unit,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<TicketStreamEvent>(queueSize)
    private val closed = AtomicBoolean()
    lateinit var snapshot: TicketStreamSnapshot
        internal set

    internal fun offer(event: TicketStreamEvent) {
        if (!closed.get() && !queue.offer(event)) {
            close()
            throw TicketStreamSlowConsumer()
        }
    }

    fun poll(): TicketStreamEvent? = queue.poll()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detach(this)
        permits.release()
    }
}
