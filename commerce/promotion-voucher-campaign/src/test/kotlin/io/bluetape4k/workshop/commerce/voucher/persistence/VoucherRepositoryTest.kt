package io.bluetape4k.workshop.commerce.voucher.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.admission.DatabaseLane
import io.bluetape4k.workshop.commerce.voucher.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimState
import io.bluetape4k.workshop.commerce.voucher.domain.ReviewKind
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * VoucherRepositoryTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherRepositoryTest {

    companion object: KLogging() {
/**
 * NOW은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-19T00:00:00Z")
/**
 * CAMPAIGN_ID은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CAMPAIGN_ID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab")
/**
 * CLAIM_ID은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CLAIM_ID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bc")
/**
 * CLAIM_ID_2은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CLAIM_ID_2 = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890bd")
/**
 * CLAIM_ID_3은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CLAIM_ID_3 = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890be")
/**
 * ALLOCATION_ID은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val ALLOCATION_ID = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd")
    }

/**
 * gate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val gate = DatabasePermitGate(
        foregroundPermits = 1,
        workerPermits = 1,
        sseMaintenancePermits = 1,
        acquireTimeout = 1.seconds(),
    )

    @Test
/**
 * 이 시나리오는 "repository access without a database permit fails fast" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `repository access without a database permit fails fast`() {
        withTables(TestDB.POSTGRESQL, CampaignTable) {
            val repository = CampaignRepository(gate)

            assertFailsWith<IllegalStateException> {
                repository.findPublic("tenant-a", CAMPAIGN_ID)
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "capacity CAS never exceeds campaign capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `capacity CAS never exceeds campaign capacity`() {
        withTables(TestDB.POSTGRESQL, *voucherTables) {
            withPermit {
                val repository = CampaignRepository(gate)
                val campaign = repository.create(activeCampaign(capacity = 1))

                repository.tryReserve(campaign.tenantId, campaign.id, expectedRevision = 0).shouldBeTrue()
                repository.tryReserve(campaign.tenantId, campaign.id, expectedRevision = 0).shouldBeFalse()

                val persisted = repository.findById(campaign.id)
                persisted.allocatedCount shouldBeEqualTo 1
                persisted.revision shouldBeEqualTo 1L
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "cross tenant claim lookup is indistinguishable from missing" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cross tenant claim lookup is indistinguishable from missing`() {
        withTables(TestDB.POSTGRESQL, *voucherTables) {
            withPermit {
                val campaigns = CampaignRepository(gate)
                val campaign = campaigns.create(activeCampaign())
                val claims = ClaimRepository(gate)
                val claim = claims.insert(allocatedClaim(campaign.id, tenantId = "tenant-a"))

                claims.findPublic("tenant-b", claim.claimId).shouldBeNull()
                claims.findPublic("tenant-a", claim.claimId)?.id shouldBeEqualTo claim.id
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "claim storage contains only verifier and key versions" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `claim storage contains only verifier and key versions`() {
        withTables(TestDB.POSTGRESQL, *voucherTables) {
            withPermit {
                val campaign = CampaignRepository(gate).create(activeCampaign())
                val claims = ClaimRepository(gate)
                val record = claims.insert(allocatedClaim(campaign.id))
                val columns = ClaimTable.columns.map { it.name }.toSet()

                columns.containsAll(
                    setOf("code_verifier", "generation_key_version", "verification_key_version"),
                ).shouldBeTrue()

                columns.any { it in setOf("code", "token_material", "generation_digest") }.shouldBeFalse()
                claims.findByVerifier(record.tenantId, record.codeVerifier)?.claimId shouldBeEqualTo record.claimId
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "tenant scoped verifier uniqueness allows the same digest in another tenant" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant scoped verifier uniqueness allows the same digest in another tenant`() {
        withTables(TestDB.POSTGRESQL, *voucherTables) {
            withPermit {
                val campaigns = CampaignRepository(gate)
                val tenantA = campaigns.create(activeCampaign(tenantId = "tenant-a", campaignId = CAMPAIGN_ID))
                val tenantB = campaigns.create(activeCampaign(tenantId = "tenant-b", campaignId = CAMPAIGN_ID))
                val claims = ClaimRepository(gate)

                claims.insert(allocatedClaim(tenantA.id, tenantId = "tenant-a"))
                claims.insert(allocatedClaim(tenantB.id, tenantId = "tenant-b", claimId = CLAIM_ID_2))

                assertFailsWith<Exception> {
                    claims.insert(allocatedClaim(tenantA.id, tenantId = "tenant-a", claimId = CLAIM_ID_3))
                }
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "database check rejects allocated count above capacity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `database check rejects allocated count above capacity`() {
        withTables(TestDB.POSTGRESQL, CampaignTable) {
            withPermit {
                assertFailsWith<Exception> {
                    CampaignTable.insert {
                        it[tenantId] = "tenant-a"
                        it[campaignId] = CAMPAIGN_ID
                        it[state] = CampaignState.ACTIVE
                        it[startsAt] = NOW.minusSeconds(60)
                        it[endsAt] = NOW.plusSeconds(3600)
                        it[capacity] = 1
                        it[allocatedCount] = 2
                        it[perUserLimit] = 1
                        it[redemptionTtlSeconds] = 3600
                        it[policyVersion] = 1
                        it[revision] = 0
                    }
                }
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "review audit and inbox repositories preserve tenant scoped invariants" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `review audit and inbox repositories preserve tenant scoped invariants`() {
        withTables(TestDB.POSTGRESQL, *voucherTables) {
            withPermit {
                val campaign = CampaignRepository(gate).create(activeCampaign())
                val claim = ClaimRepository(gate).insert(allocatedClaim(campaign.id))
                val reviews = ReviewRepository(gate)
                val review = reviews.insert(
                    ReviewRecord(
                        id = 0,
                        tenantId = "tenant-a",
                        campaignId = CAMPAIGN_ID,
                        claimRowId = claim.id,
                        claimId = claim.claimId,
                        kind = ReviewKind.REDEMPTION,
                        status = ReviewStatus.OPEN,
                        reasonCode = "RISK_REVIEW",
                        signalSummary = "bounded-summary",
                        reviewerActorDigest = null,
                        expectedClaimRevision = claim.revision,
                        revision = 0,
                    ),
                )
                reviews.findOpen("tenant-a", claim.claimId)?.id shouldBeEqualTo review.id
                reviews.findOpen("tenant-b", claim.claimId).shouldBeNull()

                val audits = AuditRepository(gate)
                val audit = AuditRecord(
                    id = 0,
                    tenantId = "tenant-a",
                    campaignId = CAMPAIGN_ID,
                    aggregateType = "CLAIM",
                    aggregateId = claim.claimId,
                    revision = 1,
                    actorType = "USER",
                    reasonCode = "ALLOCATED",
                    policyVersion = 1,
                    correlationDigest = "c".repeat(64),
                )
                audits.append(audit)

                val inbox = EventInboxRepository(gate)
                val eventId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ef")
                inbox.insert(
                    EventInboxRecord(
                        id = 0,
                        tenantId = "tenant-a",
                        eventId = eventId,
                        aggregateType = "CAMPAIGN",
                        aggregateId = CAMPAIGN_ID,
                        payloadDigest = "p".repeat(64),
                        observedSequence = 1,
                        status = InboxStatus.PENDING,
                        attempt = 0,
                        nextAttemptAt = NOW,
                        claimOwner = null,
                        claimUntil = null,
                    ),
                )
                inbox.findEvent("tenant-b", eventId).shouldBeNull()
                inbox.findEvent("tenant-a", eventId)?.eventId shouldBeEqualTo eventId

                assertFailsWith<Exception> {
                    audits.append(audit)
                }
            }
        }
    }

/**
 * activeCampaign은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun activeCampaign(
        capacity: Int = 10,
        tenantId: String = "tenant-a",
        campaignId: UUID = CAMPAIGN_ID,
    ): CampaignRecord =
        CampaignRecord(
            id = 0,
            tenantId = tenantId,
            campaignId = campaignId,
            state = CampaignState.ACTIVE,
            startsAt = NOW.minusSeconds(60),
            endsAt = NOW.plusSeconds(3600),
            capacity = capacity,
            allocatedCount = 0,
            perUserLimit = 1,
            redemptionTtlSeconds = 3600,
            policyVersion = 1,
            revision = 0,
        )

/**
 * allocatedClaim은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun allocatedClaim(
        campaignRowId: Long,
        tenantId: String = "tenant-a",
        claimId: UUID = CLAIM_ID,
    ): ClaimRecord =
        ClaimRecord(
            id = 0,
            tenantId = tenantId,
            campaignRowId = campaignRowId,
            campaignId = CAMPAIGN_ID,
            claimId = claimId,
            allocationId = ALLOCATION_ID,
            userDigest = "u".repeat(64),
            state = ClaimState.ALLOCATED,
            reviewKind = null,
            pendingFromState = null,
            capacityReserved = true,
            allocationPolicyVersion = 1,
            codeVerifier = ByteArray(32) { 0x2a },
            generationKeyVersion = 5,
            verificationKeyVersion = 7,
            expiresAt = NOW.plusSeconds(3600),
            redemptionReferenceDigest = null,
            revision = 0,
        )

/**
 * withPermit은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun <T> withPermit(block: () -> T): T =
        gate.withPermit(DatabaseLane.FOREGROUND, block)
}
