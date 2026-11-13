@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.await
import io.bluetape4k.jdbc.sql.singleInt
import io.bluetape4k.jdbc.sql.singleLong
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.checkBeTrue
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.database.getHikariDataSource
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
import org.springframework.core.io.ClassPathResource
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolLifecycleIntegrationTest {

    companion object: KLogging()

    private val harness = LifecycleHarness("lifecycle")

    @BeforeAll
    fun migrate() = harness.migrate()

    @AfterAll
    fun cleanup() {
        harness.cleanup()
    }

    @BeforeEach
    fun reset() = harness.reset()

    @Test
    fun `allocation retains ciphertext and verification digest until first reveal commits`() {
        val fixture = harness.activePool("reveal", listOf("SECRET-REVEAL"))
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "alice", "reserve-reveal"))
            .applied()
        log.debug { "reservation: $reservation" }

        val allocation = harness.allocations
            .allocate(harness.allocate(reservation, "alice", "allocate-reveal"))
            .applied()
        log.debug { "allocation: $allocation" }

        harness.entryState(allocation.entryId) shouldBeEqualTo EntryState.ALLOCATED
        harness.hasVerificationDigest(allocation.entryId).shouldBeTrue()
        harness.hasCiphertext(allocation.entryId).shouldBeTrue()

        val revealed = harness.allocations
            .reveal(harness.reveal(allocation, "alice", "reveal-once"))
            .applied()
        log.debug { "reveal: $revealed" }
        revealed.code shouldBeEqualTo CanonicalVoucherCode("SECRET-REVEAL")
        harness.hasCiphertext(allocation.entryId).shouldBeFalse()

        val replay = harness.allocations
            .reveal(harness.reveal(allocation, "alice", "reveal-once"))
        log.debug { "replay: $replay" }
        replay.shouldBeInstanceOf<MutationResult.Replay>()
        replay.descriptor.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name

        val already = harness.allocations
            .reveal(harness.reveal(allocation, "alice", "reveal-twice"))
            .applied()
        log.debug { "already: $already" }
        already.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name
        already.code.shouldBeNull()
    }

    @Test
    fun `allocation replay returns the committed descriptor without duplicating durable effects`() {
        val fixture = harness.activePool("allocation-replay", listOf("REPLAY-ALLOCATION"))
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "replay-user", "reserve-allocation-replay"))
            .applied()
        val command = harness.allocate(reservation, "replay-user", "allocate-replay")

        val allocated = harness.allocations.allocate(command).applied()
        val replay = harness.allocations.allocate(command).shouldBeInstanceOf<MutationResult.Replay>()

        log.debug { "allocated: $allocated" }
        log.debug { "replay: $replay" }

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
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "bob", "reserve-release"))
            .applied()
        log.debug { "reservation: $reservation" }

        harness.reservations
            .release(
                ReleaseReservationCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    reservationId = reservation.reservationId,
                    canonicalUser = "bob",
                    expectedRevision = reservation.revision,
                    idempotencyKey = "release-active-key",
                ),
            )
            .applied().state shouldBeEqualTo ReservationState.RELEASED

        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.AVAILABLE

        val next = harness.reservations
            .reserve(harness.reserve(fixture, "bob", "reserve-again"))
            .applied()
        log.debug { "next: $next" }

        val allocated = harness.allocations
            .allocate(harness.allocate(next, "bob", "allocate-again"))
            .applied()
        log.debug { "allocated: $allocated" }

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.reservations.release(
                ReleaseReservationCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    reservationId = next.reservationId,
                    canonicalUser = "bob",
                    expectedRevision = next.revision,
                    idempotencyKey = "release-allocated-key",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION

        harness.entryState(allocated.entryId) shouldBeEqualTo EntryState.ALLOCATED

        harness.redemptions
            .release(
                ReleaseAllocationCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = allocated.allocationId,
                    canonicalUser = "bob",
                    expectedRevision = allocated.revision,
                    idempotencyKey = "release-allocation-key",
                ),
            )
            .applied().state shouldBeEqualTo EntryState.RELEASED
        harness.entryState(allocated.entryId) shouldBeEqualTo EntryState.RELEASED
        harness.userCounts(fixture.campaign.campaignId, "bob") shouldBeEqualTo UserCounts(0, 0, 1)
    }

    @Test
    fun `allocation rejects a reservation captured under a stale campaign policy`() {
        val fixture = harness.activePool("stale-policy", listOf("STALE-POLICY"))
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "policy-user", "reserve-stale-policy"))
            .applied()
        log.debug { "reservation: $reservation" }
        harness.advanceCampaignPolicy(fixture.campaign.campaignId)

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations
                .allocate(harness.allocate(reservation, "policy-user", "allocate-stale-policy"))
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION

        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.RESERVED
        harness.allocationCount() shouldBeEqualTo 0L
    }

    @Test
    fun `lost reveal response permits exactly one capacity first replacement`() {
        val fixture = harness.activePool("replacement", listOf("LOST-ONE", "REPLACEMENT", "UNTOUCHED"))
        val originalReservation = harness.reservations
            .reserve(harness.reserve(fixture, "carol", "reserve-original"))
            .applied()
        log.debug { "originalReservation: $originalReservation" }

        val original = harness.allocations
            .allocate(harness.allocate(originalReservation, "carol", "allocate-original"))
            .applied()
        log.debug { "original: $original" }

        val originalReveal = harness.allocations
            .reveal(harness.reveal(original, "carol", "lost-response"))
            .applied()
        log.debug { "originalReveal: $originalReveal" }

        val replacementReservation = harness.allocations
            .replaceLostReveal(
                ReplaceLostRevealCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = original.allocationId,
                    canonicalUser = "carol",
                    expectedRevision = originalReveal.revision,
                    idempotencyKey = "replace-once",
                ),
            )
            .applied()
        replacementReservation.state shouldBeEqualTo ReservationState.ACTIVE

        val replacement = harness.allocations
            .allocate(harness.allocate(replacementReservation, "carol", "allocate-replacement"))
            .applied()
        log.debug { "replacement: $replacement" }
        replacement.replacementOrdinal shouldBeEqualTo 1
        harness.entryState(original.entryId) shouldBeEqualTo EntryState.REVOKED

        val secondLoss = harness.allocations
            .replaceLostReveal(
                ReplaceLostRevealCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = original.allocationId,
                    canonicalUser = "carol",
                    expectedRevision = originalReveal.revision,
                    idempotencyKey = "replace-twice",
                ),
            ).shouldBeInstanceOf<MutationResult.Replay>()

        log.debug { "secondLoss: $secondLoss" }
        secondLoss.descriptor.outcome shouldBeEqualTo VoucherPoolErrorCode.ALREADY_REVEALED.name
        secondLoss.descriptor.effectId shouldBeEqualTo original.allocationId

        harness.consumedEntries() shouldBeEqualTo 2L
        harness.lifetimeConsumed(fixture.campaign.campaignId, "carol") shouldBeEqualTo 1
    }

    @Test
    fun `redemption verifies retained key digest and revoke converges terminally`() {
        val fixture = harness.activePool("redeem", listOf("REDEEM-CODE", "REVOKE-CODE"))
        val firstReservation = harness.reservations
            .reserve(harness.reserve(fixture, "dave", "reserve-redeem"))
            .applied()

        val first = harness.allocations
            .allocate(harness.allocate(firstReservation, "dave", "allocate-redeem"))
            .applied()

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = first.allocationId,
                    canonicalUser = "dave",
                    code = CanonicalVoucherCode.of("WRONG"),
                    expectedRevision = first.revision,
                    idempotencyKey = "redeem-wrong",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.SCOPE_NOT_FOUND

        val revealed = harness.allocations
            .reveal(harness.reveal(first, "dave", "reveal-redeem"))
            .applied()
        log.debug { "revealed: $revealed" }

        harness.redemptions.redeem(
            RedeemVoucherCommand(
                tenantId = harness.tenant,
                campaignId = fixture.campaign.campaignId,
                allocationId = first.allocationId,
                canonicalUser = "dave",
                code = CanonicalVoucherCode.of("REDEEM-CODE"),
                expectedRevision = revealed.revision,
                idempotencyKey = "redeem-right",
            ),
        ).applied().state shouldBeEqualTo EntryState.REDEEMED

        val secondReservation = harness.reservations
            .reserve(harness.reserve(fixture, "erin", "reserve-revoke"))
            .applied()
        val second = harness.allocations
            .allocate(harness.allocate(secondReservation, "erin", "allocate-revoke"))
            .applied()

        harness.redemptions
            .revoke(
                RevokeAllocationCommand(
                    tenantId = harness.tenant,
                    allocationId = second.allocationId,
                    expectedRevision = second.revision,
                    idempotencyKey = "operator-revoke"
                ),
            ).applied().state shouldBeEqualTo EntryState.REVOKED
    }

    @Test
    fun `redemption rejects unrevealed and quarantined allocations with typed failures`() {
        val fixture = harness.activePool("redeem-guards", listOf("UNREVEALED", "QUARANTINED"))
        val unrevealedReservation = harness.reservations
            .reserve(harness.reserve(fixture, "guard-one", "reserve-unrevealed"))
            .applied()
        val unrevealed = harness.allocations
            .allocate(harness.allocate(unrevealedReservation, "guard-one", "allocate-unrevealed"))
            .applied()

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = unrevealed.allocationId,
                    canonicalUser = "guard-one",
                    code = CanonicalVoucherCode.of("UNREVEALED"),
                    expectedRevision = unrevealed.revision,
                    idempotencyKey = "redeem-unrevealed",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION

        val quarantineReservation = harness.reservations
            .reserve(harness.reserve(fixture, "guard-two", "reserve-quarantined"))
            .applied()
        log.debug { "quarantine reservation: $quarantineReservation" }

        val quarantined = harness.allocations
            .allocate(harness.allocate(quarantineReservation, "guard-two", "allocate-quarantined"))
            .applied()
        log.debug { "quarantined: $quarantined" }

        val revealed = harness.allocations
            .reveal(harness.reveal(quarantined, "guard-two", "reveal-quarantined"))
            .applied()
        log.debug { "revealed: $revealed" }

        harness.markQuarantined(quarantined.entryId)
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.redemptions.redeem(
                RedeemVoucherCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = quarantined.allocationId,
                    canonicalUser = "guard-two",
                    code = CanonicalVoucherCode.of("QUARANTINED"),
                    expectedRevision = revealed.revision,
                    idempotencyKey = "redeem-quarantined",
                ),
            )
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID
    }

    @Test
    fun `revoke after a winning redemption preserves redeemed state and records one loser audit`() {
        val fixture = harness.activePool("redeem-wins", listOf("REDEEM-WINS"))
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "winner", "reserve-winner"))
            .applied()
        val allocation = harness.allocations
            .allocate(harness.allocate(reservation, "winner", "allocate-winner"))
            .applied()
        val revealed = harness.allocations
            .reveal(harness.reveal(allocation, "winner", "reveal-winner"))
            .applied()

        harness.redemptions
            .redeem(
                RedeemVoucherCommand(
                    tenantId = harness.tenant,
                    campaignId = fixture.campaign.campaignId,
                    allocationId = allocation.allocationId,
                    canonicalUser = "winner",
                    code = CanonicalVoucherCode.of("REDEEM-WINS"),
                    expectedRevision = revealed.revision,
                    idempotencyKey = "redeem-winner",
                ),
            )
            .applied()

        val losingRevoke = RevokeAllocationCommand(
            tenantId = harness.tenant,
            allocationId = allocation.allocationId,
            expectedRevision = revealed.revision,
            idempotencyKey = "revoke-loser",
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
            assertFailsWith<RuntimeException> {
                harness.reservations.reserve(command)
            }
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
        val reservation =
            harness.reservations.reserve(harness.reserve(fixture, "expired-user", "expired-reserve")).applied()
        val allocation =
            harness.allocations.allocate(harness.allocate(reservation, "expired-user", "expired-allocate")).applied()
        harness.expireAllocation(allocation.allocationId)
        val reveal = harness.reveal(allocation, "expired-user", "expired-reveal-key")

        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.reveal(reveal)
        }.code shouldBeEqualTo VoucherPoolErrorCode.ALLOCATION_EXPIRED
        val replay = harness.allocations.reveal(reveal) as MutationResult.Replay
        replay.descriptor.terminalCode shouldBeEqualTo VoucherPoolErrorCode.ALLOCATION_EXPIRED
        replay.descriptor.effectId.shouldBeNull()
    }

    @Test
    fun `corrupt ciphertext commits quarantine and retries with the typed failure`() {
        val fixture = harness.activePool("quarantine", listOf("QUARANTINE-CODE"))
        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "quarantine-user", "quarantine-reserve"))
            .applied()
        log.debug { "reservation: $reservation" }

        val command = harness.allocate(reservation, "quarantine-user", "quarantine-allocate")

        harness.tamperCiphertext(reservation.entryId)
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.allocate(command)
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID

        harness.isQuarantined(reservation.entryId).shouldBeTrue()
        assertFailsWith<VoucherPoolLifecycleException> {
            harness.allocations.allocate(command)
        }.code shouldBeEqualTo VoucherPoolErrorCode.CIPHERTEXT_INVALID
    }

    @Test
    fun `pool depth projection follows foreground lifecycle transitions`() {
        val fixture = harness.activePool("depth", listOf("DEPTH-ONE", "DEPTH-TWO"))
        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 2L

        val reservation = harness.reservations
            .reserve(harness.reserve(fixture, "depth-user", "depth-reserve"))
            .applied()
        log.debug { "reservation: $reservation" }

        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.RESERVED) shouldBeEqualTo 1L

        val allocation = harness.allocations
            .allocate(harness.allocate(reservation, "depth-user", "depth-allocate"))
            .applied()
        log.debug { "allocation: $allocation" }

        harness.poolDepth(fixture.batch.batchId, EntryState.RESERVED) shouldBeEqualTo 0L
        harness.poolDepth(fixture.batch.batchId, EntryState.ALLOCATED) shouldBeEqualTo 1L

        harness.redemptions.release(
            ReleaseAllocationCommand(
                tenantId = harness.tenant,
                campaignId = fixture.campaign.campaignId,
                allocationId = allocation.allocationId,
                canonicalUser = "depth-user",
                expectedRevision = allocation.revision,
                idempotencyKey = "depth-release",
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

@Suppress("SqlResolve")
internal class LifecycleHarness(private val suffix: String) {

    companion object: KLogging() {
        private val postgres = PostgreSQLServer.Launcher.postgres
    }

    val tenant = "tenant-$suffix"
    private val tenantSchema = "voucher_${suffix}_${Base58.randomString(8).lowercase()}"

    private val dataSource = postgres.getHikariDataSource {
        schema = tenantSchema
    }

    private lateinit var digests: VoucherDigestService
    private lateinit var commandService: JdbcCampaignBatchCommandService
    private lateinit var jdbcExecutor: VoucherPoolJdbcExecutor
    private lateinit var voucherRepository: VoucherPoolRepository
    lateinit var reservations: JdbcReservationService
    lateinit var allocations: JdbcAllocationService
    lateinit var redemptions: JdbcRedemptionService

    fun migrate() {
        adminConnection().use { it.createStatement().execute("CREATE SCHEMA $tenantSchema") }

        VoucherPoolMigrationRunner(
            dataSource = dataSource,
            migration = VoucherPoolMigration("001", ClassPathResource("db/migration/V001__voucher_pool.sql")),
            advisoryLockKey = 537_007L
        ).migrate()

        digests = digestService()
        rebuildServices()
    }

    fun cleanup() {
        adminConnection().use {
            it.createStatement().execute("DROP SCHEMA IF EXISTS $tenantSchema CASCADE")
        }
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
        val campaignId = UUID.nameUUIDFromBytes("$suffix-campaign-$name".toUtf8Bytes())
        val campaign = commandService.createCampaign(
            CreateCampaignCommand(
                tenantId = tenant,
                campaignId = campaignId,
                startsAt = Instant.now().minusSeconds(60),
                endsAt = Instant.now().plusSeconds(3_600),
                policy = VoucherPoolPolicy.of(4, 1.minutes, 5.minutes, 1),
                idempotencyKey = key("campaign-$name"),
            ),
        ).applied()

        val activeCampaign = commandService.activateCampaign(
            CampaignRevisionCommand(
                tenantId = tenant,
                campaignId = campaignId,
                expectedRevision = campaign.revision,
                idempotencyKey = key("campaign-active-$name")
            ),
        ).applied()

        val batchId = UUID.nameUUIDFromBytes("$suffix-batch-$name".toUtf8Bytes())
        val batch = commandService.createImportBatch(
            CreateImportBatchCommand(
                tenantId = tenant,
                batchId = batchId,
                campaignId = campaignId,
                sourceKind = BatchSourceKind.IMPORTED,
                manifestDigest = digest("manifest-$name"),
                requestFingerprint = digest("request-$name"),
                expectedCount = codes.size.toLong(),
                activatesAt = Instant.now().minusSeconds(1),
                initialCodes = codes,
                idempotencyKey = key("batch-$name"),
            ),
        ).applied()

        val activeBatch = commandService.activateBatch(
            BatchRevisionCommand(
                tenantId = tenant,
                campaignId = campaignId,
                batchId = batchId,
                expectedRevision = batch.revision,
                idempotencyKey = key("batch-active-$name")
            ),
        ).applied()

        return LifecycleFixture(activeCampaign, activeBatch)
    }

    fun reserve(fixture: LifecycleFixture, user: String, key: String): ReserveVoucherCommand =
        ReserveVoucherCommand(
            tenantId = tenant,
            campaignId = fixture.campaign.campaignId,
            canonicalUser = user,
            idempotencyKey = this.key(key)
        )

    fun allocate(reservation: ReservationSnapshot, user: String, key: String): AllocateVoucherCommand =
        AllocateVoucherCommand(
            tenantId = tenant,
            campaignId = reservation.campaignId,
            reservationId = reservation.reservationId,
            canonicalUser = user,
            expectedRevision = reservation.revision,
            idempotencyKey = this.key(key),
        )

    fun reveal(allocation: AllocationSnapshot, user: String, key: String): RevealVoucherCommand =
        RevealVoucherCommand(
            tenantId = tenant,
            campaignId = allocation.campaignId,
            allocationId = allocation.allocationId,
            canonicalUser = user,
            expectedRevision = allocation.revision,
            idempotencyKey = this.key(key),
        )

    fun entryState(entryId: UUID): EntryState =
        query(
            sql = "SELECT state FROM voucher_pool_entries " +
                    "WHERE tenant_id=? AND entry_id=?",
            id = entryId
        ) {
            EntryState.valueOf(it.getString(1))
        }

    fun hasVerificationDigest(entryId: UUID): Boolean =
        query(
            sql = "SELECT verification_digest IS NOT NULL FROM voucher_pool_entries " +
                    "WHERE tenant_id=? AND entry_id=?",
            id = entryId,
        ) {
            it.getBoolean(1)
        }

    fun hasCiphertext(entryId: UUID): Boolean =
        query(
            sql = "SELECT code_ciphertext IS NOT NULL FROM voucher_pool_entries " +
                    "WHERE tenant_id=? AND entry_id=?",
            id = entryId,
        ) {
            it.getBoolean(1)
        }

    fun consumedEntries(): Long =
        scalar("SELECT count(*) FROM voucher_pool_entries WHERE tenant_id=? AND state<>'AVAILABLE'")

    fun lifetimeConsumed(campaignId: UUID, user: String): Int {
        val digest = digests.userIdentity(tenant, campaignId, user)

        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT lifetime_consumed FROM voucher_pool_user_limits " +
                        "WHERE tenant_id=? AND campaign_id=? AND user_digest=?"
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setObject(2, campaignId)
                statement.setBytes(3, digest.copyBytes())
                statement.executeQuery().use { result ->
                    result.singleInt()
                }
            }
        }
    }

    fun userCounts(campaignId: UUID, user: String): UserCounts {
        val digest = digests.userIdentity(tenant, campaignId, user)

        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT active_reservations,active_allocations,lifetime_consumed " +
                        "FROM voucher_pool_user_limits " +
                        "WHERE tenant_id=? AND campaign_id=? AND user_digest=?",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setObject(2, campaignId)
                statement.setBytes(3, digest.copyBytes())
                statement.executeQuery().use { result ->
                    result.next()
                    UserCounts(
                        activeReservations = result.getInt(1),
                        activeAllocations = result.getInt(2),
                        lifetimeConsumed = result.getInt(3)
                    )
                }
            }
        }
    }

    inline fun withEntryLocked(block: () -> Unit) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            connection.prepareStatement(
                "SELECT entry_id FROM voucher_pool_entries " +
                        "WHERE tenant_id=? AND state='AVAILABLE' " +
                        "ORDER BY source_ordinal LIMIT 1 FOR UPDATE",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.executeQuery().use { result ->
                    check(result.next())
                }
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
            """CREATE FUNCTION fail_lifecycle_finalize() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'simulated finalize crash' USING ERRCODE='40001'; END IF; RETURN NEW; END $$;
                CREATE TRIGGER fail_lifecycle_finalize BEFORE UPDATE ON voucher_pool_http_idempotency
                FOR EACH ROW EXECUTE FUNCTION fail_lifecycle_finalize()""".trimIndent(),
        )
    }

    fun dropFinalizeFailureTrigger() {
        execute(
            "DROP TRIGGER IF EXISTS fail_lifecycle_finalize ON voucher_pool_http_idempotency; " +
                    "DROP FUNCTION IF EXISTS fail_lifecycle_finalize()",
        )
    }

    fun reservationCount(): Long =
        scalar("SELECT count(*) FROM voucher_pool_reservations WHERE tenant_id=?")

    fun allocationCount(): Long =
        scalar("SELECT count(*) FROM voucher_pool_allocations WHERE tenant_id=?")

    fun completedIdempotencyCount(operation: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM voucher_pool_http_idempotency " +
                        "WHERE tenant_id=? AND operation=? AND status='COMPLETED' AND descriptor IS NOT NULL",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setString(2, operation)
                statement.executeQuery().use { result ->
                    result.singleLong()
                }
            }
        }

    fun tombstoneCount(operation: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM voucher_pool_command_tombstones WHERE tenant_id=? AND operation=?",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setString(2, operation)

                statement.executeQuery().use { result ->
                    result.singleLong()
                }
            }
        }

    fun installReservationGuardProbe(parties: Int): ReservationGuardProbe =
        ReservationGuardProbe(parties).also { probe ->
            rebuildServices { repository ->
                GuardProbeVoucherPoolRepository(repository, probe)
            }
        }

    fun sharedGuardHolders(probe: ReservationGuardProbe, relation: String): Int =
        dataSource.sharedLockHolders(probe.backendPids.toSet(), relation)

    fun guardWaiters(probe: ReservationGuardProbe): Int =
        dataSource.lockWaiters(probe.backendPids.toSet())

    fun terminalAuditCount(allocationId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_audits " +
                    "WHERE tenant_id=? AND aggregate_type='ALLOCATION' AND aggregate_id=? AND reason_code IN ('REDEEMED','OPERATOR_REVOKED')",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, allocationId)

            statement.executeQuery().use { result ->
                result.singleLong()
            }
        }
    }

    fun revokeRaceLostAuditCount(allocationId: UUID): Long = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT count(*) FROM voucher_pool_audits " +
                    "WHERE tenant_id=? AND aggregate_type='ALLOCATION' AND aggregate_id=? AND reason_code='REVOKE_RACE_LOST'",
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, allocationId)

            statement.executeQuery().use { result ->
                result.singleLong()
            }
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
                    WHERE tenant_id=? AND campaign_id=? AND user_digest=?""".trimIndent(),
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

    fun auditCount(aggregateType: String, aggregateId: UUID, reasonCode: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM voucher_pool_audits WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=? AND reason_code=?",
            ).use { statement ->
                statement.setString(1, tenant)
                statement.setString(2, aggregateType)
                statement.setObject(3, aggregateId)
                statement.setString(4, reasonCode)

                statement.executeQuery().use { result ->
                    result.singleLong()
                }
            }
        }

    fun reconciliationAuditCounts(batchId: UUID): Pair<Long, Long> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """SELECT before_count,after_count FROM voucher_pool_audits
                WHERE tenant_id=? AND aggregate_type='RECONCILIATION' AND aggregate_id=?
                ORDER BY revision DESC LIMIT 1""".trimIndent(),
        ).use { statement ->
            statement.setString(1, tenant)
            statement.setObject(2, batchId)

            statement.executeQuery().use { result ->
                result.next()
                result.getLong(1) to result.getLong(2)
            }
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

            statement.executeQuery().use { result ->
                if (result.next()) result.getLong(1) else 0L
            }
        }
    }

    fun pauseBatch(batchId: UUID) {
        execute(
            "UPDATE voucher_pool_batches SET state='PAUSED',revision=revision+1 " +
                    "WHERE tenant_id='$tenant' AND batch_id='$batchId'",
        )
    }

    private inline fun rebuildServices(
        repositoryTransform: (VoucherPoolRepository) -> VoucherPoolRepository = { it },
    ) {
        val repository = repositoryTransform(JdbcVoucherPoolRepository())
        val executor = VoucherPoolJdbcExecutor(
            gate = DatabasePermitGate.default(32),
            transactionManager = SpringTransactionManager(dataSource, DatabaseConfig {}, false)
        )
        voucherRepository = repository
        jdbcExecutor = executor
        val idempotency = JdbcVoucherPoolIdempotencyRepository(digests)

        val crypto = AesGcmVoucherEnvelopeCrypto(
            kekRing = VoucherKekRing(VoucherKek("test-kek", keyBytes(11))),
            digests = digests
        )
        commandService = JdbcCampaignBatchCommandService(
            executor = executor,
            repository = repository,
            idempotency = idempotency,
            digests = digests,
            crypto = crypto
        )
        reservations = JdbcReservationService(
            executor = executor,
            repository = repository,
            idempotency = idempotency,
            digests = digests
        )
        allocations = JdbcAllocationService(
            executor = executor,
            repository = repository,
            idempotency = idempotency,
            digests = digests,
            cryptoStorage = VoucherCryptoStorage(repository, crypto)
        )
        redemptions = JdbcRedemptionService(
            executor = executor,
            repository = repository,
            idempotency = idempotency,
            digests = digests
        )
    }

    private fun digest(value: String): DigestValue =
        DigestValue(MessageDigest.getInstance("SHA-256").digest(value.toUtf8Bytes()))

    private fun key(value: String) = "voucher-lifecycle-$suffix-$value"

    private fun keyBytes(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun digestService(): VoucherDigestService =
        VoucherDigestService(
            stableDedupKey = DigestKey(7, keyBytes(7)),
            commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
            rotatingKeys = mapOf(
                DigestPurpose.VERIFICATION to DigestKeyRing.of(DigestKey.of(1, keyBytes(1))),
                DigestPurpose.USER_IDENTITY to DigestKeyRing.of(DigestKey.of(2, keyBytes(2))),
                DigestPurpose.REDIS_SIGNAL to DigestKeyRing.of(DigestKey.of(3, keyBytes(3))),
                DigestPurpose.AUDIT to DigestKeyRing.of(DigestKey.of(5, keyBytes(5))),
            ),
        )

    private inline fun <T> query(sql: String, id: UUID, mapper: (java.sql.ResultSet) -> T): T =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, tenant); statement.setObject(2, id)
                statement.executeQuery().use { result ->
                    result.next()
                    mapper(result)
                }
            }
        }

    private fun scalar(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, tenant)
                statement.executeQuery().use { result ->
                    result.singleLong()
                }
            }
        }

    private fun execute(sql: String) {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(sql)
            }
        }
    }

    private fun adminConnection(): Connection =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username ?: PostgreSQLServer.USERNAME,
            postgres.password ?: PostgreSQLServer.PASSWORD,
        )
}

internal data class LifecycleFixture(
    val campaign: CampaignSnapshot,
    val batch: BatchSnapshot,
)

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
        release.await(5.seconds).checkBeTrue { "reservation guard probe release timed out" }
    }

    fun awaitEntered(): Boolean = entered.await(2.seconds)

    fun release() = release.countDown()
}

private class GuardProbeVoucherPoolRepository(
    private val delegate: VoucherPoolRepository,
    private val probe: ReservationGuardProbe,
): VoucherPoolRepository by delegate {
    override fun lockReservationGuards(
        tenantId: String,
        campaignId: UUID,
        userDigest: ByteArray,
    ): ReservationGuards? =
        delegate.lockReservationGuards(tenantId, campaignId, userDigest)
            ?.also {
                probe.enter(currentConnection())
            }
}

internal fun <T> MutationResult<T>.applied(): T =
    (this as MutationResult.Applied<T>).value
