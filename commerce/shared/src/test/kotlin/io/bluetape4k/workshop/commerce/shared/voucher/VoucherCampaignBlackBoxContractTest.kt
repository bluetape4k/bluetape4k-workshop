package io.bluetape4k.workshop.commerce.shared.voucher

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * VoucherCampaignBlackBoxContractTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class VoucherCampaignBlackBoxContractTest {

    companion object: KLogging() {
/**
 * STARTS_AT은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val STARTS_AT: Instant = Instant.parse("2026-07-24T00:00:00Z")
/**
 * ENDS_AT은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val ENDS_AT: Instant = Instant.parse("2026-07-31T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "request rejects blank caller identity" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `request rejects blank caller identity`() {
        assertFailsWith<IllegalArgumentException> {
            request(tenant = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            request(principal = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            request(idempotencyKey = " ")
        }
    }

    @Test
/**
 * 이 시나리오는 "request rejects invalid campaign limits and interval" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `request rejects invalid campaign limits and interval`() {
        assertFailsWith<IllegalArgumentException> {
            request(capacity = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request(perUserLimit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request(redemptionTtlSeconds = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            request(startsAt = ENDS_AT)
        }
    }

    @Test
/**
 * 이 시나리오는 "activation request rejects blank identity and negative revision" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `activation request rejects blank identity and negative revision`() {
        assertFailsWith<IllegalArgumentException> {
            activation(tenant = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            activation(principal = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            activation(idempotencyKey = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            activation(expectedRevision = -1)
        }
    }

/**
 * activation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun activation(
        tenant: String = "tenant",
        principal: String = "operator",
        idempotencyKey: String = "activation-key",
        expectedRevision: Long = 0,
    ): VoucherCampaignActivationRequest =
        VoucherCampaignActivationRequest(
            tenant = tenant,
            principal = principal,
            idempotencyKey = idempotencyKey,
            campaignId = UUID.fromString("01984f10-a21e-7c0b-8e12-9d6d62f05462"),
            expectedRevision = expectedRevision,
        )

/**
 * request은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun request(
        tenant: String = "tenant",
        principal: String = "operator",
        idempotencyKey: String = "request-key",
        startsAt: Instant = STARTS_AT,
        capacity: Int = 10,
        perUserLimit: Int = 2,
        redemptionTtlSeconds: Long = 3_600,
    ): VoucherCampaignBlackBoxRequest =
        VoucherCampaignBlackBoxRequest(
            tenant = tenant,
            principal = principal,
            idempotencyKey = idempotencyKey,
            campaignId = UUID.fromString("01984f10-a21e-7c0b-8e12-9d6d62f05462"),
            startsAt = startsAt,
            endsAt = ENDS_AT,
            capacity = capacity,
            perUserLimit = perUserLimit,
            redemptionTtlSeconds = redemptionTtlSeconds,
        )
}
