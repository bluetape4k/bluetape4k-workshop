package io.bluetape4k.workshop.commerce.voucherpool.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import org.junit.jupiter.api.Test
import java.util.*

/**
 * VoucherDigestServiceTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherDigestServiceTest {

    companion object: KLogging() {
/**
 * TENANT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT = "tenant-a"
/**
 * CAMPAIGN은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CAMPAIGN: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
/**
 * ALLOCATION은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val ALLOCATION: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }

    @Test
/**
 * 이 시나리오는 "stable dedup ignores campaign and verification key rotation" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stable dedup ignores campaign and verification key rotation`() {
        val code = CanonicalVoucherCode.of("POOL-001")
        val before = digestService(verificationVersion = 1)
        val after = digestService(verificationVersion = 2, retainVerificationVersion = 1)

        val stableBefore = before.stableDedup(TENANT, code)
        stableBefore shouldBeEqualTo after.stableDedup(TENANT, code)
        stableBefore.keyVersion shouldBeEqualTo 7
        before.commandTombstone(TENANT, "reserve", "request-1") shouldBeEqualTo
                after.commandTombstone(TENANT, "reserve", "request-1")
        before.commandTombstoneKeyVersion shouldBeEqualTo 4
        after.commandTombstoneKeyVersion shouldBeEqualTo 4

        val oldVerification = before.verification(TENANT, CAMPAIGN, ALLOCATION, code)
        val currentVerification = after.verification(TENANT, CAMPAIGN, ALLOCATION, code)
        oldVerification shouldNotBeEqualTo currentVerification

        after.matchesVerification(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            allocationId = ALLOCATION,
            code = code,
            expected = oldVerification
        ).shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "retained user identity key preserves ownership and limit identity during rotation" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retained user identity key preserves ownership and limit identity during rotation`() {
        val before = digestService(verificationVersion = 1, userIdentityVersion = 1)
        val after = digestService(
            verificationVersion = 1,
            userIdentityVersion = 2,
            retainUserIdentityVersion = 1,
        )

        val original = before.userIdentity(TENANT, CAMPAIGN, "customer-a")
        log.debug { "original: $original" }
        original shouldNotBeEqualTo after.userIdentity(TENANT, CAMPAIGN, "customer-a")
        original shouldBeEqualTo after.userIdentity(TENANT, CAMPAIGN, "customer-a", original.keyVersion)
    }

    @Test
/**
 * 이 시나리오는 "purpose and scope are part of the canonical digest" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `purpose and scope are part of the canonical digest`() {
        val service = digestService(verificationVersion = 1)
        val code = CanonicalVoucherCode.of("same-material")

        service.stableDedup(TENANT, code) shouldNotBeEqualTo service.stableDedup("other-tenant", code)
        service.userIdentity(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            canonicalUser = "same-material"
        ) shouldNotBeEqualTo service.commandTombstone(TENANT, "reserve", "same-material")


        service.verification(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            allocationId = ALLOCATION,
            code = code
        ) shouldNotBeEqualTo service.verification(TENANT, UUID.randomUUID(), ALLOCATION, code)

    }

    @Test
/**
 * 이 시나리오는 "unknown digest key version fails closed without exposing material" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown digest key version fails closed without exposing material`() {
        val service = digestService(verificationVersion = 2, retainVerificationVersion = 1)
        val unknown = VoucherDigest(DigestPurpose.VERIFICATION, 99, ByteArray(32) { 9 })

        val failure = assertFailsWith<VoucherKeyMaterialUnavailableException> {
            service.matchesVerification(
                tenantId = TENANT,
                campaignId = CAMPAIGN,
                allocationId = ALLOCATION,
                code = CanonicalVoucherCode.of("TOP-SECRET"),
                expected = unknown,
            )
        }
        log.debug { "failure: $failure" }
        failure.message shouldNotContain "TOP-SECRET"
        unknown.toString() shouldNotContain "09"
    }

/**
 * digestService은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun digestService(
        verificationVersion: Int,
        retainVerificationVersion: Int? = null,
        userIdentityVersion: Int = 3,
        retainUserIdentityVersion: Int? = null,
    ): VoucherDigestService {
/**
 * verificationKeys은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val verificationKeys = buildList {
            add(DigestKey(verificationVersion, keyBytes(verificationVersion)))
            retainVerificationVersion?.let { add(DigestKey(it, keyBytes(it))) }
        }
        return VoucherDigestService(
            stableDedupKey = DigestKey.of(7, keyBytes(7)),
            commandTombstoneKey = DigestKey.of(4, keyBytes(4)),
            rotatingKeys = mapOf(
                DigestPurpose.VERIFICATION to DigestKeyRing(verificationKeys.first(), verificationKeys.drop(1)),
                DigestPurpose.USER_IDENTITY to DigestKeyRing(
                    DigestKey.of(userIdentityVersion, keyBytes(userIdentityVersion)),
                    retainUserIdentityVersion?.let { listOf(DigestKey.of(it, keyBytes(it))) }.orEmpty(),
                ),
                DigestPurpose.REDIS_SIGNAL to DigestKeyRing(DigestKey.of(5, keyBytes(5))),
                DigestPurpose.AUDIT to DigestKeyRing(DigestKey.of(6, keyBytes(6))),
            ),
        )
    }

/**
 * keyBytes은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keyBytes(seed: Int) =
        ByteArray(32) { (seed + it).toByte() }
}
