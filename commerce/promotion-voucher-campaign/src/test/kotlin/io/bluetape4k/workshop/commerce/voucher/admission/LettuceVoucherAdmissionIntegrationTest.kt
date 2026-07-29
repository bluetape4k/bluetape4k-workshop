package io.bluetape4k.workshop.commerce.voucher.admission

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.workshop.commerce.voucher.application.AllocateVoucherCommand
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandException
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandFailure
import io.bluetape4k.workshop.commerce.voucher.application.VoucherCommandTestSupport
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisProperties
import io.bluetape4k.workshop.commerce.voucher.config.VoucherRedisResources
import io.bluetape4k.workshop.commerce.voucher.config.voucherDistributedRateLimiter
import org.junit.jupiter.api.Test
import java.time.Duration

internal class LettuceVoucherAdmissionIntegrationTest : VoucherCommandTestSupport() {
    @Test
    fun `Bluetape Bucket4j quota and Bloom filter use opaque versioned keys`() =
        withRedis { resources, properties ->
            val keys = keyFactory()
            val gate = VoucherAdmissionGate(voucherDistributedRateLimiter(resources.client, properties))
            val risk = RiskSignalService(keys, LettuceBloomRiskBackend(checkNotNull(resources.bloomFilter)))
            flush(resources)

            val rateKey = keys.rateKey(TENANT_ID, "principal-1", "allocate")
            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed
            val rejection = gate.decide(rateKey)
            (rejection is AdmissionDecision.RateLimited) shouldBeEqualTo true
            ((rejection as AdmissionDecision.RateLimited).retryAfter > Duration.ZERO) shouldBeEqualTo true

            risk.assess(TENANT_ID, "subject-1") shouldBeEqualTo RiskSignal.CLEAR
            risk.remember(TENANT_ID, "subject-1") shouldBeEqualTo true
            risk.assess(TENANT_ID, "subject-1") shouldBeEqualTo RiskSignal.REVIEW
        }

    @Test
    fun `Redis flush never overrides committed PostgreSQL allocation authority`() =
        withRedis { resources, properties ->
            val keys = keyFactory()
            val gate = VoucherAdmissionGate(voucherDistributedRateLimiter(resources.client, properties))
            val rateKey = keys.rateKey(TENANT_ID, "principal-1", "allocate")
            flush(resources)
            createCampaign(capacity = 1, perUserLimit = 1)

            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed
            allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "same-user"))
            flush(resources)
            gate.decide(rateKey) shouldBeEqualTo AdmissionDecision.Proceed

            assertFailsWith<VoucherCommandException> {
                allocation.allocate(AllocateVoucherCommand(TENANT_ID, CAMPAIGN_ID, "same-user"))
            }.code shouldBeEqualTo VoucherCommandFailure.PER_USER_LIMIT_REACHED
            campaignSnapshot().allocatedCount shouldBeEqualTo 1
        }

    private fun withRedis(block: (VoucherRedisResources, VoucherRedisProperties) -> Unit) {
        val properties =
            VoucherRedisProperties(
                enabled = true,
                uri = redis.url,
                commandTimeout = Duration.ofSeconds(1),
                quotaCapacity = 1,
                quotaPeriod = Duration.ofMinutes(1),
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

    private fun flush(resources: VoucherRedisResources) {
        resources.client.connect().use { it.sync().flushall() }
    }

    private fun keyFactory() =
        VoucherAdmissionKeyFactory(
            version = 1,
            rateKey = ByteArray(32) { 0x61 },
            riskKey = ByteArray(32) { 0x62 },
        )

    private companion object {
        val redis: RedisServer = RedisServer.Launcher.redis
    }
}
