@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigration
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolMigrationRunner
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.JdbcVoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcVoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.ReservationGuards
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.lockWaiters
import io.bluetape4k.workshop.commerce.voucherpool.persistence.sharedLockHolders
import io.bluetape4k.workshop.commerce.voucherpool.security.AesGcmVoucherEnvelopeCrypto
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoStorage
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKek
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherKekRing
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.core.io.ClassPathResource
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolLifecycleIntegrationTest {
    private val harness = LifecycleHarness("lifecycle")

    @BeforeAll fun migrate() = harness.migrate()
    @AfterAll fun cleanup() {
        harness.cleanup()
    }
    @BeforeEach fun reset() = harness.reset()

    @Test
    fun `allocation retains ciphertext and verification digest until first reveal commits`() {
        val fixture = harness.activePool("reveal", listOf("SECRET-REVEAL"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "alice", "reserve-reveal")).applied()
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "alice", "allocate-reveal")).applied()

        harness.entryState(allocation.entryId) shouldBeEqualTo EntryState.ALLOCATED
        harness.hasVerificationDigest(allocation.entryId) shouldBeEqualTo true
        harness.hasCiphertext(allocation.entryId) shouldBeEqualTo true

        val revealed = harness.allocations.reveal(harness.reveal(allocation, "alice", "reveal-once")).applied()
        revealed.code shouldBeEqualTo CanonicalVoucherCode.of("SECRET-REVEAL")
        harness.hasCiphertext(allocation.entryId) shouldBeEqualTo false

        val replay = harness.allocations.reveal(harness.reveal(allocation, "alice", "reveal-once"))
        (replay is MutationResult.Replay) shouldBeEqualTo true
        (replay as MutationResult.Replay).descriptor.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name
        val already = harness.allocations.reveal(harness.reveal(allocation, "alice", "reveal-twice")).applied()
        already.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name
        already.code shouldBeEqualTo null
    }

    @Test
    fun `allocation replay returns the committed descriptor without duplicating durable effects`() {
        val fixture = harness.activePool("allocation-replay", listOf("REPLAY-ALLOCATION"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "replay-user", "reserve-allocation-replay"),
        ).applied()
        val command = harness.allocate(reservation, "replay-user", "allocate-replay")

        val allocated = harness.allocations.allocate(command).applied()
        val replay = harness.allocations.allocate(command) as MutationResult.Replay

        replay.descriptor.outcome shouldBeEqualTo "ALLOCATION_ALLOCATED"
        replay.descriptor.effectId shouldBeEqualTo allocated.allocationId
        replay.descriptor.revision shouldBeEqualTo allocated.revision
        harness.allocationCount() shouldBeEqualTo 1L
        harness.completedIdempotencyCount("voucher-allocate") shouldBeEqualTo 1L
        harness.tombstoneCount("voucher-allocate") shouldBeEqualTo 1L
    }

    @Test
    fun `active reservation release recycles entry without releasing allocated entries`() {
        val fixture = harness.activePool("release", listOf("RELEASE-ME", "KEEP-ALLOCATED"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "bob", "reserve-release")).applied()
        harness.reservations.release(
            ReleaseReservationCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                reservation.reservationId,
                "bob",
                reservation.revision,
                "release-active-key",
            ),
        ).applied().state shouldBeEqualTo ReservationState.RELEASED
        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.AVAILABLE

        val next = harness.reservations.reserve(harness.reserve(fixture, "bob", "reserve-again")).applied()
        val allocated = harness.allocations.allocate(harness.allocate(next, "bob", "allocate-again")).applied()
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.reservations.release(
                ReleaseReservationCommand(
                    harness.tenant,
                    fixture.campaign.campaignId,
                    next.reservationId,
                    "bob",
                    next.revision,
                    "release-allocated-key",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION
        harness.entryState(allocated.entryId) shouldBeEqualTo EntryState.ALLOCATED
        harness.redemptions.release(
            ReleaseAllocationCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                allocated.allocationId,
                "bob",
                allocated.revision,
                "release-allocation-key",
            ),
        ).applied().state shouldBeEqualTo EntryState.RELEASED
        harness.entryState(allocated.entryId) shouldBeEqualTo EntryState.RELEASED
        harness.userCounts(fixture.campaign.campaignId, "bob") shouldBeEqualTo UserCounts(0, 0, 1)
    }

    @Test
    fun `allocation rejects a reservation captured under a stale campaign policy`() {
        val fixture = harness.activePool("stale-policy", listOf("STALE-POLICY"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "policy-user", "reserve-stale-policy"),
        ).applied()
        harness.advanceCampaignPolicy(fixture.campaign.campaignId)

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.allocate(harness.allocate(reservation, "policy-user", "allocate-stale-policy"))
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION

        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.RESERVED
        harness.allocationCount() shouldBeEqualTo 0L
    }

    @Test
    fun `lost reveal response permits exactly one capacity first replacement`() {
        val fixture = harness.activePool("replacement", listOf("LOST-ONE", "REPLACEMENT", "UNTOUCHED"))
        val originalReservation = harness.reservations.reserve(harness.reserve(fixture, "carol", "reserve-original")).applied()
        val original = harness.allocations.allocate(harness.allocate(originalReservation, "carol", "allocate-original")).applied()
        val originalReveal = harness.allocations.reveal(harness.reveal(original, "carol", "lost-response")).applied()

        val replacementReservation = harness.allocations.replaceLostReveal(
            ReplaceLostRevealCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                original.allocationId,
                "carol",
                originalReveal.revision,
                "replace-once",
            ),
        ).applied()
        replacementReservation.state shouldBeEqualTo ReservationState.ACTIVE
        val replacement = harness.allocations.allocate(harness.allocate(replacementReservation, "carol", "allocate-replacement")).applied()
        replacement.replacementOrdinal shouldBeEqualTo 1
        harness.entryState(original.entryId) shouldBeEqualTo EntryState.REVOKED

        val secondLoss = harness.allocations.replaceLostReveal(
            ReplaceLostRevealCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                original.allocationId,
                "carol",
                originalReveal.revision,
                "replace-twice",
            ),
        ) as MutationResult.Replay
        secondLoss.descriptor.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name
        secondLoss.descriptor.effectId shouldBeEqualTo original.allocationId
        harness.consumedEntries() shouldBeEqualTo 2L
        harness.lifetimeConsumed(fixture.campaign.campaignId, "carol") shouldBeEqualTo 1
    }

    @Test
    fun `redemption verifies retained key digest and revoke converges terminally`() {
        val fixture = harness.activePool("redeem", listOf("REDEEM-CODE", "REVOKE-CODE"))
        val firstReservation = harness.reservations.reserve(harness.reserve(fixture, "dave", "reserve-redeem")).applied()
        val first = harness.allocations.allocate(harness.allocate(firstReservation, "dave", "allocate-redeem")).applied()
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    harness.tenant,
                    fixture.campaign.campaignId,
                    first.allocationId,
                    "dave",
                    CanonicalVoucherCode.of("WRONG"),
                    first.revision,
                    "redeem-wrong",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.SCOPE_NOT_FOUND
        val revealed = harness.allocations.reveal(harness.reveal(first, "dave", "reveal-redeem")).applied()
        harness.redemptions.redeem(
            RedeemVoucherCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                first.allocationId,
                "dave",
                CanonicalVoucherCode.of("REDEEM-CODE"),
                revealed.revision,
                "redeem-right",
            ),
        ).applied().state shouldBeEqualTo EntryState.REDEEMED

        val secondReservation = harness.reservations.reserve(harness.reserve(fixture, "erin", "reserve-revoke")).applied()
        val second = harness.allocations.allocate(harness.allocate(secondReservation, "erin", "allocate-revoke")).applied()
        harness.redemptions.revoke(
            RevokeAllocationCommand(harness.tenant, second.allocationId, second.revision, "operator-revoke"),
        ).applied().state shouldBeEqualTo EntryState.REVOKED
    }

    @Test
    fun `redemption rejects unrevealed and quarantined allocations with typed failures`() {
        val fixture = harness.activePool("redeem-guards", listOf("UNREVEALED", "QUARANTINED"))
        val unrevealedReservation = harness.reservations.reserve(
            harness.reserve(fixture, "guard-one", "reserve-unrevealed"),
        ).applied()
        val unrevealed = harness.allocations.allocate(
            harness.allocate(unrevealedReservation, "guard-one", "allocate-unrevealed"),
        ).applied()
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    harness.tenant,
                    fixture.campaign.campaignId,
                    unrevealed.allocationId,
                    "guard-one",
                    CanonicalVoucherCode.of("UNREVEALED"),
                    unrevealed.revision,
                    "redeem-unrevealed",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION

        val quarantineReservation = harness.reservations.reserve(
            harness.reserve(fixture, "guard-two", "reserve-quarantined"),
        ).applied()
        val quarantined = harness.allocations.allocate(
            harness.allocate(quarantineReservation, "guard-two", "allocate-quarantined"),
        ).applied()
        val revealed = harness.allocations.reveal(
            harness.reveal(quarantined, "guard-two", "reveal-quarantined"),
        ).applied()
        harness.markQuarantined(quarantined.entryId)
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    harness.tenant,
                    fixture.campaign.campaignId,
                    quarantined.allocationId,
                    "guard-two",
                    CanonicalVoucherCode.of("QUARANTINED"),
                    revealed.revision,
                    "redeem-quarantined",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID
    }

    @Test
    fun `revoke after a winning redemption preserves redeemed state and records one loser audit`() {
        val fixture = harness.activePool("redeem-wins", listOf("REDEEM-WINS"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "winner", "reserve-winner")).applied()
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "winner", "allocate-winner")).applied()
        val revealed = harness.allocations.reveal(harness.reveal(allocation, "winner", "reveal-winner")).applied()
        harness.redemptions.redeem(
            RedeemVoucherCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                allocation.allocationId,
                "winner",
                CanonicalVoucherCode.of("REDEEM-WINS"),
                revealed.revision,
                "redeem-winner",
            ),
        ).applied()
        val losingRevoke = RevokeAllocationCommand(
            harness.tenant,
            allocation.allocationId,
            revealed.revision,
            "revoke-loser",
        )

        repeat(2) {
            assertFailsWith<VoucherPoolLifecycleException> {
                harness.redemptions.revoke(losingRevoke)
            }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION
        }

        harness.entryState(allocation.entryId) shouldBeEqualTo EntryState.REDEEMED
        harness.revokeRaceLostAuditCount(allocation.allocationId) shouldBeEqualTo 1L
    }

    @Test
    fun `locked capacity is busy while consumed capacity is exhausted and terminally replayed`() {
        val fixture = harness.activePool("availability", listOf("ONLY-CODE"))
        val command = harness.reserve(fixture, "busy-user", "busy-key")

        harness.withEntryLocked {
            assertFailsWith<VoucherPoolLifecycleException> {
                harness.reservations.reserve(command)
            }.code shouldBeEqualTo VoucherPoolErrorCode.POOL_BUSY
        }

        harness.reservations.reserve(command).applied()
        val exhausted = harness.reserve(fixture, "exhausted-user", "exhausted-key")
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.reservations.reserve(exhausted)
        }.code shouldBeEqualTo VoucherPoolErrorCode.POOL_EXHAUSTED
        val replay = harness.reservations.reserve(exhausted) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.POOL_EXHAUSTED
    }

    @Test
    fun `reservation effect rolls back when idempotency finalize fails and same key retries`() {
        val fixture = harness.activePool("finalize-rollback", listOf("ROLLBACK-CODE"))
        val command = harness.reserve(fixture, "rollback-user", "rollback-key")

        harness.installFinalizeFailureTrigger()
        try {
            assertFailsWith<RuntimeException> { harness.reservations.reserve(command) }
        } finally {
            harness.dropFinalizeFailureTrigger()
        }

        harness.reservationCount() shouldBeEqualTo 0L
        harness.consumedEntries() shouldBeEqualTo 0L
        harness.completedIdempotencyCount("voucher-reserve") shouldBeEqualTo 0L
        harness.tombstoneCount("voucher-reserve") shouldBeEqualTo 0L
        harness.reservations.reserve(command).applied().state shouldBeEqualTo ReservationState.ACTIVE
        harness.reservationCount() shouldBeEqualTo 1L
        harness.completedIdempotencyCount("voucher-reserve") shouldBeEqualTo 1L
        harness.tombstoneCount("voucher-reserve") shouldBeEqualTo 1L
    }

    @Test
    fun `terminal reveal failure replays its terminal descriptor without code`() {
        val fixture = harness.activePool("expired-reveal", listOf("EXPIRED-REVEAL"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "expired-user", "expired-reserve")).applied()
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "expired-user", "expired-allocate")).applied()
        harness.expireAllocation(allocation.allocationId)
        val reveal = harness.reveal(allocation, "expired-user", "expired-reveal-key")

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.reveal(reveal)
        }.code shouldBeEqualTo VoucherPoolErrorCode.ALLOCATION_EXPIRED
        val replay = harness.allocations.reveal(reveal) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.ALLOCATION_EXPIRED
        replay.descriptor.effectId shouldBeEqualTo null
    }

    @Test
    fun `corrupt ciphertext commits quarantine and retries with the typed failure`() {
        val fixture = harness.activePool("quarantine", listOf("QUARANTINE-CODE"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "quarantine-user", "quarantine-reserve")).applied()
        val command = harness.allocate(reservation, "quarantine-user", "quarantine-allocate")
        harness.tamperCiphertext(reservation.entryId)

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.allocate(command)
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID
        harness.isQuarantined(reservation.entryId) shouldBeEqualTo true
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.allocate(command)
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID
    }

    @Test
    fun `pool depth projection follows foreground lifecycle transitions`() {
        val fixture = harness.activePool("depth", listOf("DEPTH-ONE", "DEPTH-TWO"))
        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 2L
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "depth-user", "depth-reserve")).applied()
        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.RESERVED) shouldBeEqualTo 1L
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "depth-user", "depth-allocate")).applied()
        harness.poolDepth(fixture.batch.batchId, EntryState.RESERVED) shouldBeEqualTo 0L
        harness.poolDepth(fixture.batch.batchId, EntryState.ALLOCATED) shouldBeEqualTo 1L
        harness.redemptions.release(
            ReleaseAllocationCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                allocation.allocationId,
                "depth-user",
                allocation.revision,
                "depth-release",
            ),
        ).applied()
        harness.poolDepth(fixture.batch.batchId, EntryState.ALLOCATED) shouldBeEqualTo 0L
        harness.poolDepth(fixture.batch.batchId, EntryState.RELEASED) shouldBeEqualTo 1L
    }

    @Test
    fun `active campaign reports the paused batch state rather than campaign inactivity`() {
        val fixture = harness.activePool("no-eligible-batch", listOf("PAUSED-BATCH-CODE"))
        harness.pauseBatch(fixture.batch.batchId)
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.reservations.reserve(harness.reserve(fixture, "paused-user", "paused-reserve"))
        }.code shouldBeEqualTo VoucherPoolErrorCode.BATCH_PAUSED
    }
}

internal class LifecycleHarness(private val suffix: String) {
    val tenant = "tenant-$suffix"
    private val schema = "voucher_${suffix}_${Base58.randomString(8).lowercase()}"
    private val dataSource = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl)
        user = postgres.username ?: PostgreSQLServer.USERNAME
        password = postgres.password ?: PostgreSQLServer.PASSWORD
        currentSchema = schema
    }
    private lateinit var digests: VoucherDigestService
    private lateinit var commandService: JdbcCampaignBatchCommandService
    private lateinit var jdbcExecutor: VoucherPoolJdbcExecutor
    private lateinit var voucherRepository: VoucherPoolRepository
    lateinit var reservations: JdbcReservationService
    lateinit var allocations: JdbcAllocationService
    lateinit var redemptions: JdbcRedemptionService

    fun migrate() {
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $schema") }
        VoucherPoolMigrationRunner(dataSource, VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")), 537_007L).migrate()
        digests = digestService()
        rebuildServices()
    }

    fun cleanup() {
        adminConnection().use { it.createStatement().execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
    }

    fun reset() {
        dataSource.connection.use { connection ->
            connection.createStatement().execute(
                "TRUNCATE voucher_pool_worker_claims,voucher_pool_audits,voucher_pool_http_idempotency,voucher_pool_command_tombstones,voucher_pool_allocations,voucher_pool_reservations,voucher_pool_user_limits,voucher_pool_entries,voucher_pool_code_dedup,voucher_pool_batches,voucher_pool_campaigns CASCADE",
            )
        }
        rebuildServices()
    }

    fun activePool(name: String, codes: List<String>): LifecycleFixture {
        val campaignId = UUID.nameUUIDFromBytes("$suffix-campaign-$name".toByteArray())
        val campaign = commandService.createCampaign(
            CreateCampaignCommand(
                tenant, campaignId, Instant.now().minusSeconds(60), Instant.now().plusSeconds(3_600),
                VoucherPoolPolicy.of(4, 1.minutes, 5.minutes, 1), key("campaign-$name"),
            ),
        ).applied()
        val activeCampaign = commandService.activateCampaign(
            CampaignRevisionCommand(tenant, campaignId, campaign.revision, key("campaign-active-$name")),
        ).applied()
        val batchId = UUID.nameUUIDFromBytes("$suffix-batch-$name".toByteArray())
        val batch = commandService.createImportBatch(
            CreateImportBatchCommand(
                tenant, batchId, campaignId, BatchSourceKind.IMPORTED, digest("manifest-$name"), digest("request-$name"),
                codes.size.toLong(), Instant.now().minusSeconds(1), initialCodes = codes, idempotencyKey = key("batch-$name"),
            ),
        ).applied()
        val activeBatch = commandService.activateBatch(
            BatchRevisionCommand(tenant, campaignId, batchId, batch.revision, key("batch-active-$name")),
        ).applied()
        return LifecycleFixture(activeCampaign, activeBatch)
    }

    fun reserve(fixture: LifecycleFixture, user: String, key: String) =
        ReserveVoucherCommand(tenant, fixture.campaign.campaignId, user, this.key(key))

    fun allocate(reservation: ReservationSnapshot, user: String, key: String) =
        AllocateVoucherCommand(
            tenant,
            reservation.campaignId,
            reservation.reservationId,
            user,
            reservation.revision,
            this.key(key),
        )

    fun reveal(allocation: AllocationSnapshot, user: String, key: String) =
        RevealVoucherCommand(
            tenant,
            allocation.campaignId,
            allocation.allocationId,
            user,
            allocation.revision,
            this.key(key),
        )

    fun entryState(entryId: UUID): EntryState = query("SELECT state FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?", entryId) {
        EntryState.valueOf(it.getString(1))
    }

    fun hasVerificationDigest(entryId: UUID): Boolean = query(
        "SELECT verification_digest IS NOT NULL FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?", entryId,
    ) { it.getBoolean(1) }

    fun hasCiphertext(entryId: UUID): Boolean = query(
        "SELECT code_ciphertext IS NOT NULL FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?", entryId,
    ) { it.getBoolean(1) }

    fun consumedEntries(): Long = scalar("SELECT count(*) FROM voucher_pool_entries WHERE tenant_id=? AND state<>'AVAILABLE'")

    fun lifetimeConsumed(campaignId: UUID, user: String): Int {
        val digest = digests.userIdentity(tenant, campaignId, user)
        return dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT lifetime_consumed FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=? AND user_digest=?").use { statement ->
                statement.setString(1, tenant); statement.setObject(2, campaignId); statement.setBytes(3, digest.copyBytes())
                statement.executeQuery().use { result -> result.next(); result.getInt(1) }
            }
        }
    }

    fun userCounts(campaignId: UUID, user: String): UserCounts {
        val digest = digests.userIdentity(tenant, campaignId, user)
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT active_reservations,active_allocations,lifetime_consumed FROM voucher_pool_user_limits WHERE tenant_id=? AND campaign_id=? AND user_digest=?",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setObject(2, campaignId)
                statement.setBytes(3, digest.copyBytes())
                statement.executeQuery().use { result ->
                    result.next()
                    UserCounts(result.getInt(1), result.getInt(2), result.getInt(3))
                }
            }
        }
    }

    fun withEntryLocked(block: () -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "SELECT entry_id FROM voucher_pool_entries WHERE tenant_id=? AND state='AVAILABLE' ORDER BY source_ordinal LIMIT 1 FOR UPDATE",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.executeQuery().use { result -> check(result.next()) }
            }
            try {
                block()
            } finally {
                connection.rollback()
            }
        }
    }

    fun installFinalizeFailureTrigger() {
        execute(
            """CREATE FUNCTION fail_lifecycle_finalize() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'simulated finalize crash' USING ERRCODE='40001'; END IF; RETURN NEW; END ${'$'}${'$'};
                CREATE TRIGGER fail_lifecycle_finalize BEFORE UPDATE ON voucher_pool_http_idempotency
                FOR EACH ROW EXECUTE FUNCTION fail_lifecycle_finalize()""",
        )
    }

    fun dropFinalizeFailureTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_lifecycle_finalize ON voucher_pool_http_idempotency; " +
                "DROP FUNCTION IF EXISTS fail_lifecycle_finalize()",
        )
    }

    fun reservationCount(): Long = scalar("SELECT count(*) FROM voucher_pool_reservations WHERE tenant_id=?")

    fun allocationCount(): Long = scalar("SELECT count(*) FROM voucher_pool_allocations WHERE tenant_id=?")

    fun completedIdempotencyCount(operation: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_http_idempotency WHERE tenant_id=? AND operation=? AND status='COMPLETED' AND descriptor IS NOT NULL",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setString(2, operation)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    fun tombstoneCount(operation: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_command_tombstones WHERE tenant_id=? AND operation=?",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setString(2, operation)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    fun installReservationGuardProbe(parties: Int): ReservationGuardProbe = ReservationGuardProbe(parties).also { probe ->
        rebuildServices { repository -> GuardProbeVoucherPoolRepository(repository, probe) }
    }

    fun sharedGuardHolders(probe: ReservationGuardProbe, relation: String): Int =
        dataSource.sharedLockHolders(probe.backendPids.toSet(), relation)

    fun guardWaiters(probe: ReservationGuardProbe): Int = dataSource.lockWaiters(probe.backendPids.toSet())

    fun terminalAuditCount(allocationId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_audits WHERE tenant_id=? AND aggregate_type='ALLOCATION' AND aggregate_id=? AND reason_code IN ('REDEEMED','OPERATOR_REVOKED')",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, allocationId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    fun revokeRaceLostAuditCount(allocationId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_audits WHERE tenant_id=? AND aggregate_type='ALLOCATION' AND aggregate_id=? AND reason_code='REVOKE_RACE_LOST'",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, allocationId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    fun expireAllocation(allocationId: UUID) {
        execute(
            "UPDATE voucher_pool_allocations SET allocation_expires_at=now()-interval '1 second' " +
                "WHERE tenant_id='$tenant' AND allocation_id='$allocationId'; " +
                "UPDATE voucher_pool_entries SET allocated_at=now()-interval '2 seconds', " +
                "allocation_expires_at=now()-interval '1 second' " +
                "WHERE tenant_id='$tenant' AND allocation_id='$allocationId'",
        )
    }

    fun expireReservation(reservationId: UUID) {
        execute(
            "UPDATE voucher_pool_reservations SET reservation_expires_at=now()-interval '1 second' " +
                "WHERE tenant_id='$tenant' AND reservation_id='$reservationId'; " +
                "UPDATE voucher_pool_entries SET reserved_at=now()-interval '2 seconds', " +
                "reservation_expires_at=now()-interval '1 second' " +
                "WHERE tenant_id='$tenant' AND reservation_id='$reservationId'",
        )
    }

    fun reservationState(reservationId: UUID): ReservationState = query(
        "SELECT state FROM voucher_pool_reservations WHERE tenant_id=? AND reservation_id=?",
        reservationId,
    ) { ReservationState.valueOf(it.getString(1)) }

    fun batchState(batchId: UUID): BatchState = query(
        "SELECT state FROM voucher_pool_batches WHERE tenant_id=? AND batch_id=?",
        batchId,
    ) { BatchState.valueOf(it.getString(1)) }

    fun expireBatch(batchId: UUID) {
        execute(
            "UPDATE voucher_pool_batches SET expires_at=now()-interval '1 second' " +
                "WHERE tenant_id='$tenant' AND batch_id='$batchId'",
        )
    }

    fun campaignState(campaignId: UUID): CampaignState = query(
        "SELECT state FROM voucher_pool_campaigns WHERE tenant_id=? AND campaign_id=?",
        campaignId,
    ) { CampaignState.valueOf(it.getString(1)) }

    fun setCampaignState(campaignId: UUID, state: CampaignState) {
        execute(
            "UPDATE voucher_pool_campaigns SET state='${state.name}',revision=revision+1 " +
                "WHERE tenant_id='$tenant' AND campaign_id='$campaignId'",
        )
    }

    fun corruptPoolDepth(batchId: UUID, state: EntryState, count: Long) {
        execute(
            "UPDATE voucher_pool_pool_depth SET entry_count=$count " +
                "WHERE tenant_id='$tenant' AND batch_id='$batchId' AND state='${state.name}'",
        )
    }

    fun corruptUserCounts(campaignId: UUID, user: String, reservations: Int, allocations: Int, lifetime: Int) {
        val userDigest = digests.userIdentity(tenant, campaignId, user)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """UPDATE voucher_pool_user_limits SET active_reservations=?,active_allocations=?,lifetime_consumed=?
                    WHERE tenant_id=? AND campaign_id=? AND user_digest=?""",
            ).use { statement ->
                statement.setInt(1, reservations)
                statement.setInt(2, allocations)
                statement.setInt(3, lifetime)
                statement.setString(4, tenant)
                statement.setObject(5, campaignId)
                statement.setBytes(6, userDigest.copyBytes())
                statement.executeUpdate()
            }
        }
    }

    fun auditCount(aggregateType: String, aggregateId: UUID, reasonCode: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_audits WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=? AND reason_code=?",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setString(2, aggregateType)
            statement.setObject(3, aggregateId)
            statement.setString(4, reasonCode)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    fun reconciliationAuditCounts(batchId: UUID): Pair<Long, Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT before_count,after_count FROM voucher_pool_audits
                WHERE tenant_id=? AND aggregate_type='RECONCILIATION' AND aggregate_id=?
                ORDER BY revision DESC LIMIT 1""",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, batchId)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) to result.getLong(2) }
        }
    }

    fun workerExecutor(): VoucherPoolJdbcExecutor = jdbcExecutor

    fun workerRepository(): VoucherPoolRepository = voucherRepository

    fun tamperCiphertext(entryId: UUID) {
        execute(
            "UPDATE voucher_pool_entries SET code_ciphertext=decode('00','hex') " +
                "WHERE tenant_id='$tenant' AND entry_id='$entryId'",
        )
    }

    fun isQuarantined(entryId: UUID): Boolean = query(
        "SELECT quarantined_at IS NOT NULL FROM voucher_pool_entries WHERE tenant_id=? AND entry_id=?",
        entryId,
    ) { it.getBoolean(1) }

    fun markQuarantined(entryId: UUID) {
        execute(
            "UPDATE voucher_pool_entries SET quarantined_at=now() " +
                "WHERE tenant_id='$tenant' AND entry_id='$entryId'",
        )
    }

    fun advanceCampaignPolicy(campaignId: UUID) {
        execute(
            "UPDATE voucher_pool_campaigns SET policy_version=policy_version+1," +
                "allocation_ttl_seconds=allocation_ttl_seconds+60,revision=revision+1 " +
                "WHERE tenant_id='$tenant' AND campaign_id='$campaignId'",
        )
    }

    @Suppress("NestedBlockDepth") // Explicit JDBC resource scopes keep the projection query leak-free.
    fun poolDepth(batchId: UUID, state: EntryState): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT entry_count FROM voucher_pool_pool_depth WHERE tenant_id=? AND batch_id=? AND state=?",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, batchId)
            statement.setString(3, state.name)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
        }
    }

    fun pauseBatch(batchId: UUID) {
        execute(
            "UPDATE voucher_pool_batches SET state='PAUSED',revision=revision+1 " +
                "WHERE tenant_id='$tenant' AND batch_id='$batchId'",
        )
    }

    private fun rebuildServices(
        repositoryTransform: (VoucherPoolRepository) -> VoucherPoolRepository = { it },
    ) {
        val repository = repositoryTransform(JdbcVoucherPoolRepository())
        val executor = VoucherPoolJdbcExecutor(DatabasePermitGate.default(32), SpringTransactionManager(dataSource, DatabaseConfig {}, false))
        voucherRepository = repository
        jdbcExecutor = executor
        val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)
        val crypto = AesGcmVoucherEnvelopeCrypto(VoucherKekRing.of(VoucherKek.of("test-kek", keyBytes(11))), digests)
        commandService = JdbcCampaignBatchCommandService(executor, repository, idempotency, digests, crypto)
        reservations = JdbcReservationService(executor, repository, idempotency, digests)
        allocations = JdbcAllocationService(executor, repository, idempotency, digests, VoucherCryptoStorage(repository, crypto))
        redemptions = JdbcRedemptionService(executor, repository, idempotency, digests)
    }

    private fun digest(value: String) = DigestValue.of(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    private fun key(value: String) = "voucher-lifecycle-$suffix-$value"
    private fun keyBytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }
    private fun digestService() = VoucherDigestService(
        DigestKey.of(7, keyBytes(7)), DigestKey.of(4, keyBytes(4)),
        mapOf(
            DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
            DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
            DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
            DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
        ),
    )

    private fun <T> query(sql: String, id: UUID, mapper: (java.sql.ResultSet) -> T): T = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenant); statement.setObject(2, id)
            statement.executeQuery().use { result -> result.next(); mapper(result) }
        }
    }

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, tenant); statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun adminConnection(): Connection = DriverManager.getConnection(
        postgres.jdbcUrl, postgres.username ?: PostgreSQLServer.USERNAME, postgres.password ?: PostgreSQLServer.PASSWORD,
    )

    companion object { private val postgres = PostgreSQLServer.Launcher.postgres }
}

internal data class LifecycleFixture(val campaign: CampaignSnapshot, val batch: BatchSnapshot)

internal data class UserCounts(
    val activeReservations: Int,
    val activeAllocations: Int,
    val lifetimeConsumed: Int,
)

internal class ReservationGuardProbe(parties: Int) {
    private val entered = CountDownLatch(parties)
    private val release = CountDownLatch(1)
    val backendPids = ConcurrentLinkedQueue<Int>()

    fun enter(connection: Connection) {
        connection.createStatement().executeQuery("SELECT pg_backend_pid()").use { result ->
            result.next()
            backendPids += result.getInt(1)
        }
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "reservation guard probe release timed out" }
    }

    fun awaitEntered(): Boolean = entered.await(2, TimeUnit.SECONDS)

    fun release() = release.countDown()
}

private class GuardProbeVoucherPoolRepository(
    private val delegate: VoucherPoolRepository,
    private val probe: ReservationGuardProbe,
) : VoucherPoolRepository by delegate {
    override fun lockReservationGuards(
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): ReservationGuards? = delegate.lockReservationGuards(tenantId, campaignId, userDigest)?.also {
        probe.enter(currentConnection())
    }
}

internal fun <T> MutationResult<T>.applied(): T = (this as MutationResult.Applied<T>).value
