package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import jakarta.annotation.PreDestroy
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val MAX_CURSOR_LENGTH = 96
private const val CURSOR_COMPONENT_COUNT = 3
private const val MAX_SSE_SUBSCRIPTIONS = 256
private const val MAX_SSE_QUEUE_SIZE = 256
private const val DEFAULT_POLL_INTERVAL_MILLIS = 250L
private const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 10L
private const val DEFAULT_WRITE_TIMEOUT_SECONDS = 2L

internal data class EventSourcedStreamCursor(
    val streamPosition: Long,
    val globalPosition: Long,
    val projectionPosition: Long,
) {
    init {
        streamPosition.requireZeroOrPositiveNumber("cursor.streamPosition")
        globalPosition.requireZeroOrPositiveNumber("cursor.globalPosition")
        projectionPosition.requireZeroOrPositiveNumber("cursor.projectionPosition")
        streamPosition.requireLe(globalPosition, "cursor.streamPosition")
        projectionPosition.requireLe(globalPosition, "cursor.projectionPosition")
    }

    override fun toString(): String = "$streamPosition:$globalPosition:$projectionPosition"

    companion object {
        fun parse(raw: String?): EventSourcedStreamCursor? {
            if (raw == null) return null
            raw.length.requireInRange(CURSOR_COMPONENT_COUNT, MAX_CURSOR_LENGTH, "Last-Event-ID.length")
            val components = raw.split(':')
            components.size.requireEquals(CURSOR_COMPONENT_COUNT, "Last-Event-ID.components")
            return EventSourcedStreamCursor(
                streamPosition = components[0].toLongOrNull().requireNotNull("cursor.streamPosition"),
                globalPosition = components[1].toLongOrNull().requireNotNull("cursor.globalPosition"),
                projectionPosition = components[2].toLongOrNull().requireNotNull("cursor.projectionPosition"),
            )
        }
    }
}

/** Public event shape intentionally excludes event payload, subject, token, digest, and fencing data. */
internal data class EventSourcedPublicEventDescriptor(
    val campaignId: UUID,
    val state: String,
    val streamPosition: Long,
    val globalPosition: Long,
    val projectionPosition: Long,
    val lag: Long,
    val observedAt: Instant,
) {
    init {
        state.requireNotBlank("state")
        streamPosition.requireZeroOrPositiveNumber("streamPosition")
        globalPosition.requireZeroOrPositiveNumber("globalPosition")
        projectionPosition.requireZeroOrPositiveNumber("projectionPosition")
        streamPosition.requireLe(globalPosition, "streamPosition")
        projectionPosition.requireLe(globalPosition, "projectionPosition")
        lag.requireEquals(globalPosition - projectionPosition, "lag")
    }

    fun cursor(): EventSourcedStreamCursor =
        EventSourcedStreamCursor(streamPosition, globalPosition, projectionPosition)
}

internal data class EventSourcedStreamEvent(
    val cursor: EventSourcedStreamCursor,
    val event: String,
    val data: String,
    val terminal: Boolean = false,
) {
    init {
        event.requireNotBlank("event")
        data.requireNotBlank("data")
    }
}

@ConfigurationProperties("voucher.sse")
internal data class EventSourcedSseProperties(
    val maxSubscriptions: Int = 32,
    val queueSize: Int = 32,
    val pollInterval: Duration = Duration.ofMillis(DEFAULT_POLL_INTERVAL_MILLIS),
    val heartbeatInterval: Duration = Duration.ofSeconds(DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
    val writeTimeout: Duration = Duration.ofSeconds(DEFAULT_WRITE_TIMEOUT_SECONDS),
) {
    init {
        maxSubscriptions.requireInRange(1, MAX_SSE_SUBSCRIPTIONS, "voucher.sse.max-subscriptions")
        queueSize.requireInRange(1, MAX_SSE_QUEUE_SIZE, "voucher.sse.queue-size")
        pollInterval.requireGt(Duration.ZERO, "voucher.sse.poll-interval")
        heartbeatInterval.requireGt(Duration.ZERO, "voucher.sse.heartbeat-interval")
        writeTimeout.requireGt(Duration.ZERO, "voucher.sse.write-timeout")
    }
}

internal fun interface EventSourcedEventSource {
    fun read(
        tenant: String,
        campaignId: UUID,
    ): EventSourcedPublicEventDescriptor
}

@Component
internal class ProjectionSnapshotEventSource(
    private val snapshots: CampaignProjectionSnapshotReader,
    private val clock: Clock,
) : EventSourcedEventSource {
    override fun read(
        tenant: String,
        campaignId: UUID,
    ): EventSourcedPublicEventDescriptor {
        val snapshot = snapshots.read(TenantId(tenant), campaignId)
        val campaign =
            snapshot.campaign
                ?: throw EventSourcedStreamRejected(
                    stableCode = "CAMPAIGN_NOT_FOUND",
                    httpStatus = HttpStatus.NOT_FOUND.value(),
                    safeReason = "campaign was not found",
                )
        return EventSourcedPublicEventDescriptor(
            campaignId = campaign.campaignId,
            state = campaign.state.name,
            streamPosition = campaign.streamVersion,
            globalPosition = snapshot.positions.streamPosition,
            projectionPosition = snapshot.positions.projectionPosition,
            lag = snapshot.positions.lag,
            observedAt = clock.instant(),
        )
    }
}

/**
 * Each admitted connection owns one bounded virtual-thread poller and one bounded queue.
 * Snapshot-reader transactions finish before queue or network writes begin.
 */
@Component
internal class EventSourcedEventStream(
    private val source: EventSourcedEventSource,
    private val mapper: ObjectMapper,
    private val properties: EventSourcedSseProperties,
) : AutoCloseable {
    private val executor: ExecutorService = VirtualThreads.executorService()
    private val admission = Semaphore(properties.maxSubscriptions)
    private val closed = AtomicBoolean()

    fun open(
        tenant: String,
        campaignId: UUID,
        requestedCursor: EventSourcedStreamCursor?,
    ): Subscription {
        requireAvailable()
        acquireAdmission()
        return runCatching {
            source.read(tenant, campaignId)
                .also { initial -> validateReconnect(requestedCursor, initial.cursor()) }
                .let { initial -> Subscription(tenant, campaignId, initial).also(Subscription::start) }
        }.onFailure {
            admission.release()
        }.getOrThrow()
    }

    fun connect(subscription: Subscription): SseEmitter {
        val emitter = SseEmitter(0L)
        emitter.onCompletion(subscription::close)
        emitter.onTimeout(subscription::close)
        emitter.onError { subscription.close() }
        executor.submit {
            try {
                var streaming = true
                while (streaming && (subscription.isActive() || subscription.queueDepth() > 0)) {
                    val event = subscription.next(properties.heartbeatInterval)
                    streaming = event != null
                    if (event != null) {
                        sendWithDeadline(emitter, event)
                        streaming = !event.terminal
                    }
                }
                emitter.complete()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                log.info { "event_sourced_sse_disconnected reason=interrupted" }
            } catch (_: TimeoutException) {
                log.warn { "event_sourced_sse_disconnected reason=write_timeout" }
            } catch (failure: ExecutionException) {
                log.info { "event_sourced_sse_disconnected reason=${failure.cause?.javaClass?.simpleName}" }
            } finally {
                subscription.close()
            }
        }
        return emitter
    }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.close()
    }

    private fun validateReconnect(
        requested: EventSourcedStreamCursor?,
        current: EventSourcedStreamCursor,
    ) {
        if (requested == null) return
        requested.streamPosition.requireLe(current.streamPosition, "cursor.streamPosition")
        requested.globalPosition.requireLe(current.globalPosition, "cursor.globalPosition")
        requested.projectionPosition.requireLe(current.projectionPosition, "cursor.projectionPosition")
    }

    private fun requireAvailable() {
        if (closed.get()) {
            throw EventSourcedStreamRejected(
                "SSE_SHUTTING_DOWN",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "event stream is shutting down",
            )
        }
    }

    private fun acquireAdmission() {
        if (!admission.tryAcquire()) {
            throw EventSourcedStreamRejected(
                "SSE_CAPACITY_REJECTED",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "event stream capacity is unavailable",
            )
        }
    }

    private fun event(
        descriptor: EventSourcedPublicEventDescriptor,
        type: String,
        terminal: Boolean = false,
    ): EventSourcedStreamEvent =
        EventSourcedStreamEvent(
            cursor = descriptor.cursor(),
            event = type,
            data = mapper.writeValueAsString(descriptor),
            terminal = terminal,
        )

    private fun heartbeat(cursor: EventSourcedStreamCursor): EventSourcedStreamEvent =
        EventSourcedStreamEvent(
            cursor = cursor,
            event = "heartbeat",
            data = mapper.writeValueAsString(mapOf("observedAt" to Instant.now())),
        )

    private fun sendWithDeadline(
        emitter: SseEmitter,
        event: EventSourcedStreamEvent,
    ) {
        val send = executor.submit {
            emitter.send(
                SseEmitter.event()
                    .id(event.cursor.toString())
                    .name(event.event)
                    .data(event.data, MediaType.APPLICATION_JSON),
            )
        }
        try {
            send.get(properties.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            send.cancel(true)
            throw timeout
        }
    }

    internal inner class Subscription(
        private val tenant: String,
        private val campaignId: UUID,
        initial: EventSourcedPublicEventDescriptor,
    ) : AutoCloseable {
        private val queue = ArrayBlockingQueue<EventSourcedStreamEvent>(properties.queueSize)
        private val cursor = AtomicReference(initial.cursor())
        private val active = AtomicBoolean(true)
        private val poller = AtomicReference<Future<*>?>()

        init {
            queue.offer(event(initial, "snapshot"))
        }

        fun start() {
            poller.set(executor.submit(::poll))
        }

        fun next(timeout: Duration): EventSourcedStreamEvent? {
            if (!active.get() && queue.isEmpty()) return null
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) ?: heartbeat(cursor.get())
        }

        fun isActive(): Boolean = active.get()

        fun queueDepth(): Int = queue.size

        override fun close() {
            finish(clearQueue = true)
        }

        private fun poll() {
            while (active.get()) {
                if (!pause()) return
                try {
                    val descriptor = source.read(tenant, campaignId)
                    val nextCursor = descriptor.cursor()
                    if (nextCursor == cursor.get()) continue
                    cursor.set(nextCursor)
                    if (!queue.offer(event(descriptor, "projection"))) overflow(descriptor)
                } catch (failure: EventSourcedStreamRejected) {
                    terminate(failure.stableCode, failure.safeReason)
                } catch (failure: IllegalArgumentException) {
                    if (active.get()) {
                        log.warn {
                            "event_sourced_sse_poll_failed " +
                                "campaignId=$campaignId failure=${failure.javaClass.simpleName}"
                        }
                    }
                }
            }
        }

        private fun pause(): Boolean =
            try {
                TimeUnit.NANOSECONDS.sleep(properties.pollInterval.toNanos())
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        private fun overflow(descriptor: EventSourcedPublicEventDescriptor) {
            queue.clear()
            queue.offer(event(descriptor, "reset", terminal = true))
            finish(clearQueue = false)
        }

        private fun terminate(
            code: String,
            reason: String,
        ) {
            queue.clear()
            queue.offer(
                EventSourcedStreamEvent(
                    cursor = cursor.get(),
                    event = "error",
                    data = mapper.writeValueAsString(mapOf("code" to code, "reason" to reason)),
                    terminal = true,
                ),
            )
            finish(clearQueue = false)
        }

        private fun finish(clearQueue: Boolean) {
            if (!active.compareAndSet(true, false)) {
                if (clearQueue) queue.clear()
                return
            }
            poller.getAndSet(null)?.cancel(true)
            if (clearQueue) queue.clear()
            admission.release()
        }
    }

    private companion object : KLogging()
}

internal class EventSourcedStreamRejected(
    val stableCode: String,
    val httpStatus: Int,
    val safeReason: String,
) : RuntimeException(stableCode)

@RestController
internal class EventSourcedEventStreamController(
    private val streams: EventSourcedEventStream,
) {
    @GetMapping("/api/v1/campaigns/{campaignId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun campaignEvents(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): ResponseEntity<SseEmitter> {
        val tenant = tenantHeader.requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER)
        principalHeader.requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER)
        val cursor =
            parseEventSourcedCursor(lastEventId)
        val subscription = openEventSourcedSubscription(streams, tenant, campaignId, cursor)
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(streams.connect(subscription))
    }
}

private fun parseEventSourcedCursor(lastEventId: String?): EventSourcedStreamCursor? =
    runCatching { EventSourcedStreamCursor.parse(lastEventId) }
        .getOrElse {
            throw EventSourcedStreamRejected(
                "INVALID_EVENT_CURSOR",
                HttpStatus.BAD_REQUEST.value(),
                "event cursor is invalid",
            )
        }

private fun openEventSourcedSubscription(
    streams: EventSourcedEventStream,
    tenant: String,
    campaignId: UUID,
    cursor: EventSourcedStreamCursor?,
): EventSourcedEventStream.Subscription =
    runCatching { streams.open(tenant, campaignId, cursor) }
        .getOrElse { failure ->
            if (failure is IllegalArgumentException) {
                throw EventSourcedStreamRejected(
                    "INVALID_EVENT_CURSOR",
                    HttpStatus.BAD_REQUEST.value(),
                    "event cursor is invalid",
                )
            }
            throw failure
        }
