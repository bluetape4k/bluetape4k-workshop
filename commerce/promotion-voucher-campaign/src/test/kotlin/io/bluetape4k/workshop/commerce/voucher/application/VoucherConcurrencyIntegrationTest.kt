package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class VoucherConcurrencyIntegrationTest : VoucherCommandTestSupport() {
    @Test
    fun `capacity storm has exactly N winners and never oversells`() {
        createCampaign(capacity = 3, perUserLimit = 1)
        val outcomes =
            race(
                *(1..12).map { index ->
                    { allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-$index")) }
                }.toTypedArray(),
            )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 3
        campaignSnapshot().allocatedCount shouldBeEqualTo 3
    }

    @Test
    fun `pause and allocation race has one authoritative winner without lock timeout`() {
        createCampaign(capacity = 1)

        val outcomes =
            race(
                { campaignCommands.pause(CampaignTransitionCommand(TENANT_ID, CAMPAIGN_ID, 0)) },
                { allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1")) },
            )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure).all { it.exceptionOrNull() is VoucherCommandException } shouldBeEqualTo true
        val campaign = campaignSnapshot()
        val contributingClaims =
            queryLong(
                """
                SELECT count(*)
                  FROM voucher_claims
                 WHERE capacity_reserved = true
                   AND state IN ('ALLOCATED', 'REVIEW_REQUIRED', 'REDEEMED')
                """.trimIndent(),
            )
        campaign.allocatedCount.toLong() shouldBeEqualTo contributingClaims
    }

    @Test
    fun `redeem and revoke race has one authoritative winner and one revision audit`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        val outcomes =
            race(
                {
                    claimCommands.redeem(
                        RedeemVoucherCommand(
                            TENANT_ID,
                            checkNotNull(allocated.oneTimeCode),
                            0,
                            "order-1",
                        ),
                    )
                },
                {
                    claimCommands.revoke(
                        ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, allocated.claim.claimId, 0),
                    )
                },
            )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure).all { it.exceptionOrNull() is VoucherCommandException } shouldBeEqualTo true
        jdbc.foregroundTransaction { audits.findAggregate(TENANT_ID, allocated.claim.claimId) }
            .count { it.revision == 1L } shouldBeEqualTo 1
        campaignSnapshot().allocatedCount.let { it == 0 || it == 1 } shouldBeEqualTo true
    }

    @Test
    fun `release and expiry race completes without lock timeout or double decrement`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        configureCommandRuntime(serviceClock = Clock.fixed(NOW.plusSeconds(3_601), ZoneOffset.UTC))

        val outcomes =
            race(
                { claimCommands.release(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, allocated.claim.claimId, 0)) },
                { claimCommands.expire(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, allocated.claim.claimId, 0)) },
            )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure).all { it.exceptionOrNull() is VoucherCommandException } shouldBeEqualTo true
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
        jdbc.foregroundTransaction { audits.findAggregate(TENANT_ID, allocated.claim.claimId) }
            .count { it.revision == 1L } shouldBeEqualTo 1
    }

    @Test
    fun `redemption review and expiry race follows campaign claim review lock order`() {
        createCampaign()
        val allocated = allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1"))
        val pending =
            claimCommands.redeem(
                RedeemVoucherCommand(
                    TENANT_ID,
                    checkNotNull(allocated.oneTimeCode),
                    0,
                    "order-1",
                    RiskSignal.REVIEW,
                ),
            )
        val review = jdbc.foregroundTransaction { checkNotNull(reviews.findOpen(TENANT_ID, pending.claimId)) }
        configureCommandRuntime(serviceClock = Clock.fixed(NOW.plusSeconds(3_601), ZoneOffset.UTC))

        val outcomes =
            race(
                {
                    reviewCommands.approve(
                        ReviewDecisionCommand(TENANT_ID, CAMPAIGN_ID, pending.claimId, review.id, 0, pending.revision),
                    )
                },
                { claimCommands.expire(ClaimTransitionCommand(TENANT_ID, CAMPAIGN_ID, pending.claimId, pending.revision)) },
            )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure).all { it.exceptionOrNull() is VoucherCommandException } shouldBeEqualTo true
        campaignSnapshot().allocatedCount shouldBeEqualTo 0
        jdbc.foregroundTransaction { checkNotNull(claims.findPublic(TENANT_ID, pending.claimId)) }.state.name shouldBeEqualTo
            "EXPIRED"
    }

    @Test
    fun `campaign lock convoy times out near five seconds and releases its only permit`() {
        configureCommandRuntime(foregroundPermits = 1)
        createCampaign(capacity = 2)

        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            blocker.prepareStatement(
                """
                SELECT campaign_id
                  FROM voucher_campaigns
                 WHERE tenant_id = ? AND campaign_id = ?
                   FOR UPDATE
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, TENANT_ID)
                statement.setObject(2, CAMPAIGN_ID)
                statement.executeQuery().use { result -> result.next().shouldBeTrue() }
            }

            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val startedAt = System.nanoTime()
                val failure =
                    executor.submit<Throwable?> {
                        runCatching {
                            allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "blocked-user"))
                        }.exceptionOrNull()
                    }.get(8, TimeUnit.SECONDS)
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

                (failure != null).shouldBeTrue()
                (elapsedMillis in 4_500..7_500).shouldBeTrue()
            }
            blocker.rollback()
        }

        allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "recovery-user"))
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    private fun race(vararg actions: () -> Any): List<Result<Any>> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)
        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                actions.map { action ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(2, TimeUnit.SECONDS)) { "race start barrier timed out" }
                            runCatching(action)
                        },
                    )
                }
            check(ready.await(2, TimeUnit.SECONDS)) { "race readiness barrier timed out" }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        }
    }
}
