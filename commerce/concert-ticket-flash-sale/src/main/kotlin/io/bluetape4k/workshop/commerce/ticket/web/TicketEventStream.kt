package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import java.io.Serial
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed interface StreamScope : Serializable {
    data class PublicSale(val saleId: UUID) : StreamScope {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    data class OwnerAttempt(val attemptId: UUID, val buyerSubjectId: UUID) : StreamScope {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }
}

data class TicketStreamEvent(
    val sequence: Long,
    val type: String,
    val payload: Map<String, String>,
    val serverTime: Instant,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

data class TicketStreamSnapshot(
    val payload: Map<String, String>,
    val highWater: Long,
    val serverTime: Instant,
) : Serializable {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class TicketStreamCapacityExceeded : IllegalStateException("ticket_stream_capacity_exceeded")
class TicketStreamSlowConsumer : IllegalStateException("ticket_stream_slow_consumer")

/** Snapshot-first, bounded broadcaster. No JDBC resource is retained while a client is connected. */
class TicketEventStream(
    private val queueSize: Int,
    maxConnections: Int,
    private val retainedEvents: Int = 1_024,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val sequence = AtomicLong()
    private val permits = Semaphore(maxConnections, true)
    private val retained = ArrayDeque<ScopedEvent>()
    private val subscriptions = linkedSetOf<TicketSubscription>()
    private val accepting = AtomicBoolean(true)

    init {
        queueSize.requireGe(1, "queueSize")
        maxConnections.requireGe(1, "maxConnections")
        retainedEvents.requireGe(queueSize, "retainedEvents")
    }

    fun publish(scope: StreamScope, type: String, payload: Map<String, String>): TicketStreamEvent {
        type.requireNotBlank("type")
        payload.keys.none(SENSITIVE_FIELDS::contains).requireEquals(true, "payload.hasNoSensitiveFields")
        lock.withLock {
            val event = TicketStreamEvent(sequence.incrementAndGet(), type, payload.toMap(), clock.instant())
            retained += ScopedEvent(scope, event)
            while (retained.size > retainedEvents) retained.removeFirst()
            subscriptions.filter { it.scope == scope }.forEach { it.offer(event) }
            return event
        }
    }

    /** Registers before catch-up under one lock, closing the snapshot-to-subscribe race. */
    fun subscribe(scope: StreamScope, snapshot: () -> Map<String, String>): TicketSubscription {
        if (!accepting.get() || !permits.tryAcquire()) throw TicketStreamCapacityExceeded()
        val highWater = lock.withLock { sequence.get() }
        val snapshotPayload = try {
            snapshot().toMap()
        } catch (failure: Exception) {
            permits.release()
            throw failure
        }
        lock.withLock {
            val subscription = TicketSubscription(scope, queueSize, permits) {
                lock.withLock { subscriptions.remove(it) }
            }
            subscriptions += subscription
            subscription.snapshot = TicketStreamSnapshot(snapshotPayload, highWater, clock.instant())
            retained.asSequence()
                .filter { it.scope == scope && it.event.sequence > highWater }
                .forEach { subscription.offer(it.event) }
            return subscription
        }
    }

    fun activeConnections(): Int = lock.withLock { subscriptions.size }

    fun stopNewConnections() {
        accepting.set(false)
    }

    override fun close() {
        accepting.set(false)
        lock.withLock { subscriptions.toList().forEach(TicketSubscription::close) }
    }

    private data class ScopedEvent(val scope: StreamScope, val event: TicketStreamEvent) : Serializable {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

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
