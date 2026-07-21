@file:Suppress("MaxLineLength")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class VoucherPoolConcurrencyIntegrationTest {
    private val harness = LifecycleHarness("concurrency")

    @BeforeAll fun migrate() = harness.migrate()
    @AfterAll fun cleanup() {
        harness.cleanup()
    }
    @BeforeEach fun reset() = harness.reset()

    @Test
    fun `concurrent reservations select distinct entries`() {
        val fixture = harness.activePool("distinct", List(64) { "CONCURRENT-$it" })
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = List(64) { index ->
                executor.submit(Callable {
                    reserveWithAdmissionRetry {
                        harness.reservations.reserve(harness.reserve(fixture, "user-$index", "reserve-$index")).applied()
                    }
                })
            }
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            results.map { it.entryId }.distinct().size shouldBeEqualTo 64
        }
    }

    @Test
    fun `same user concurrent reservations are serialized by user limit`() {
        val fixture = harness.activePool("user-limit", List(8) { "LIMIT-$it" })
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = List(8) { index ->
                executor.submit(Callable {
                    runCatching {
                        harness.reservations.reserve(harness.reserve(fixture, "same-user", "same-$index")).applied()
                    }
                })
            }
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            outcomes.count { it.isSuccess } shouldBeEqualTo 4
            outcomes.filter { it.isFailure }.forEach { outcome ->
                val failure = outcome.exceptionOrNull() as VoucherPoolLifecycleException
                failure.code shouldBeEqualTo VoucherPoolErrorCode.USER_LIMIT_REACHED
            }
            harness.userCounts(fixture.campaign.campaignId, "same-user") shouldBeEqualTo UserCounts(4, 0, 0)
        }
    }

    @Test
    fun `same campaign reservations retain concurrent campaign and batch shared progress`() {
        val fixture = harness.activePool("shared-progress", List(4) { "SHARED-$it" })
        val probe = harness.installReservationGuardProbe(4)
        val startedAt = System.nanoTime()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val results = List(4) { index ->
                executor.submit(Callable<ReservationSnapshot> {
                    harness.reservations.reserve(harness.reserve(fixture, "shared-user-$index", "shared-$index")).applied()
                })
            }
            probe.awaitEntered() shouldBeEqualTo true
            harness.sharedGuardHolders(probe, "voucher_pool_campaigns") shouldBeEqualTo 4
            harness.sharedGuardHolders(probe, "voucher_pool_batches") shouldBeEqualTo 4
            harness.guardWaiters(probe) shouldBeEqualTo 0
            probe.release()
            val reservations = results.map { it.get(2, TimeUnit.SECONDS) }
            reservations.map { it.entryId }.distinct().size shouldBeEqualTo 4
        }
        (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) <= 2_000) shouldBeEqualTo true
    }

    @Test
    fun `opposing reserve and release transitions keep depth exact without deadlock`() {
        val fixture = harness.activePool("opposing-depth", List(16) { "OPPOSING-$it" })
        val existing = List(8) { index ->
            harness.reservations.reserve(harness.reserve(fixture, "release-user-$index", "initial-$index")).applied()
        }

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val releases = existing.mapIndexed { index, reservation ->
                executor.submit {
                    reserveWithAdmissionRetry {
                        harness.reservations.release(
                            ReleaseReservationCommand(
                                harness.tenant,
                                fixture.campaign.campaignId,
                                reservation.reservationId,
                                "release-user-$index",
                                reservation.revision,
                                "opposing-release-$index",
                            ),
                        ).applied()
                    }
                }
            }
            val reserves = List(8) { index ->
                executor.submit {
                    reserveWithAdmissionRetry {
                        harness.reservations.reserve(
                            harness.reserve(fixture, "new-user-$index", "opposing-reserve-$index"),
                        ).applied()
                    }
                }
            }
            (releases + reserves).forEach { it.get(20, TimeUnit.SECONDS) }
        }

        harness.poolDepth(fixture.batch.batchId, EntryState.AVAILABLE) shouldBeEqualTo 8L
        harness.poolDepth(fixture.batch.batchId, EntryState.RESERVED) shouldBeEqualTo 8L
    }

    @Test
    fun `redeem and revoke race has exactly one terminal winner`() {
        val fixture = harness.activePool("terminal-race", listOf("TERMINAL-RACE"))
        val reservation = harness.reservations.reserve(harness.reserve(fixture, "racer", "race-reserve")).applied()
        val allocation = harness.allocations.allocate(harness.allocate(reservation, "racer", "race-allocate")).applied()
        val revealed = harness.allocations.reveal(harness.reveal(allocation, "racer", "race-reveal")).applied()
        val barrier = CyclicBarrier(2)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val outcomes = listOf(
                executor.submit(Callable {
                    barrier.await()
                    runCatching {
                        harness.redemptions.redeem(
                            RedeemVoucherCommand(
                                harness.tenant,
                                fixture.campaign.campaignId,
                                allocation.allocationId,
                                "racer",
                                CanonicalVoucherCode.of("TERMINAL-RACE"),
                                revealed.revision,
                                "race-redeem-key",
                            ),
                        ).applied()
                    }
                }),
                executor.submit(Callable {
                    barrier.await()
                    runCatching {
                        harness.redemptions.revoke(
                            RevokeAllocationCommand(
                                harness.tenant,
                                allocation.allocationId,
                                revealed.revision,
                                "race-revoke-key",
                            ),
                        ).applied()
                    }
                }),
            ).map { it.get(10, TimeUnit.SECONDS) }

            outcomes.count { it.isSuccess } shouldBeEqualTo 1
            val loser = outcomes.single { it.isFailure }.exceptionOrNull() as VoucherPoolLifecycleException
            loser.code shouldBeEqualTo VoucherPoolErrorCode.STALE_REVISION
            val terminalState = harness.entryState(allocation.entryId)
            (terminalState in setOf(EntryState.REDEEMED, EntryState.REVOKED)) shouldBeEqualTo true
            harness.userCounts(fixture.campaign.campaignId, "racer") shouldBeEqualTo UserCounts(0, 0, 1)
            harness.terminalAuditCount(allocation.allocationId) shouldBeEqualTo 1L
            harness.revokeRaceLostAuditCount(allocation.allocationId) shouldBeEqualTo
                if (terminalState == EntryState.REDEEMED) 1L else 0L
        }
    }

    private fun reserveWithAdmissionRetry(block: () -> ReservationSnapshot): ReservationSnapshot {
        repeat(50) {
            try {
                return block()
            } catch (_: VoucherPoolJdbcTimeoutException) {
                Thread.sleep(20)
            } catch (failure: VoucherPoolLifecycleException) {
                if (failure.code !in setOf(VoucherPoolErrorCode.POOL_BUSY, VoucherPoolErrorCode.COMMAND_IN_PROGRESS)) {
                    throw failure
                }
                Thread.sleep(20)
            }
        }
        return block()
    }
}
