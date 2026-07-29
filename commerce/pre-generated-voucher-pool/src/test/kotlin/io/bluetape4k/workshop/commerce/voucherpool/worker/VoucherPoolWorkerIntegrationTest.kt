@file:Suppress("MagicNumber", "MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.worker

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucherpool.application.LifecycleHarness
import io.bluetape4k.workshop.commerce.voucherpool.application.ReplaceLostRevealCommand
import io.bluetape4k.workshop.commerce.voucherpool.application.VoucherPoolLifecycleException
import io.bluetape4k.workshop.commerce.voucherpool.application.UserCounts
import io.bluetape4k.workshop.commerce.voucherpool.application.applied
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.application.RedeemVoucherCommand
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolRuntimeControl
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolWorkerDispatcher
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolWorkerTrigger
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolWorkerIntegrationTest {
    private val harness = LifecycleHarness("workers")
    private lateinit var claims: JdbcVoucherPoolWorkerRepository
    private lateinit var workers: JdbcVoucherPoolWorkers

    @BeforeAll fun migrate() = harness.migrate()

    @AfterAll fun cleanup() = harness.cleanup()

    @BeforeEach
    fun reset() {
        harness.reset()
        claims = JdbcVoucherPoolWorkerRepository(harness.workerExecutor())
        workers = JdbcVoucherPoolWorkers(harness.workerExecutor(), claims, harness.workerRepository())
    }

    @Test
    fun `reservation and allocation expiry apply exact deltas once`() {
        val fixture = harness.activePool("expiry", listOf("RESERVATION-EXPIRY", "ALLOCATION-EXPIRY"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "reservation-user", "reservation-expiry-reserve"),
        ).applied()
        harness.expireReservation(reservation.reservationId)

        runToCompletion(WorkerKind.RESERVATION_EXPIRY, fixture.batch.batchId)

        harness.reservationState(reservation.reservationId) shouldBeEqualTo ReservationState.EXPIRED
        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.AVAILABLE
        harness.userCounts(fixture.campaign.campaignId, "reservation-user") shouldBeEqualTo UserCounts(0, 0, 0)
        harness.auditCount("RESERVATION", reservation.reservationId, "RESERVATION_EXPIRED") shouldBeEqualTo 1L

        val allocatedReservation = harness.reservations.reserve(
            harness.reserve(fixture, "allocation-user", "allocation-expiry-reserve"),
        ).applied()
        val allocation = harness.allocations.allocate(
            harness.allocate(allocatedReservation, "allocation-user", "allocation-expiry-allocate"),
        ).applied()
        harness.expireAllocation(allocation.allocationId)

        runToCompletion(WorkerKind.ALLOCATION_EXPIRY, fixture.batch.batchId)

        harness.entryState(allocation.entryId) shouldBeEqualTo EntryState.EXPIRED
        harness.userCounts(fixture.campaign.campaignId, "allocation-user") shouldBeEqualTo UserCounts(0, 0, 1)
        harness.auditCount("ALLOCATION", allocation.allocationId, "ALLOCATION_EXPIRED") shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.EXPIRED) shouldBeEqualTo 1L
    }

    @Test
    fun `batch revoke terminalizes active entries with exact counters and depth`() {
        val fixture = harness.activePool("batch-revoke", listOf("AVAILABLE", "RESERVED", "ALLOCATED"))
        val reserved = harness.reservations.reserve(harness.reserve(fixture, "reserved-user", "reserved-key")).applied()
        val allocationReservation = harness.reservations.reserve(
            harness.reserve(fixture, "allocated-user", "allocated-reserve"),
        ).applied()
        val allocated = harness.allocations.allocate(
            harness.allocate(allocationReservation, "allocated-user", "allocated-key"),
        ).applied()

        runToCompletion(WorkerKind.BATCH_REVOKE, fixture.batch.batchId)

        harness.entryState(reserved.entryId) shouldBeEqualTo EntryState.REVOKED
        harness.entryState(allocated.entryId) shouldBeEqualTo EntryState.REVOKED
        harness.userCounts(fixture.campaign.campaignId, "reserved-user") shouldBeEqualTo UserCounts(0, 0, 0)
        harness.userCounts(fixture.campaign.campaignId, "allocated-user") shouldBeEqualTo UserCounts(0, 0, 1)
        harness.poolDepth(fixture.batch.batchId, EntryState.REVOKED) shouldBeEqualTo 3L
        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "REVOKED"
    }

    @Test
    fun `batch expiry preserves redeemed entries while expiring remaining capacity`() {
        val fixture = harness.activePool("batch-expiry", listOf("REDEEMED", "ALLOCATED", "EXPIRE-ME"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "redeemer", "expiry-redeem-reserve")).applied()
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "redeemer", "expiry-redeem-allocate")).applied()
        val reveal = harness.allocations.reveal(harness.reveal(allocation, "redeemer", "expiry-reveal")).applied()
        harness.redemptions.redeem(
            RedeemVoucherCommand(
                harness.tenant,
                fixture.campaign.campaignId,
                allocation.allocationId,
                "redeemer",
                CanonicalVoucherCode.of("REDEEMED"),
                reveal.revision,
                "expiry-redeem",
            ),
        ).applied()
        val activeReservation = harness.reservations.reserve(
            harness.reserve(fixture, "allocated-user", "expiry-active-reserve"),
        ).applied()
        val activeAllocation = harness.allocations.allocate(
            harness.allocate(activeReservation, "allocated-user", "expiry-active-allocate"),
        ).applied()
        harness.expireBatch(fixture.batch.batchId)

        runToCompletion(WorkerKind.BATCH_EXPIRY, fixture.batch.batchId)

        harness.entryState(allocation.entryId) shouldBeEqualTo EntryState.REDEEMED
        harness.entryState(activeAllocation.entryId) shouldBeEqualTo EntryState.EXPIRED
        harness.userCounts(fixture.campaign.campaignId, "allocated-user") shouldBeEqualTo UserCounts(0, 0, 1)
        harness.poolDepth(fixture.batch.batchId, EntryState.REDEEMED) shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.EXPIRED) shouldBeEqualTo 2L
        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "EXPIRED"
    }

    @Test
    fun `batch expiry rejects a batch whose database expiry is not due`() {
        val fixture = harness.activePool("batch-not-due", listOf("NOT-DUE"))
        val claim = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_EXPIRY, fixture.batch.batchId, "not-due-owner"),
        )

        assertFailsWith<IllegalStateException> { workers.runChunk(claim) }

        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "ACTIVE"
        claims.release(claim).state shouldBeEqualTo WorkerClaimState.IDLE
    }

    @Test
    fun `released claim resumes at its durable cursor and duplicate terminalization is harmless`() {
        val fixture = harness.activePool("restart", listOf("RESTART-1", "RESTART-2", "RESTART-3"))
        val original = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_REVOKE, fixture.batch.batchId, "owner-a"),
        )

        val first = workers.runChunk(original, requestedLimit = 1)
        first.processed shouldBeEqualTo 1
        first.effects shouldBeEqualTo 1
        val checkpoint = checkNotNull(first.claim)
        claims.release(checkpoint)

        var resumed = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_REVOKE, fixture.batch.batchId, "owner-b"),
        )
        (resumed.cursor > 0L).shouldBeTrue()
        val second = workers.runChunk(resumed, requestedLimit = 1)
        resumed = checkNotNull(second.claim)
        runToCompletion(resumed)

        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "REVOKED"
        harness.poolDepth(fixture.batch.batchId, EntryState.REVOKED) shouldBeEqualTo 3L
        harness.auditCount("BATCH", fixture.batch.batchId, "REVOKED") shouldBeEqualTo 1L

        val duplicate = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_REVOKE, fixture.batch.batchId, "owner-c"),
        )
        workers.runChunk(duplicate).completed.shouldBeTrue()
        harness.auditCount("BATCH", fixture.batch.batchId, "REVOKED") shouldBeEqualTo 1L
    }

    @Test
    fun `postgres dispatcher resumes durable work without Redis`() {
        val fixture = harness.activePool("postgres-dispatch", listOf("DISPATCH-1", "DISPATCH-2", "DISPATCH-3"))
        harness.expireBatch(fixture.batch.batchId)
        val original = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_EXPIRY, fixture.batch.batchId, "departed-owner"),
        )
        val first = workers.runChunk(original, requestedLimit = 1)
        claims.release(checkNotNull(first.claim))
        val dispatcher = VoucherPoolWorkerDispatcher(
            claims,
            VoucherPoolWorkerTrigger(workers, VoucherPoolRuntimeControl()),
            "replacement-owner",
        )

        val outcomes = dispatcher.runOnce()

        outcomes.single().state shouldBeEqualTo WorkerRunState.COMPLETED
        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "EXPIRED"
        harness.poolDepth(fixture.batch.batchId, EntryState.EXPIRED) shouldBeEqualTo 3L
        claims.findRunnable(10).shouldBeEmpty()
    }

    @Test
    fun `cursor wrap around rechecks earlier active rows`() {
        val fixture = harness.activePool("wrap", listOf("WRAP-1", "WRAP-2"))
        harness.expireBatch(fixture.batch.batchId)
        val claim = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_EXPIRY, fixture.batch.batchId, "wrap-owner"),
        )
        val beyondEnd = claims.checkpoint(claim, Long.MAX_VALUE)

        val outcome = workers.runChunk(beyondEnd)

        outcome.effects shouldBeEqualTo 2
        runToCompletion(checkNotNull(outcome.claim))
        harness.batchState(fixture.batch.batchId).name shouldBeEqualTo "EXPIRED"
    }

    @Test
    fun `reconciliation repairs pool depth and user counters from authoritative rows`() {
        val fixture = harness.activePool("reconcile", listOf("RECONCILE-1", "RECONCILE-2"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "reconcile-user", "reconcile-reserve"),
        ).applied()
        harness.allocations.allocate(
            harness.allocate(reservation, "reconcile-user", "reconcile-allocate"),
        ).applied()
        harness.corruptPoolDepth(fixture.batch.batchId, EntryState.AVAILABLE, 9)
        harness.corruptUserCounts(fixture.campaign.campaignId, "reconcile-user", 7, 8, 9)

        runToCompletion(WorkerKind.RECONCILIATION, fixture.batch.batchId)

        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 1L
        harness.poolDepth(fixture.batch.batchId, EntryState.ALLOCATED) shouldBeEqualTo 1L
        harness.userCounts(fixture.campaign.campaignId, "reconcile-user") shouldBeEqualTo UserCounts(0, 1, 1)
        harness.auditCount("RECONCILIATION", fixture.batch.batchId, "RECONCILED") shouldBeEqualTo 1L
        harness.reconciliationAuditCounts(fixture.batch.batchId) shouldBeEqualTo (2L to 0L)

        harness.corruptUserCounts(fixture.campaign.campaignId, "reconcile-user", 3, 4, 5)
        runToCompletion(WorkerKind.RECONCILIATION, fixture.batch.batchId)
        harness.userCounts(fixture.campaign.campaignId, "reconcile-user") shouldBeEqualTo UserCounts(0, 1, 1)
        harness.auditCount("RECONCILIATION", fixture.batch.batchId, "RECONCILED") shouldBeEqualTo 2L
        harness.reconciliationAuditCounts(fixture.batch.batchId) shouldBeEqualTo (1L to 0L)
    }

    @Test
    fun `failed production run releases claim with durable backoff`() {
        val fixture = harness.activePool("runner-failure", listOf("RUNNER-FAILURE"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "runner-user", "runner-reserve"),
        ).applied()
        harness.expireReservation(reservation.reservationId)
        harness.corruptPoolDepth(fixture.batch.batchId, EntryState.RESERVED, 0)

        val outcome = workers.run(
            WorkerRunRequest(
                harness.tenant,
                WorkerKind.RESERVATION_EXPIRY,
                fixture.batch.batchId,
                "runner-owner",
            ),
        )

        outcome.state shouldBeEqualTo WorkerRunState.RETRYABLE
        checkNotNull(outcome.claim).apply {
            owner.shouldBeNull()
            attempt shouldBeEqualTo 1
            nextAction shouldBeEqualTo "RETRY_AFTER_BACKOFF"
        }
        harness.reservationState(reservation.reservationId) shouldBeEqualTo ReservationState.ACTIVE
    }

    @Test
    fun `cooperative cancellation commits one chunk then releases the latest checkpoint`() {
        val fixture = harness.activePool("runner-cancel", listOf("CANCEL-1", "CANCEL-2", "CANCEL-3"))
        var checks = 0

        val cancelled = workers.run(
            WorkerRunRequest(
                harness.tenant,
                WorkerKind.BATCH_REVOKE,
                fixture.batch.batchId,
                "cancel-owner",
                requestedLimit = 1,
            ),
        ) { checks++ == 0 }

        cancelled.state shouldBeEqualTo WorkerRunState.CANCELLED
        cancelled.effects shouldBeEqualTo 1
        checkNotNull(cancelled.claim).apply {
            owner.shouldBeNull()
            (cursor > 0L).shouldBeTrue()
        }
        workers.run(
            WorkerRunRequest(
                harness.tenant,
                WorkerKind.BATCH_REVOKE,
                fixture.batch.batchId,
                "resume-owner",
                requestedLimit = 1,
            ),
        ).state shouldBeEqualTo WorkerRunState.COMPLETED
        harness.poolDepth(fixture.batch.batchId, EntryState.REVOKED) shouldBeEqualTo 3L
    }

    @Test
    fun `campaign revoke fans out through batch claims then reaches terminal state`() {
        val fixture = harness.activePool("campaign-revoke", listOf("CAMPAIGN-1", "CAMPAIGN-2"))

        harness.setCampaignState(fixture.campaign.campaignId, CampaignState.REVOKING)
        workers.run(
            WorkerRunRequest(
                harness.tenant,
                WorkerKind.CAMPAIGN_REVOKE,
                fixture.campaign.campaignId,
                "campaign-coordinator",
                requestedLimit = 1,
            ),
        ).state shouldBeEqualTo WorkerRunState.COMPLETED
        runToCompletion(WorkerKind.BATCH_REVOKE, fixture.batch.batchId)

        harness.campaignState(fixture.campaign.campaignId) shouldBeEqualTo CampaignState.REVOKED
    }

    @Test
    fun `allocation and revoke worker converge without reverse lock deadlock or counter drift`() {
        val fixture = harness.activePool("allocation-race", listOf("ALLOCATION-RACE"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "race-user", "race-reserve"),
        ).applied()
        val allocationCommand = harness.allocate(reservation, "race-user", "race-allocate")
        val claim = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_REVOKE, fixture.batch.batchId, "race-worker"),
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val workerFuture = pool.submit<WorkerChunkOutcome> {
                start.await()
                workers.runChunk(claim, requestedLimit = 1)
            }
            val allocationFuture = pool.submit<Throwable?> {
                start.await()
                runCatching { harness.allocations.allocate(allocationCommand) }.exceptionOrNull()
            }
            start.countDown()

            val firstChunk = workerFuture.get(10, TimeUnit.SECONDS)
            allocationFuture.get(10, TimeUnit.SECONDS)?.let { failure ->
                check(failure is VoucherPoolLifecycleException)
                failure.code shouldBeEqualTo VoucherPoolErrorCode.BATCH_REVOKED
            }
            runToCompletion(checkNotNull(firstChunk.claim))
        } finally {
            pool.shutdownNow()
        }

        harness.entryState(reservation.entryId) shouldBeEqualTo EntryState.REVOKED
        val counts = harness.userCounts(fixture.campaign.campaignId, "race-user")
        counts.activeReservations shouldBeEqualTo 0
        counts.activeAllocations shouldBeEqualTo 0
        (counts.lifetimeConsumed in 0..1).shouldBeTrue()
        harness.poolDepth(fixture.batch.batchId, EntryState.REVOKED) shouldBeEqualTo 1L
    }

    @Test
    fun `lost reveal replacement and revoke worker preserve capacity and lock order`() {
        val fixture = harness.activePool("replacement-race", listOf("LOST", "REPLACEMENT", "SPARE"))
        val reservation = harness.reservations.reserve(
            harness.reserve(fixture, "replacement-user", "replacement-race-reserve"),
        ).applied()
        val allocation = harness.allocations.allocate(
            harness.allocate(reservation, "replacement-user", "replacement-race-allocate"),
        ).applied()
        val reveal = harness.allocations.reveal(
            harness.reveal(allocation, "replacement-user", "replacement-race-reveal"),
        ).applied()
        val replacementCommand = ReplaceLostRevealCommand(
            harness.tenant,
            fixture.campaign.campaignId,
            allocation.allocationId,
            "replacement-user",
            reveal.revision,
            "replacement-race",
        )
        val claim = checkNotNull(
            claims.claim(harness.tenant, WorkerKind.BATCH_REVOKE, fixture.batch.batchId, "replacement-worker"),
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val workerFuture = pool.submit<WorkerChunkOutcome> {
                start.await()
                workers.runChunk(claim, requestedLimit = 1)
            }
            val replacementFuture = pool.submit<Throwable?> {
                start.await()
                runCatching { harness.allocations.replaceLostReveal(replacementCommand) }.exceptionOrNull()
            }
            start.countDown()

            val firstChunk = workerFuture.get(10, TimeUnit.SECONDS)
            replacementFuture.get(10, TimeUnit.SECONDS)?.let { failure ->
                check(failure is VoucherPoolLifecycleException)
                failure.code shouldBeEqualTo VoucherPoolErrorCode.BATCH_REVOKED
            }
            runToCompletion(checkNotNull(firstChunk.claim))
        } finally {
            pool.shutdownNow()
        }

        harness.userCounts(fixture.campaign.campaignId, "replacement-user") shouldBeEqualTo UserCounts(0, 0, 1)
        harness.poolDepth(fixture.batch.batchId, EntryState.REVOKED) shouldBeEqualTo 3L
    }

    private fun runToCompletion(kind: WorkerKind, scopeId: java.util.UUID) {
        runToCompletion(checkNotNull(claims.claim(harness.tenant, kind, scopeId, "test-owner")))
    }

    private fun runToCompletion(initial: WorkerClaim) {
        var claim = initial
        repeat(10) {
            val outcome = workers.runChunk(claim)
            if (outcome.completed) return
            claim = checkNotNull(outcome.claim)
        }
        error("worker did not complete within the bounded test run")
    }
}
