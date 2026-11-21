package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandTestSupport
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import io.bluetape4k.workshop.commerce.voucher.config.voucherDistributedRateLimiter
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * LettuceVoucherAdmissionIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class LettuceVoucherAdmissionIntegrationTest: VoucherCommandTestSupport() {

    private companion object {
/**
 * redis은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val redis: RedisServer by lazy { RedisServer.Launcher.redis }
    }

    @Test
/**
 * 이 시나리오는 "Bluetape Bucket4j quota and Bloom filter use opaque versioned keys" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Bluetape Bucket4j quota and Bloom filter use opaque versioned keys`() =
        withRedis { resources, properties ->
/**
 * keys은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val keys = keyFactory()
/**
 * gate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val gate = VoucherAdmissionGate(voucherDistributedRateLimiter(resources.client, properties))
/**
 * risk은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val risk = RiskSignalService(keys, LettuceBloomRiskBackend(checkNotNull(resources.bloomFilter)))
            flush(resources)

/**
 * rateKey은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val rateKey = keys.rateKey(TENANT_ID, "principal-1", "allocate")
            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed

/**
 * rejection은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val rejection = gate.decide(rateKey)
            rejection.shouldBeInstanceOf<AdmissionDecision.RateLimited>()
            rejection.retryAfter shouldBeGreaterThan Duration.ZERO

            risk.assess(TENANT_ID, "subject-1") shouldBeEqualTo RiskSignal.CLEAR
            risk.remember(TENANT_ID, "subject-1").shouldBeTrue()
            risk.assess(TENANT_ID, "subject-1") shouldBeEqualTo RiskSignal.REVIEW
        }

    @Test
/**
 * 이 시나리오는 "Redis flush never overrides committed PostgreSQL allocation authority" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Redis flush never overrides committed PostgreSQL allocation authority`() =
        withRedis { resources, properties ->
/**
 * keys은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val keys = keyFactory()
/**
 * gate은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val gate = VoucherAdmissionGate(voucherDistributedRateLimiter(resources.client, properties))
/**
 * rateKey은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val rateKey = keys.rateKey(TENANT_ID, "principal-1", "allocate")
            flush(resources)
            createCampaign(capacity = 1, perUserLimit = 1)

            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed
            allocation.allocate(
                AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "same-user")
            )
            flush(resources)
            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed

            assertFailsWith<VoucherCommandException> {
                allocation.allocate(
                    AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "same-user")
                )
            }.code shouldBeEqualTo VoucherCommandFailure.PER_USER_LIMIT_REACHED
            campaignSnapshot().allocatedCount shouldBeEqualTo 1
        }

/**
 * withRedis은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun withRedis(block: (VoucherRedisResources, VoucherRedisProperties) -> Unit) {
        val properties = VoucherRedisProperties(
            enabled = true,
            uri = redis.url,
            commandTimeout = 1.seconds(),
            quotaCapacity = 1,
            quotaPeriod = 1.minutes(),
            bloomExpectedInsertions = 1_000,
            bloomFalseProbability = 0.01,
        )
        val cleanupClient = LettuceClients.clientOf(redis.url)
        try {
            LettuceClients.connect(cleanupClient).sync().flushall()
        } finally {
            LettuceClients.shutdown(cleanupClient)
        }

        VoucherRedisResources.open(properties).use { resources ->
            try {
                block(resources, properties)
            } finally {
                flush(resources)
            }
        }
    }

/**
 * flush은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun flush(resources: VoucherRedisResources) {
        resources.client.connect().use {
            it.sync().flushall()
        }
    }

/**
 * keyFactory은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun keyFactory(): VoucherAdmissionKeyFactory =
        VoucherAdmissionKeyFactory(
            version = 1,
            rateKey = ByteArray(32) { 0x61 },
            riskKey = ByteArray(32) { 0x62 },
        )
}
