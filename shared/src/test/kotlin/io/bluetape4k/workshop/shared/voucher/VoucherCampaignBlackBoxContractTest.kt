package io.bluetape4k.workshop.shared.voucher

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class VoucherCampaignBlackBoxContractTest {
    @Test
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

    companion object {
        private val STARTS_AT: Instant = Instant.parse("2026-07-24T00:00:00Z")
        private val ENDS_AT: Instant = Instant.parse("2026-07-31T00:00:00Z")
    }
}
