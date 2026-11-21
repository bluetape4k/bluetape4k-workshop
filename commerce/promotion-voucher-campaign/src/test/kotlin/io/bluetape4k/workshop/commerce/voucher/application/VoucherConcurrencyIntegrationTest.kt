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

/**
 * VoucherConcurrencyIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherConcurrencyIntegrationTest: VoucherCommandTestSupport() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "capacity storm has exactly N winners and never oversells" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "pause and allocation race has one authoritative winner without lock timeout" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
        val contributingClaims = contributingClaimCount(CAMPAIGN_ID)
        campaign.allocatedCount.toLong() shouldBeEqualTo contributingClaims
    }

    @Test
/**
 * 이 시나리오는 "redeem and revoke race has one authoritative winner and one revision audit" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "release and expiry race completes without lock timeout or double decrement" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "redemption review and expiry race follows campaign claim review lock order" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "campaign lock convoy times out near five seconds and releases its only permit" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

/**
 * race은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
