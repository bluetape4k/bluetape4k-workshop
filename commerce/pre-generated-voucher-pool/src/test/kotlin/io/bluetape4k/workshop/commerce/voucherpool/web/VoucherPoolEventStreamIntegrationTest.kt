@file:Suppress("MagicNumber", "VarCouldBeVal", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.BatchSourceKind
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignBatchCommandService
import io.bluetape4k.workshop.commerce.voucherpool.application.CampaignRevisionCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateCampaignCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.CreateImportBatchCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.ReservationService
import io.bluetape4k.workshop.commerce.voucherpool.application.ReserveVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestPropertySource(properties = ["workshop.voucher-pool.sse.heartbeat-interval=100ms"])
internal class VoucherPoolEventStreamIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    private companion object: KLogging() {
        const val TENANT = "tenant-event-stream"
        const val PRINCIPAL = "principal-event-stream"
        const val OTHER_PRINCIPAL = "principal-event-stream-other"
        const val OPERATOR_SECRET = "test-operator-secret-0000000000000001"
        const val OPERATOR_GUARD = "test-voucher-pool-operator-guard"
    }

    @Autowired
    private lateinit var campaigns: CampaignBatchCommandService

    @Autowired
    private lateinit var reservations: ReservationService

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var streams: VoucherPoolEventStream

    @BeforeEach
    fun reset() {
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = 537_012L,
        ).migrate()

        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE voucher_pool_campaigns CASCADE")
            }
        }
    }

    @Test
    fun `customer polling snapshot and SSE are snapshot first and owner scoped`() {
        val fixture = activePool("customer")
        val owned = reservations.reserve(
            ReserveVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                canonicalUser = PRINCIPAL,
                idempotencyKey = "sse-reserve"
            )
        ).applied()

        reservations.reserve(
            ReserveVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                canonicalUser = OTHER_PRINCIPAL,
                idempotencyKey = "sse-other-reserve"
            )
        ).applied()

        webTestClient.get()
            .uri("/api/v1/snapshots")
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, PRINCIPAL)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservations.length()").isEqualTo(1)
            .jsonPath("$.reservations[0].resourceId").isEqualTo(owned.reservationId.toString())
            .jsonPath("$.code").doesNotExist()

        val direct = streams.openCustomer(TENANT, PRINCIPAL, null)
        direct.next(Duration.ZERO)!!.type shouldBeEqualTo "snapshot"
        direct.close()

        val event = firstCustomerEvent()
        log.debug { "event: $event" }
        event.any { it == "event: snapshot" }.shouldBeTrue()
        event.any { it.startsWith("id: ") && ':' in it }.shouldBeTrue()
        event.any { it.contains(owned.reservationId.toString()) }.shouldBeTrue()
        event.joinToString("\n") shouldNotContain "VOUCHER-SSE-"
    }

    @Test
    fun `operator polling snapshot honors campaign batch relation and stream cursor scope`() {
        val fixture = activePool("operator")

        operatorGet("/operator/api/v1/snapshots?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(fixture.campaignId.toString())
            .jsonPath("$.batchId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.counts.AVAILABLE").isEqualTo(2)

        operatorGet(
            "/operator/api/v1/events?campaignId=${fixture.campaignId}&batchId=${UUID.randomUUID()}&cursor=0:0",
            accept = MediaType.TEXT_EVENT_STREAM,
        )
            .exchange()
            .expectStatus().isNotFound

        operatorGet(
            "/operator/api/v1/events?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}&cursor=bad",
            accept = MediaType.TEXT_EVENT_STREAM,
        )
            .exchange()
            .expectStatus().isBadRequest
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_EVENT_CURSOR")
    }

    @Test
    fun `customer cursor resumes for its owner and rejects a different owner`() {
        val fixture = activePool("cursor")
        reservations.reserve(
            ReserveVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                canonicalUser = PRINCIPAL,
                idempotencyKey = "cursor-owner"
            )
        ).applied()
        reservations.reserve(
            ReserveVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                canonicalUser = OTHER_PRINCIPAL,
                idempotencyKey = "cursor-other"
            )
        ).applied()

        val first = streams.openCustomer(TENANT, PRINCIPAL, null)
        val cursor = first.next(Duration.ZERO).shouldNotBeNull().cursor
        first.close()

        val resumed = streams.openCustomer(TENANT, PRINCIPAL, cursor)
        resumed.next(Duration.ZERO).shouldNotBeNull().type shouldBeEqualTo "snapshot"
        resumed.close()

        val failure = runCatching {
            streams.openCustomer(TENANT, OTHER_PRINCIPAL, cursor)
        }.exceptionOrNull()
        failure.shouldBeInstanceOf<VoucherPoolApiException>().stableCode shouldBeEqualTo "INVALID_EVENT_CURSOR"
    }

    @Test
    fun `expired cursor emits authoritative snapshot then terminal reset`() {
        val fixture = activePool("reset")
        reservations.reserve(
            ReserveVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                canonicalUser = PRINCIPAL,
                idempotencyKey = "reset-owner"
            )
        ).applied()

        val subscription = streams.openCustomer(
            tenantId = TENANT,
            principalId = PRINCIPAL,
            cursor = VoucherPoolEventCursor(0, 0)
        )
        subscription.next(Duration.ZERO)!!.type shouldBeEqualTo "snapshot"

        val reset = subscription.next(Duration.ZERO).shouldNotBeNull()
        reset.type shouldBeEqualTo "reset"
        reset.terminal.shouldBeTrue()
        subscription.close()
    }

    @Test
    fun `close racing a blocked initial snapshot prevents late poller registration`() {
        val source = ClosingRaceEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = 1.millis(),
                maxIdleInterval = 5.millis(),
                heartbeatInterval = 1.seconds(),
            ),
            http = VoucherPoolHttpProperties(operatorSecret = OPERATOR_SECRET, operatorGuard = OPERATOR_GUARD),
        )
        VirtualThreads.executorService().use { executor ->
            val stream = VoucherPoolEventStream(
                source,
                Jackson.defaultJsonMapper,
                executor,
                properties,
                VoucherPoolMetrics(SimpleMeterRegistry()),
            )
            val subscription = AtomicReference<VoucherPoolEventStream.StreamSubscription?>()
            val failure = AtomicReference<RuntimeException?>()
            val opening = Thread.ofVirtual().start {
                try {
                    subscription.set(stream.openCustomer(TENANT, PRINCIPAL, null))
                } catch (caught: RuntimeException) {
                    failure.set(caught)
                }
            }
            try {
                source.initialEntered.await(2.seconds).shouldBeTrue()
                stream.close()
                source.releaseInitial.countDown()
                opening.join(2.seconds())

                val shutdown = failure.get()
                val shutdownException = shutdown.shouldBeInstanceOf<VoucherPoolApiException>()
                shutdownException.stableCode shouldBeEqualTo "SERVICE_SHUTTING_DOWN"
                subscription.get().shouldBeNull()
                stream.activePollers() shouldBeEqualTo 0
            } finally {
                source.releaseInitial.countDown()
                subscription.get()?.close()
                stream.close()
            }
        }
    }

    @Test
    fun `slow consumer receives one terminal reset instead of an unbounded backlog`() {
        val source = OverflowEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                queueSize = 2,
                pollInterval = 1.millis(),
                maxIdleInterval = 10.millis(),
                heartbeatInterval = 10.millis(),
            ),
            http = VoucherPoolHttpProperties(
                operatorSecret = OPERATOR_SECRET,
                operatorGuard = OPERATOR_GUARD,
            ),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                val subscription = stream.openCustomer(TENANT, PRINCIPAL, null)

                await atMost 2.seconds() untilAsserted {
                    source.pollCount.get() shouldBeGreaterThan 0
                }

                val next = subscription.next(1.seconds()).shouldNotBeNull()
                next.type shouldBeEqualTo "reset"
                next.terminal.shouldBeTrue()

                subscription.close()
                subscription.cleanupInvocationCount() shouldBeEqualTo 1L
            }
        }
    }

    @Test
    fun `subscriber catch up closes the snapshot to shared poller registration gap`() {
        val source = CatchupEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = 1.millis(),
                maxIdleInterval = 5.millis(),
                heartbeatInterval = 1.seconds(),
            ),
            http = VoucherPoolHttpProperties(
                operatorSecret = OPERATOR_SECRET,
                operatorGuard = OPERATOR_GUARD,
            ),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                val first = stream.openCustomer(TENANT, PRINCIPAL, null)

                await atMost 2.seconds untilAsserted {
                    source.pollCount.get() shouldBeGreaterThan 0
                }

                val second = stream.openCustomer(TENANT, PRINCIPAL, null)
                second.next(Duration.ZERO).shouldNotBeNull().type shouldBeEqualTo "snapshot"

                val caughtUp = second.next(1.seconds()).shouldNotBeNull()
                caughtUp.type shouldBeEqualTo "audit"
                caughtUp.cursor.id shouldBeEqualTo 1L

                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `shared poller advances its raw scan watermark when a page has no visible events`() {
        val source = RawWatermarkEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = 1.millis(),
                maxIdleInterval = 5.millis(),
                heartbeatInterval = 1.seconds(),
            ),
            http = VoucherPoolHttpProperties(operatorSecret = OPERATOR_SECRET, operatorGuard = OPERATOR_GUARD),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                stream.openCustomer(TENANT, PRINCIPAL, null).use {

                    await atMost 2.seconds untilAsserted {
                        source.afterIds.size shouldBeGreaterOrEqualTo 2
                    }

                    val observed = source.afterIds.toList()
                    observed[1] shouldBeGreaterThan observed[0]
                }
            }
        }
    }

    @Test
    fun `shared poller emits a snapshot when authoritative state changes without an audit`() {
        val source = SnapshotChangeEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = 1.millis(),
                maxIdleInterval = 5.millis(),
                heartbeatInterval = 1.seconds(),
            ),
            http = VoucherPoolHttpProperties(operatorSecret = OPERATOR_SECRET, operatorGuard = OPERATOR_GUARD),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                stream.openOperator(TENANT, null, null, null).use { subscription ->
                    subscription.next(Duration.ZERO)!!.type shouldBeEqualTo "snapshot"
                    val changed = subscription.next(1.seconds()).shouldNotBeNull()
                    changed.type shouldBeEqualTo "snapshot"
                }
            }
        }
    }

    private fun firstCustomerEvent(): List<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/events"))
            .timeout(10.seconds())
            .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, PRINCIPAL)
            .GET()
            .build()

        val response = HttpClient.newHttpClient()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())

        response.statusCode() shouldBeEqualTo 200

        return response.body().bufferedReader().use { reader ->
            generateSequence(reader::readLine)
                .takeWhile(String::isNotEmpty)
                .toList()
        }
    }

    private fun operatorGet(
        path: String,
        accept: MediaType = MediaType.APPLICATION_JSON,
    ): RequestHeadersSpec<*> =
        webTestClient.get()
            .uri(path)
            .accept(accept)
            .header("X-Workshop-Origin", "http://127.0.0.1:$port")
            .header(TENANT_HEADER, TENANT)
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)

    private fun activePool(name: String): EventFixture {
        val now = Instant.now()
        val campaignId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val campaign = campaigns.createCampaign(
            CreateCampaignCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                startsAt = now.minusSeconds(60),
                endsAt = now.plusSeconds(3_600),
                policy = VoucherPoolPolicy.of(3, 5.minutes, 30.minutes, 1),
                idempotencyKey = "sse-create-campaign-$name",
            ),
        ).applied()

        campaigns.activateCampaign(
            CampaignRevisionCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                expectedRevision = campaign.revision,
                idempotencyKey = "sse-activate-campaign-$name"
            ),
        ).applied()

        val batch = campaigns.createImportBatch(
            CreateImportBatchCommand(
                tenantId = TENANT,
                batchId = batchId,
                campaignId = campaignId,
                sourceKind = BatchSourceKind.IMPORTED,
                manifestDigest = digest(1),
                requestFingerprint = digest(2),
                expectedCount = 2,
                activatesAt = now.minusSeconds(30),
                initialCodes = listOf("VOUCHER-SSE-$name-A", "VOUCHER-SSE-$name-B"),
                idempotencyKey = "sse-create-batch-$name",
            ),
        ).applied()

        campaigns.activateBatch(
            BatchRevisionCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                batchId = batchId,
                expectedRevision = batch.revision,
                idempotencyKey = "sse-activate-batch-$name"
            ),
        ).applied()

        return EventFixture(campaignId, batchId)
    }

    private fun digest(seed: Int): DigestValue =
        DigestValue(ByteArray(32) { index -> (seed + index).toByte() })

    private data class EventFixture(val campaignId: UUID, val batchId: UUID): java.io.Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class OverflowEventSource: VoucherPoolEventSource {
        val pollCount = AtomicInteger()

        private val snapshot = VoucherPoolSnapshotResponse(
            "CUSTOMER",
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            false,
            Instant.EPOCH,
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial =
            VoucherPoolStreamInitial(
                snapshot = snapshot,
                cursor = VoucherPoolEventCursor(0, 0),
                resetRequired = false
            )

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            pollCount.incrementAndGet()
            val events = if (afterId == 0L) {
                (1L..2L).map { id ->
                    VoucherPoolAuditEnvelope(
                        cursor = VoucherPoolEventCursor(id, id),
                        event = VoucherPoolAuditHttpEvent(
                            aggregateType = "RESERVATION",
                            aggregateId = UUID.randomUUID(),
                            revision = id,
                            reasonCode = "UPDATED",
                            policyVersion = 1,
                            occurredAt = Instant.EPOCH
                        ),
                    )
                }
            } else {
                emptyList()
            }
            return VoucherPoolStreamBatch(snapshot, events)
        }
    }

    private class CatchupEventSource: VoucherPoolEventSource {
        val pollCount = AtomicInteger()
        private val snapshot = VoucherPoolSnapshotResponse(
            scope = "CUSTOMER",
            campaignId = null,
            batchId = null,
            reservations = emptyList(),
            allocations = emptyList(),
            campaigns = emptyList(),
            batches = emptyList(),
            counts = emptyMap(),
            truncated = false,
            observedAt = Instant.EPOCH,
        )
        private val event = VoucherPoolAuditEnvelope(
            cursor = VoucherPoolEventCursor(1, 1),
            event = VoucherPoolAuditHttpEvent(
                aggregateType = "RESERVATION",
                aggregateId = UUID.randomUUID(),
                revision = 1,
                reasonCode = "UPDATED",
                policyVersion = 1,
                occurredAt = Instant.EPOCH
            ),
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial =
            VoucherPoolStreamInitial(
                snapshot = snapshot,
                cursor = VoucherPoolEventCursor(0, 0),
                resetRequired = false
            )

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            pollCount.incrementAndGet()
            return VoucherPoolStreamBatch(
                snapshot = snapshot,
                events = if (afterId < event.cursor.id) listOf(event) else emptyList()
            )
        }
    }

    private class RawWatermarkEventSource: VoucherPoolEventSource {
        val afterIds = ConcurrentLinkedQueue<Long>()

        private val snapshot = VoucherPoolSnapshotResponse(
            "CUSTOMER",
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            false,
            Instant.EPOCH,
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial =
            VoucherPoolStreamInitial(
                snapshot = snapshot,
                cursor = VoucherPoolEventCursor(0, 0),
                resetRequired = false
            )

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            afterIds += afterId
            return VoucherPoolStreamBatch(
                snapshot = snapshot,
                events = emptyList(),
                scannedThroughId = afterId + 10
            )
        }
    }

    private class SnapshotChangeEventSource: VoucherPoolEventSource {
        private val initial = snapshot(emptyMap())
        private val changed = snapshot(mapOf("AVAILABLE" to 1L))

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = initial

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial =
            VoucherPoolStreamInitial(
                snapshot = initial,
                cursor = VoucherPoolEventCursor(0, 0),
                resetRequired = false
            )

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch =
            VoucherPoolStreamBatch(
                snapshot = changed,
                events = emptyList(),
                scannedThroughId = afterId
            )

        private fun snapshot(counts: Map<String, Long>): VoucherPoolSnapshotResponse =
            VoucherPoolSnapshotResponse(
                scope = "OPERATOR",
                campaignId = null,
                batchId = null,
                reservations = emptyList(),
                allocations = emptyList(),
                campaigns = emptyList(),
                batches = emptyList(),
                counts = counts,
                truncated = false,
                observedAt = Instant.EPOCH,
            )
    }

    private class ClosingRaceEventSource: VoucherPoolEventSource {
        val initialEntered = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)

        private val snapshot = VoucherPoolSnapshotResponse(
            scope = "CUSTOMER",
            campaignId = null,
            batchId = null,
            reservations = emptyList(),
            allocations = emptyList(),
            campaigns = emptyList(),
            batches = emptyList(),
            counts = emptyMap(),
            truncated = false,
            observedAt = Instant.EPOCH,
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial {
            initialEntered.countDown()
            releaseInitial.await(2.seconds).shouldBeTrue()
            return VoucherPoolStreamInitial(
                snapshot = snapshot,
                cursor = VoucherPoolEventCursor(0, 0),
                resetRequired = false
            )
        }

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch =
            VoucherPoolStreamBatch(
                snapshot = snapshot,
                events = emptyList(),
                scannedThroughId = afterId
            )
    }
}
