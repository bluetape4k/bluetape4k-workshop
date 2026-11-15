@file:Suppress("SqlResolve")

package io.bluetape4k.workshop.commerce.voucher.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeIn
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.javatimes.nanos
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

internal class VoucherConcurrencyIntegrationTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
    fun `capacity storm has exactly N winners and never oversells`() {
        createCampaign(capacity = 3, perUserLimit = 1)

        val outcomes = race(
            List(12) { index ->
                {
                    allocation.allocate(
                        AllocateVoucherCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            userRef = "user-$index"
                        )
                    )
                }
            }
        )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 3
        campaignSnapshot().allocatedCount shouldBeEqualTo 3
    }

    @Test
    fun `pause and allocation race has one authoritative winner without lock timeout`() {
        createCampaign(capacity = 1)

        val outcomes = race(
            listOf(
                {
                    campaignCommands.pause(
                        CampaignTransitionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            expectedRevision = 0
                        )
                    )
                },
                {
                    allocation.allocate(
                        AllocateVoucherCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            userRef = "user-1"
                        )
                    )
                },
            )
        )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure).all { it.exceptionOrNull() is VoucherCommandException }.shouldBeTrue()

        val campaign = campaignSnapshot()
        val contributingClaims = queryLong(
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
        val allocateCommand = AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1")
        val allocated = allocation.allocate(allocateCommand)

        val outcomes = race(
            listOf(
                {
                    claimCommands.redeem(
                        RedeemVoucherCommand(
                            tenantId = TENANT_ID,
                            code = checkNotNull(allocated.oneTimeCode),
                            expectedRevision = 0,
                            redemptionReference = "order-1",
                        ),
                    )
                },
                {
                    claimCommands.revoke(
                        ClaimTransitionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            claimId = allocated.claim.claimId,
                            expectedRevision = 0
                        ),
                    )
                },
            )
        )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure)
            .all { it.exceptionOrNull() is VoucherCommandException }.shouldBeTrue()

        jdbc.foregroundTransaction {
            audits.findAggregate(TENANT_ID, allocated.claim.claimId)
        }.count { it.revision == 1L } shouldBeEqualTo 1

        campaignSnapshot().allocatedCount.let { it == 0 || it == 1 }.shouldBeTrue()
    }

    @Test
    fun `release and expiry race completes without lock timeout or double decrement`() {
        createCampaign()

        val allocateCommand = AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1")
        val allocated = allocation.allocate(allocateCommand)
        configureCommandRuntime(serviceClock = Clock.fixed(NOW + 3_601.seconds(), ZoneOffset.UTC))

        val outcomes = race(
            listOf(
                {
                    claimCommands.release(
                        ClaimTransitionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            claimId = allocated.claim.claimId,
                            expectedRevision = 0
                        )
                    )
                },
                {
                    claimCommands.expire(
                        ClaimTransitionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            claimId = allocated.claim.claimId,
                            expectedRevision = 0
                        )
                    )
                },
            )
        )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure)
            .all { it.exceptionOrNull() is VoucherCommandException }.shouldBeTrue()

        campaignSnapshot().allocatedCount shouldBeEqualTo 0
        jdbc.foregroundTransaction {
            audits.findAggregate(TENANT_ID, allocated.claim.claimId)
        }.count { it.revision == 1L } shouldBeEqualTo 1
    }

    @Test
    fun `redemption review and expiry race follows campaign claim review lock order`() {
        createCampaign()
        val allocateCommand = AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "user-1")
        val allocated = allocation.allocate(allocateCommand)
        val pending = claimCommands.redeem(
            RedeemVoucherCommand(
                tenantId = TENANT_ID,
                code = checkNotNull(allocated.oneTimeCode),
                expectedRevision = 0,
                redemptionReference = "order-1",
                riskSignal = RiskSignal.REVIEW,
            ),
        )
        val review = jdbc.foregroundTransaction {
            reviews.findOpen(TENANT_ID, pending.claimId)
        }.shouldNotBeNull()

        configureCommandRuntime(serviceClock = Clock.fixed(NOW + 3_601.seconds(), ZoneOffset.UTC))

        val outcomes = race(
            listOf(
                {
                    reviewCommands.approve(
                        ReviewDecisionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            claimId = pending.claimId,
                            reviewId = review.id,
                            expectedReviewRevision = 0,
                            expectedClaimRevision = pending.revision
                        ),
                    )
                },
                {
                    claimCommands.expire(
                        ClaimTransitionCommand(
                            tenantId = TENANT_ID,
                            campaignId = CAMPAIGN_ID,
                            claimId = pending.claimId,
                            expectedRevision = pending.revision
                        )
                    )
                }
            ),
        )

        outcomes.count(Result<Any>::isSuccess) shouldBeEqualTo 1
        outcomes.filter(Result<Any>::isFailure)
            .all { it.exceptionOrNull() is VoucherCommandException }.shouldBeTrue()

        campaignSnapshot().allocatedCount shouldBeEqualTo 0

        jdbc.foregroundTransaction {
            claims.findPublic(TENANT_ID, pending.claimId).shouldNotBeNull()
        }.state.name shouldBeEqualTo "EXPIRED"
    }

    @Test
    fun `campaign lock convoy times out near five seconds and releases its only permit`() {
        configureCommandRuntime(foregroundPermits = 1)
        createCampaign(capacity = 2)

        dataSource.connection.use { blocker ->
            blocker.autoCommit = false
            val sql = """
                SELECT campaign_id
                  FROM voucher_campaigns
                 WHERE tenant_id = ? AND campaign_id = ?
                   FOR UPDATE
                """.trimIndent()
            blocker.prepareStatement(sql).use { statement ->
                statement.setString(1, TENANT_ID)
                statement.setObject(2, CAMPAIGN_ID)
                statement.executeQuery().use { result ->
                    result.next().shouldBeTrue()
                }
            }

            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val startedAt = System.nanoTime()
                val failure = executor.submit<Throwable?> {
                    runCatching {
                        allocation.allocate(
                            AllocateVoucherCommand(
                                tenantId = TENANT_ID,
                                campaignId = CAMPAIGN_ID,
                                userRef = "blocked-user"
                            )
                        )
                    }.exceptionOrNull()
                }.get(8.seconds)

                val elapsedMillis = (System.nanoTime() - startedAt).nanos().toMillis()

                failure.shouldNotBeNull()
                elapsedMillis shouldBeIn 4_500L..7_500L
            }
            blocker.rollback()
        }

        allocation.allocate(
            AllocateVoucherCommand(
                tenantId = TENANT_ID,
                campaignId = CAMPAIGN_ID,
                userRef = "recovery-user"
            )
        )
        campaignSnapshot().allocatedCount shouldBeEqualTo 1
    }

    private fun race(actions: List<() -> Any>): List<Result<Any>> {
        val ready = CountDownLatch(actions.size)
        val start = CountDownLatch(1)

        return Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures = actions.map { action ->
                executor.submit<Result<Any>> {
                    ready.countDown()
                    start.await(2.seconds).shouldBeTrue()
                    runCatching(action)
                }
            }
            ready.await(2.seconds).shouldBeTrue()
            start.countDown()
            futures.map { it.get(10.seconds) }
        }
    }
}
