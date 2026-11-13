@file:Suppress("MagicNumber", "MaxLineLength", "VarCouldBeVal", "SqlResolve")

package io.bluetape4k.workshop.commerce.voucherpool.query

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.AllocationService
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
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import java.sql.Timestamp
import java.time.Instant
import java.util.*
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

internal class VoucherPoolQueryServiceIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    private companion object: KLogging() {
        const val TENANT = "tenant-query-integration"
        const val PRINCIPAL = "principal-query-integration"
    }

    @Autowired
    private lateinit var queries: VoucherPoolQueryService

    @Autowired
    private lateinit var campaigns: CampaignBatchCommandService

    @Autowired
    private lateinit var reservations: ReservationService

    @Autowired
    private lateinit var allocations: AllocationService

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun reset() {
        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = 537_010L,
        ).migrate()

        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.execute("TRUNCATE TABLE voucher_pool_campaigns CASCADE")
            }
        }
    }

    @Test
    fun `owner reads stay scoped while operator projections remain tenant bounded`() {
        val fixture = activePool()
        val stuck = reservations.reserve(
            ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "query-reserve-stuck"),
        ).applied()
        log.debug { "stuck: $stuck" }

        val allocatedReservation = reservations.reserve(
            ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "query-reserve-allocated"),
        ).applied()
        log.debug { "allocatedReservation: $allocatedReservation" }

        val allocation = allocations.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT,
                campaignId = fixture.campaignId,
                reservationId = allocatedReservation.reservationId,
                canonicalUser = PRINCIPAL,
                expectedRevision = allocatedReservation.revision,
                idempotencyKey = "query-allocate",
            ),
        ).applied()
        log.debug { "allocation: $allocation" }

        expireReservation(stuck.reservationId)
        terminalizeEntry(allocation.entryId, fixture.batchId)

        queries.reservation(TENANT, PRINCIPAL, stuck.reservationId)!!.reservationId shouldBeEqualTo stuck.reservationId

        queries.reservation(TENANT, "wrong-principal", stuck.reservationId).shouldBeNull()
        queries.reservation("wrong-tenant", PRINCIPAL, stuck.reservationId).shouldBeNull()

        queries.allocation(TENANT, PRINCIPAL, allocation.allocationId)!!.state shouldBeEqualTo EntryState.RELEASED

        queries.allocation(TENANT, "wrong-principal", allocation.allocationId).shouldBeNull()
        queries.allocation("wrong-tenant", PRINCIPAL, allocation.allocationId).shouldBeNull()

        queries.batch(TENANT, fixture.batchId)!!.batchId shouldBeEqualTo fixture.batchId
        queries.batch("wrong-tenant", fixture.batchId).shouldBeNull()
        queries.campaign(TENANT, fixture.campaignId)!!.campaignId shouldBeEqualTo fixture.campaignId
        queries.campaign("wrong-tenant", fixture.campaignId).shouldBeNull()

        val depth = queries.poolDepth(TENANT, fixture.campaignId, fixture.batchId).shouldNotBeNull()

        depth.counts shouldBeEqualTo EntryState.entries.associateWith { state ->
            when (state) {
                EntryState.AVAILABLE,
                EntryState.RESERVED,
                EntryState.RELEASED,
                     -> 1L
                else -> 0L
            }
        }
        depth.eligibleAvailable shouldBeEqualTo 1L
        depth.expiredButNotTerminalized shouldBeEqualTo 1L

        queries.poolDepth("wrong-tenant", fixture.campaignId, fixture.batchId).shouldBeNull()

        val otherFixture = activePool("other")
        queries.poolDepth(TENANT, fixture.campaignId, otherFixture.batchId).shouldBeNull()

        val stuckPage = queries.stuckReservations(TENANT, fixture.campaignId, null, 1).shouldNotBeNull()
        log.debug { "stuckPage: $stuckPage" }
        stuckPage.items.map(StuckReservationReadModel::reservationId) shouldBeEqualTo listOf(stuck.reservationId)
        stuckPage.nextCursor.shouldBeNull()
        queries.stuckReservations("wrong-tenant", fixture.campaignId, null, 1).shouldBeNull()
    }

    @Test
    fun `stuck reservation cursor is stable when expiry timestamps tie`() {
        val fixture = activePool("cursor")
        val first = reservations.reserve(
            ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "cursor-reserve-first"),
        ).applied()
        log.debug { "first: $first" }

        val second = reservations.reserve(
            ReserveVoucherCommand(TENANT, fixture.campaignId, PRINCIPAL, "cursor-reserve-second"),
        ).applied()
        log.debug { "second: $second" }

        val tiedExpiry = Instant.now().minusSeconds(60)
        expireReservations(listOf(first.reservationId, second.reservationId), tiedExpiry)

        val firstPage = queries.stuckReservations(TENANT, fixture.campaignId, null, 1).shouldNotBeNull()
        val cursor = firstPage.nextCursor.shouldNotBeNull()
        val secondPage = queries.stuckReservations(TENANT, fixture.campaignId, cursor, 1).shouldNotBeNull()

        firstPage.items shouldHaveSize 1
        secondPage.items shouldHaveSize 1
        (firstPage.items + secondPage.items)
            .map(StuckReservationReadModel::reservationId)
            .toSet() shouldBeEqualTo setOf(first.reservationId, second.reservationId)

        secondPage.nextCursor.shouldBeNull()
    }

    private fun activePool(name: String = "primary"): QueryFixture {
        val now = Instant.now()
        val campaignId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val created = campaigns.createCampaign(
            CreateCampaignCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                startsAt = now.minusSeconds(60),
                endsAt = now.plusSeconds(3_600),
                policy = VoucherPoolPolicy.of(3, 5.minutes, 30.minutes, 1),
                idempotencyKey = "query-create-campaign-$name",
            ),
        ).applied()
        log.debug { "created: $created" }

        campaigns.activateCampaign(
            CampaignRevisionCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                expectedRevision = created.revision,
                idempotencyKey = "query-activate-campaign-$name"
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
                expectedCount = 3,
                activatesAt = now.minusSeconds(30),
                initialCodes = listOf("QUERY-$name-A", "QUERY-$name-B", "QUERY-$name-C"),
                idempotencyKey = "query-create-batch-$name",
            ),
        ).applied()
        log.debug { "batch: $batch" }
        
        campaigns.activateBatch(
            BatchRevisionCommand(
                tenantId = TENANT,
                campaignId = campaignId,
                batchId = batchId,
                expectedRevision = batch.revision,
                idempotencyKey = "query-activate-batch-$name"
            ),
        ).applied()
        return QueryFixture(campaignId, batchId)
    }

    private fun expireReservation(reservationId: UUID) {
        expireReservations(
            reservationIds = listOf(reservationId),
            expiresAt = Instant.now().minusSeconds(60)
        )
    }

    private fun expireReservations(reservationIds: List<UUID>, expiresAt: Instant) {
        dataSource.connection.use { conn ->
            val updatePoolReservation = """
            UPDATE voucher_pool_reservations 
               SET reservation_expires_at=? 
             WHERE tenant_id=? 
               AND reservation_id=?
            """.trimIndent()

            conn.prepareStatement(updatePoolReservation).use { stmt ->
                reservationIds.forEach { reservationId ->
                    stmt.setTimestamp(1, Timestamp.from(expiresAt))
                    stmt.setString(2, TENANT)
                    stmt.setObject(3, reservationId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            val updatePoolEntries = """
                UPDATE voucher_pool_entries e SET reserved_at=?,reservation_expires_at=?
                  FROM voucher_pool_reservations r
                 WHERE r.tenant_id=e.tenant_id 
                   AND r.entry_id=e.entry_id
                   AND r.tenant_id=? 
                   AND r.reservation_id=?
                """.trimIndent()
            conn.prepareStatement(updatePoolEntries).use { stmt ->
                reservationIds.forEach { reservationId ->
                    stmt.setTimestamp(1, Timestamp.from(expiresAt.minusSeconds(60)))
                    stmt.setTimestamp(2, Timestamp.from(expiresAt))
                    stmt.setString(3, TENANT)
                    stmt.setObject(4, reservationId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    private fun terminalizeEntry(entryId: UUID, batchId: UUID) {
        dataSource.connection.use { conn ->
            val updatePoolEntries = """
                UPDATE voucher_pool_entries 
                   SET state='RELEASED',
                       terminal_reason='QUERY_TEST',
                       revision=revision+1
                 WHERE tenant_id=? 
                   AND entry_id=?
                """.trimIndent()
            conn.prepareStatement(updatePoolEntries).use { stmt ->
                stmt.setString(1, TENANT)
                stmt.setObject(2, entryId)
                stmt.executeUpdate()
            }

            val updateAllocated = """
                UPDATE voucher_pool_pool_depth 
                   SET entry_count=entry_count-1,
                       revision=revision+1
                 WHERE tenant_id=? 
                   AND batch_id=? 
                   AND state='ALLOCATED'
                """.trimIndent()
            conn.prepareStatement(updateAllocated).use { stmt ->
                stmt.setString(1, TENANT)
                stmt.setObject(2, batchId)
                stmt.executeUpdate()
            }

            val updateReleased = """
                UPDATE voucher_pool_pool_depth 
                   SET entry_count=entry_count+1,
                       revision=revision+1
                 WHERE tenant_id=? 
                   AND batch_id=? 
                   AND state='RELEASED'
                """.trimIndent()
            conn.prepareStatement(updateReleased).use { stmt ->
                stmt.setString(1, TENANT)
                stmt.setObject(2, batchId)
                stmt.executeUpdate()
            }
        }
    }

    private fun digest(seed: Int): DigestValue =
        DigestValue(ByteArray(32) { index -> (seed + index).toByte() })

    private data class QueryFixture(
        val campaignId: UUID,
        val batchId: UUID,
    )
}
