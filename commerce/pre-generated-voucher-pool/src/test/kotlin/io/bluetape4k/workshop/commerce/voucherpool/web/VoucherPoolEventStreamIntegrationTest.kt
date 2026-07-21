@file:Suppress("MagicNumber", "VarCouldBeVal")

package io.bluetape4k.workshop.commerce.voucherpool.web

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
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMetrics
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolSseProperties
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.concurrent.virtualthread.VirtualThreads
import io.bluetape4k.jackson3.Jackson
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

@TestPropertySource(properties = ["workshop.voucher-pool.sse.heartbeat-interval=100ms"])
internal class VoucherPoolEventStreamIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Autowired private lateinit var campaigns: CampaignBatchCommandService
    @Autowired private lateinit var reservations: ReservationService
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var streams: VoucherPoolEventStream

    @BeforeEach
    fun reset() {
        VoucherPoolMigrationRunner(
            dataSource,
            VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            537_012L,
        ).migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("TRUNCATE TABLE voucher_pool_campaigns CASCADE") }
        }
    }

    @Test
    fun `customer polling snapshot and SSE are snapshot first and owner scoped`() {
        val fixture = activePool("customer")
        val owned = reservations.reserve(ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "sse-reserve"))
            .applied()
        reservations.reserve(ReserveVoucherCommand(TENANT, fixture.campaignId, OTHER_PRINCIPAL, "sse-other-reserve"))
            .applied()

        webTestClient.get().uri("/api/v1/snapshots")
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, PRINCIPAL)
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.reservations.length()").isEqualTo(1)
            .jsonPath("$.reservations[0].resourceId").isEqualTo(owned.reservationId.toString())
            .jsonPath("$.code").doesNotExist()

        val direct = streams.openCustomer(TENANT, PRINCIPAL, null)
        check(checkNotNull(direct.next(Duration.ZERO)).type == "snapshot")
        direct.close()

        val event = firstCustomerEvent()
        check(event.any { it == "event: snapshot" })
        check(event.any { it.startsWith("id: ") && ':' in it })
        check(event.any { it.contains(owned.reservationId.toString()) })
        check(event.none { it.contains("VOUCHER-SSE-") })
    }

    @Test
    fun `operator polling snapshot honors campaign batch relation and stream cursor scope`() {
        val fixture = activePool("operator")

        operatorGet("/operator/api/v1/snapshots?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}")
            .exchange().expectStatus().isOk
            .expectBody()
            .jsonPath("$.campaignId").isEqualTo(fixture.campaignId.toString())
            .jsonPath("$.batchId").isEqualTo(fixture.batchId.toString())
            .jsonPath("$.counts.AVAILABLE").isEqualTo(2)

        operatorGet(
            "/operator/api/v1/events?campaignId=${fixture.campaignId}&batchId=${UUID.randomUUID()}&cursor=0:0",
            accept = MediaType.TEXT_EVENT_STREAM,
        ).exchange().expectStatus().isNotFound

        operatorGet(
            "/operator/api/v1/events?campaignId=${fixture.campaignId}&batchId=${fixture.batchId}&cursor=bad",
            accept = MediaType.TEXT_EVENT_STREAM,
        ).exchange().expectStatus().isBadRequest
            .expectBody().jsonPath("$.code").isEqualTo("INVALID_EVENT_CURSOR")
    }

    @Test
    fun `customer cursor resumes for its owner and rejects a different owner`() {
        val fixture = activePool("cursor")
        reservations.reserve(ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "cursor-owner"))
            .applied()
        reservations.reserve(ReserveVoucherCommand(TENANT, fixture.campaignId, OTHER_PRINCIPAL, "cursor-other"))
            .applied()

        val first = streams.openCustomer(TENANT, PRINCIPAL, null)
        val cursor = checkNotNull(first.next(Duration.ZERO)).cursor
        first.close()

        val resumed = streams.openCustomer(TENANT, PRINCIPAL, cursor)
        check(checkNotNull(resumed.next(Duration.ZERO)).type == "snapshot")
        resumed.close()

        val failure = runCatching { streams.openCustomer(TENANT, OTHER_PRINCIPAL, cursor) }.exceptionOrNull()
        check(failure is VoucherPoolApiException && failure.stableCode == "INVALID_EVENT_CURSOR")
    }

    @Test
    fun `expired cursor emits authoritative snapshot then terminal reset`() {
        val fixture = activePool("reset")
        reservations.reserve(ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "reset-owner"))
            .applied()

        val subscription = streams.openCustomer(TENANT, PRINCIPAL, VoucherPoolEventCursor(0, 0))
        check(checkNotNull(subscription.next(Duration.ZERO)).type == "snapshot")
        val reset = checkNotNull(subscription.next(Duration.ZERO))
        check(reset.type == "reset" && reset.terminal)
        subscription.close()
    }

    @Test
    fun `slow consumer receives one terminal reset instead of an unbounded backlog`() {
        val source = OverflowEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                queueSize = 2,
                pollInterval = Duration.ofMillis(1),
                maxIdleInterval = Duration.ofMillis(10),
                heartbeatInterval = Duration.ofMillis(10),
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
                await atMost Duration.ofSeconds(2) untilAsserted { check(source.pollCount.get() > 0) }

                val next = checkNotNull(subscription.next(Duration.ofSeconds(1)))
                check(next.type == "reset" && next.terminal)
                subscription.close()
                check(subscription.cleanupInvocationCount() == 1L)
            }
        }
    }

    @Test
    fun `subscriber catch up closes the snapshot to shared poller registration gap`() {
        val source = CatchupEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = Duration.ofMillis(1),
                maxIdleInterval = Duration.ofMillis(5),
                heartbeatInterval = Duration.ofSeconds(1),
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
                await atMost Duration.ofSeconds(2) untilAsserted { check(source.pollCount.get() > 0) }

                val second = stream.openCustomer(TENANT, PRINCIPAL, null)
                check(checkNotNull(second.next(Duration.ZERO)).type == "snapshot")
                val caughtUp = checkNotNull(second.next(Duration.ofSeconds(1)))
                check(caughtUp.type == "audit" && caughtUp.cursor.id == 1L)

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
                pollInterval = Duration.ofMillis(1),
                maxIdleInterval = Duration.ofMillis(5),
                heartbeatInterval = Duration.ofSeconds(1),
            ),
            http = VoucherPoolHttpProperties(operatorSecret = OPERATOR_SECRET, operatorGuard = OPERATOR_GUARD),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                stream.openCustomer(TENANT, PRINCIPAL, null).use {
                    await atMost Duration.ofSeconds(2) untilAsserted { check(source.afterIds.size >= 2) }
                    val observed = source.afterIds.toList()
                    check(observed[1] > observed[0])
                }
            }
        }
    }

    @Test
    fun `shared poller emits a snapshot when authoritative state changes without an audit`() {
        val source = SnapshotChangeEventSource()
        val properties = VoucherPoolProperties(
            sse = VoucherPoolSseProperties(
                pollInterval = Duration.ofMillis(1),
                maxIdleInterval = Duration.ofMillis(5),
                heartbeatInterval = Duration.ofSeconds(1),
            ),
            http = VoucherPoolHttpProperties(operatorSecret = OPERATOR_SECRET, operatorGuard = OPERATOR_GUARD),
        )
        VirtualThreads.executorService().use { executor ->
            val metrics = VoucherPoolMetrics(SimpleMeterRegistry())
            VoucherPoolEventStream(source, Jackson.defaultJsonMapper, executor, properties, metrics).use { stream ->
                stream.openOperator(TENANT, null, null, null).use { subscription ->
                    check(checkNotNull(subscription.next(Duration.ZERO)).type == "snapshot")
                    val changed = checkNotNull(subscription.next(Duration.ofSeconds(1)))
                    check(changed.type == "snapshot")
                }
            }
        }
    }

    private fun firstCustomerEvent(): List<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/v1/events"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
            .header(TENANT_HEADER, TENANT)
            .header(PRINCIPAL_HEADER, PRINCIPAL)
            .GET()
            .build()
        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200)
        return response.body().bufferedReader().use { reader ->
            generateSequence(reader::readLine).takeWhile(String::isNotEmpty).toList()
        }
    }

    private fun operatorGet(
        path: String,
        accept: MediaType = MediaType.APPLICATION_JSON,
    ) = webTestClient.get().uri(path)
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
                TENANT,
                campaignId,
                now.minusSeconds(60),
                now.plusSeconds(3_600),
                VoucherPoolPolicy.of(3, 5.minutes, 30.minutes, 1),
                "sse-create-campaign-$name",
            ),
        ).applied()
        campaigns.activateCampaign(
            CampaignRevisionCommand(TENANT, campaignId, campaign.revision, "sse-activate-campaign-$name"),
        ).applied()
        val batch = campaigns.createImportBatch(
            CreateImportBatchCommand(
                TENANT,
                batchId,
                campaignId,
                BatchSourceKind.IMPORTED,
                digest(1),
                digest(2),
                2,
                now.minusSeconds(30),
                initialCodes = listOf("VOUCHER-SSE-$name-A", "VOUCHER-SSE-$name-B"),
                idempotencyKey = "sse-create-batch-$name",
            ),
        ).applied()
        campaigns.activateBatch(
            BatchRevisionCommand(TENANT, campaignId, batchId, batch.revision, "sse-activate-batch-$name"),
        ).applied()
        return EventFixture(campaignId, batchId)
    }

    private fun digest(seed: Int): DigestValue = DigestValue.of(ByteArray(32) { index -> (seed + index).toByte() })

    private data class EventFixture(val campaignId: UUID, val batchId: UUID) : java.io.Serializable {
        companion object { private const val serialVersionUID: Long = 1L }
    }

    private class OverflowEventSource : VoucherPoolEventSource {
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
        ): VoucherPoolStreamInitial = VoucherPoolStreamInitial(snapshot, VoucherPoolEventCursor(0, 0), false)

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            pollCount.incrementAndGet()
            val events = if (afterId == 0L) {
                (1L..2L).map { id ->
                    VoucherPoolAuditEnvelope(
                        VoucherPoolEventCursor(id, id),
                        VoucherPoolAuditHttpEvent("RESERVATION", UUID.randomUUID(), id, "UPDATED", 1, Instant.EPOCH),
                    )
                }
            } else {
                emptyList()
            }
            return VoucherPoolStreamBatch(snapshot, events)
        }
    }

    private class CatchupEventSource : VoucherPoolEventSource {
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
        private val event = VoucherPoolAuditEnvelope(
            VoucherPoolEventCursor(1, 1),
            VoucherPoolAuditHttpEvent("RESERVATION", UUID.randomUUID(), 1, "UPDATED", 1, Instant.EPOCH),
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial = VoucherPoolStreamInitial(snapshot, VoucherPoolEventCursor(0, 0), false)

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            pollCount.incrementAndGet()
            return VoucherPoolStreamBatch(snapshot, if (afterId < event.cursor.id) listOf(event) else emptyList())
        }
    }

    private class RawWatermarkEventSource : VoucherPoolEventSource {
        val afterIds = ConcurrentLinkedQueue<Long>()
        private val snapshot = VoucherPoolSnapshotResponse(
            "CUSTOMER", null, null, emptyList(), emptyList(), emptyList(), emptyList(),
            emptyMap(), false, Instant.EPOCH,
        )

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = snapshot

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial = VoucherPoolStreamInitial(snapshot, VoucherPoolEventCursor(0, 0), false)

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch {
            afterIds += afterId
            return VoucherPoolStreamBatch(snapshot, emptyList(), afterId + 10)
        }
    }

    private class SnapshotChangeEventSource : VoucherPoolEventSource {
        private val initial = snapshot(emptyMap())
        private val changed = snapshot(mapOf("AVAILABLE" to 1L))

        override fun snapshot(scope: VoucherPoolStreamScope): VoucherPoolSnapshotResponse = initial

        override fun initial(
            scope: VoucherPoolStreamScope,
            cursor: VoucherPoolEventCursor?,
        ): VoucherPoolStreamInitial = VoucherPoolStreamInitial(initial, VoucherPoolEventCursor(0, 0), false)

        override fun poll(scope: VoucherPoolStreamScope, afterId: Long): VoucherPoolStreamBatch =
            VoucherPoolStreamBatch(changed, emptyList(), afterId)

        private fun snapshot(counts: Map<String, Long>) = VoucherPoolSnapshotResponse(
            "OPERATOR", null, null, emptyList(), emptyList(), emptyList(), emptyList(),
            counts, false, Instant.EPOCH,
        )
    }

    private companion object {
        const val TENANT = "tenant-event-stream"
        const val PRINCIPAL = "principal-event-stream"
        const val OTHER_PRINCIPAL = "principal-event-stream-other"
        const val OPERATOR_SECRET = "test-operator-secret-0000000000000001"
        const val OPERATOR_GUARD = "test-voucher-pool-operator-guard"
    }
}
