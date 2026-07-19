package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.application.toSnapshot
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherSseProperties
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRecord
import io.bluetape4k.workshop.commerce.voucher.persistence.AuditRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignRepository
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherJdbcExecutor
import jakarta.annotation.PreDestroy
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper
import java.io.OutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class EventCursor(
    val revision: Long,
    val id: Long,
) {
    init {
        require(revision >= 0 && id >= 0) { "event cursor values must not be negative" }
    }

    override fun toString(): String = "$revision:$id"

    companion object {
        fun parse(raw: String?): EventCursor? {
            if (raw == null) return null
            if (raw.length !in 3..MAX_CURSOR_LENGTH || raw.any { it != ':' && !it.isDigit() }) {
                throw invalidCursor()
            }
            val values = raw.split(':')
            if (values.size != 2) throw invalidCursor()
            val revision = values[0].toLongOrNull() ?: throw invalidCursor()
            val id = values[1].toLongOrNull() ?: throw invalidCursor()
            return EventCursor(revision, id)
        }

        private const val MAX_CURSOR_LENGTH = 64
    }
}

internal data class VoucherStreamEvent(
    val cursor: EventCursor,
    val event: String,
    val data: String,
    val terminal: Boolean = false,
)

internal data class VoucherAuditHttpEvent(
    val aggregateType: String,
    val aggregateId: UUID,
    val revision: Long,
    val reasonCode: String,
    val policyVersion: Long,
    val occurredAt: Instant?,
)

internal data class VoucherStreamInitial(
    val snapshot: CampaignHttpResponse,
    val cursor: EventCursor,
    val resetRequired: Boolean,
)

internal data class VoucherStreamBatch(
    val snapshot: CampaignHttpResponse,
    val events: List<Pair<EventCursor, VoucherAuditHttpEvent>>,
)

/** Source boundary that keeps database work outside subscriber queues and network writes. */
internal interface VoucherEventSource {
    fun initial(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): VoucherStreamInitial

    fun poll(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
    ): VoucherStreamBatch
}

/** Reads snapshot and audit pages while holding only the reserved SSE-maintenance DB permit. */
@Component
internal class PostgresVoucherEventSource(
    private val jdbc: VoucherJdbcExecutor,
    private val campaigns: CampaignRepository,
    private val audits: AuditRepository,
    private val mapper: ObjectMapper,
    private val properties: VoucherProperties,
) : VoucherEventSource {
    override fun initial(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): VoucherStreamInitial =
        jdbc.sseMaintenanceTransaction {
            val campaign = campaigns.findPublic(tenantId, campaignId)?.toSnapshot()
                ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")
            val first = audits.firstCampaignAudit(tenantId, campaignId)
            val last = audits.lastCampaignAudit(tenantId, campaignId)
            val reset = validateCursor(tenantId, campaignId, campaign.revision, requestedCursor, first, last)
            VoucherStreamInitial(campaign.toHttp(), last?.toCursor() ?: EventCursor(campaign.revision, 0), reset)
        }

    override fun poll(
        tenantId: String,
        campaignId: UUID,
        afterId: Long,
    ): VoucherStreamBatch =
        jdbc.sseMaintenanceTransaction {
            val campaign = campaigns.findPublic(tenantId, campaignId)?.toSnapshot()
                ?: throw VoucherApiException("CAMPAIGN_NOT_FOUND", 404, "campaign was not found")
            val bounded = ArrayList<Pair<EventCursor, VoucherAuditHttpEvent>>()
            var encodedBytes = 0
            for (audit in audits.findCampaignAfter(tenantId, campaignId, afterId, properties.sse.maxRows)) {
                val event = audit.toHttpEvent()
                val encodedEventBytes = mapper.writeValueAsBytes(event).size + audit.toCursor().toString().length + SSE_FRAME_BYTES
                if (encodedBytes + encodedEventBytes > properties.sse.maxPayloadBytes) break
                bounded += audit.toCursor() to event
                encodedBytes += encodedEventBytes
            }
            VoucherStreamBatch(campaign.toHttp(), bounded)
        }

    private fun validateCursor(
        tenantId: String,
        campaignId: UUID,
        campaignRevision: Long,
        requested: EventCursor?,
        first: AuditRecord?,
        last: AuditRecord?,
    ): Boolean {
        if (requested == null) return false
        if (requested.revision > campaignRevision) throw invalidCursor()
        val referenced = requested.id.takeIf { it > 0 }?.let(audits::findCursor)
        if (referenced != null) {
            if (referenced.tenantId != tenantId || referenced.campaignId != campaignId) throw invalidCursor()
            if (referenced.revision != requested.revision) throw invalidCursor()
        }
        if (last == null) {
            if (requested.id > 0) throw invalidCursor()
            return false
        }
        if (requested.id > last.id) throw invalidCursor()
        val beforeRetentionWindow = first != null && requested.id < first.id
        if (requested.id > 0 && referenced == null && !beforeRetentionWindow) throw invalidCursor()
        return beforeRetentionWindow
    }

    private companion object {
        private const val SSE_FRAME_BYTES = 32
    }
}

/**
 * Shares one bounded virtual-thread poller per tenant/campaign and one bounded queue per subscriber.
 * Database permits are returned before any queue or network write can block.
 */
@Component
internal class VoucherEventStream(
    private val source: VoucherEventSource,
    private val mapper: ObjectMapper,
    private val executor: ExecutorService,
    properties: VoucherProperties,
) : AutoCloseable {
    private val config: VoucherSseProperties = properties.sse
    private val registryLock = ReentrantLock()
    private val pollers = HashMap<CampaignStreamKey, CampaignPoller>()
    private val closed = AtomicBoolean()

    fun open(
        tenantId: String,
        campaignId: UUID,
        requestedCursor: EventCursor?,
    ): StreamSubscription {
        check(!closed.get()) { "voucher event stream is shutting down" }
        val key = CampaignStreamKey(tenantId, campaignId)
        registryLock.withLock {
            if (key !in pollers && pollers.size >= config.maxCampaigns) throw SseCapacityRejected(campaignId)
        }
        val initial = source.initial(tenantId, campaignId, requestedCursor)
        val subscription = StreamSubscription(config.queueSize, initial.cursor, this)
        subscription.offer(event(initial.cursor, "snapshot", initial.snapshot))
        if (initial.resetRequired) {
            subscription.offer(event(initial.cursor, "reset", initial.snapshot))
        }

        registryLock.withLock {
            val poller =
                pollers[key] ?: run {
                    if (pollers.size >= config.maxCampaigns) throw SseCapacityRejected(campaignId)
                    CampaignPoller(key, initial.cursor).also {
                        pollers[key] = it
                        it.start()
                    }
                }
            poller.add(subscription)
        }
        log.info { "voucher_sse_subscribed campaignId=$campaignId activePollers=${activePollers()}" }
        return subscription
    }

    fun write(
        subscription: StreamSubscription,
        output: OutputStream,
    ) {
        try {
            while (!subscription.isClosed()) {
                val next = subscription.next(config.heartbeatInterval) ?: break
                writeWithDeadline(output, next)
                if (next.terminal) break
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            log.info { "voucher_sse_disconnected reason=interrupted" }
        } catch (_: TimeoutException) {
            log.warn { "voucher_sse_disconnected reason=write_timeout" }
        } catch (failure: ExecutionException) {
            log.info {
                "voucher_sse_disconnected reason=${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName}"
            }
        } catch (failure: IOException) {
            log.info { "voucher_sse_disconnected reason=${failure.javaClass.simpleName}" }
        } finally {
            subscription.close()
        }
    }

    internal fun activePollers(): Int = registryLock.withLock { pollers.size }

    internal fun pollDelay(
        tenantId: String,
        campaignId: UUID,
    ): Duration? = registryLock.withLock { pollers[CampaignStreamKey(tenantId, campaignId)]?.currentDelay() }

    @PreDestroy
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = registryLock.withLock { pollers.values.toList().also { pollers.clear() } }
        active.forEach(CampaignPoller::stop)
        log.info { "voucher_sse_closed pollers=${active.size}" }
    }

    private fun remove(subscription: StreamSubscription) {
        val poller = subscription.poller.getAndSet(null) ?: return
        poller.remove(subscription)
    }

    private fun removePoller(poller: CampaignPoller) {
        registryLock.withLock { pollers.remove(poller.key, poller) }
    }

    private fun event(
        cursor: EventCursor,
        type: String,
        payload: Any,
        terminal: Boolean = false,
    ): VoucherStreamEvent = VoucherStreamEvent(cursor, type, mapper.writeValueAsString(payload), terminal)

    private fun writeWithDeadline(
        output: OutputStream,
        event: VoucherStreamEvent,
    ) {
        val bytes = event.encode()
        val write = executor.submit { output.write(bytes); output.flush() }
        try {
            write.get(config.writeTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            write.cancel(true)
            throw timeout
        }
    }

    internal inner class StreamSubscription(
        queueSize: Int,
        initialCursor: EventCursor,
        private val owner: VoucherEventStream,
    ) : AutoCloseable {
        private val queue = ArrayBlockingQueue<VoucherStreamEvent>(queueSize)
        private val closed = AtomicBoolean()
        private val cursor = AtomicReference(initialCursor)
        private val cleanupCount = AtomicLong()
        internal val poller = AtomicReference<CampaignPoller?>()

        internal fun offer(event: VoucherStreamEvent): Boolean {
            if (closed.get()) return false
            if (event.event == "audit" && event.cursor.id <= cursor.get().id) return true
            cursor.set(event.cursor)
            return queue.offer(event)
        }

        internal fun overflow(snapshot: CampaignHttpResponse) {
            if (closed.get()) return
            queue.clear()
            queue.offer(owner.event(cursor.get(), "reset", snapshot, terminal = true))
        }

        internal fun terminate(
            type: String,
            payload: Any,
        ) {
            if (closed.get()) return
            queue.clear()
            queue.offer(owner.event(cursor.get(), type, payload, terminal = true))
        }

        internal fun next(timeout: Duration): VoucherStreamEvent? {
            if (closed.get() && queue.isEmpty()) return null
            return queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
                ?: owner.event(cursor.get(), "heartbeat", mapOf("observedAt" to Instant.now()))
        }

        internal fun attach(poller: CampaignPoller) {
            check(this.poller.compareAndSet(null, poller)) { "subscription already has a poller" }
        }

        internal fun isClosed(): Boolean = closed.get()

        internal fun queueDepth(): Int = queue.size

        internal fun cleanupInvocationCount(): Long = cleanupCount.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            cleanupCount.incrementAndGet()
            queue.clear()
            owner.remove(this)
        }
    }

    internal inner class CampaignPoller(
        val key: CampaignStreamKey,
        initialCursor: EventCursor,
    ) {
        private val subscribers = ConcurrentHashMap.newKeySet<StreamSubscription>()
        private val cursor = AtomicReference(initialCursor)
        private val running = AtomicBoolean(true)
        private val task = AtomicReference<Future<*>?>()
        private val delay = AtomicReference(config.pollInterval)

        fun start() {
            task.set(executor.submit(::run))
        }

        fun add(subscription: StreamSubscription) {
            subscription.attach(this)
            subscribers += subscription
        }

        fun remove(subscription: StreamSubscription) {
            subscribers -= subscription
            if (subscribers.isEmpty()) {
                stop()
                removePoller(this)
            }
        }

        fun currentDelay(): Duration = delay.get()

        fun stop() {
            if (!running.compareAndSet(true, false)) return
            task.getAndSet(null)?.cancel(true)
            subscribers.toList().forEach(StreamSubscription::close)
            subscribers.clear()
        }

        private fun run() {
            while (running.get()) {
                if (!sleep(delay.get())) break
                try {
                    val batch = source.poll(key.tenantId, key.campaignId, cursor.get().id)
                    if (batch.events.isEmpty()) {
                        delay.updateAndGet { it.multipliedBy(2).coerceAtMost(config.maxIdleInterval) }
                        continue
                    }
                    delay.set(config.pollInterval)
                    batch.events.forEach { (nextCursor, audit) ->
                        cursor.set(nextCursor)
                        val next = event(nextCursor, "audit", audit)
                        subscribers.toList().forEach { subscriber ->
                            if (!subscriber.offer(next)) subscriber.overflow(batch.snapshot)
                        }
                    }
                } catch (failure: VoucherApiException) {
                    subscribers.toList().forEach {
                        it.terminate("error", mapOf("code" to failure.stableCode, "reason" to failure.safeReason))
                    }
                    stop()
                } catch (failure: Exception) {
                    if (running.get()) {
                        log.warn(failure) { "voucher_sse_poll_failed campaignId=${key.campaignId}" }
                    }
                    delay.set(config.maxIdleInterval)
                }
            }
        }

        private fun sleep(duration: Duration): Boolean =
            try {
                TimeUnit.NANOSECONDS.sleep(duration.toNanos())
                true
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
    }

    companion object : KLogging()
}

internal data class CampaignStreamKey(
    val tenantId: String,
    val campaignId: UUID,
)

internal class SseCapacityRejected(
    val campaignId: UUID,
) : RuntimeException("SSE_CAPACITY_REJECTED")

/** Header-authenticated SSE endpoint used by the same-origin browser fetch stream. */
@RestController
internal class VoucherEventStreamController(
    private val streams: VoucherEventStream,
) {
    @GetMapping("/api/v1/campaigns/{campaignId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun campaignEvents(
        @PathVariable campaignId: UUID,
        @RequestHeader(TENANT_HEADER, required = false) tenantHeader: String?,
        @RequestHeader(PRINCIPAL_HEADER, required = false) principalHeader: String?,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): ResponseEntity<StreamingResponseBody> {
        val tenant = requireAsciiIdentifier(tenantHeader, TENANT_HEADER)
        requireAsciiIdentifier(principalHeader, PRINCIPAL_HEADER)
        val subscription = streams.open(tenant, campaignId, EventCursor.parse(lastEventId))
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(StreamingResponseBody { output -> streams.write(subscription, output) })
    }
}

private fun AuditRecord.toCursor(): EventCursor = EventCursor(revision, id)

private fun AuditRecord.toHttpEvent(): VoucherAuditHttpEvent =
    VoucherAuditHttpEvent(aggregateType, aggregateId, revision, reasonCode, policyVersion, createdAt)

private fun VoucherStreamEvent.encode(): ByteArray =
    buildString {
        append("id: ").append(cursor).append('\n')
        append("event: ").append(event).append('\n')
        append("data: ").append(data.replace("\n", "")).append("\n\n")
    }.toByteArray(UTF_8)

private fun invalidCursor(): VoucherApiException =
    VoucherApiException("INVALID_EVENT_CURSOR", 400, "event cursor is invalid")
