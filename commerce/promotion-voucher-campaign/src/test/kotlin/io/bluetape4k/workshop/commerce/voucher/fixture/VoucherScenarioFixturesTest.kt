package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test

internal class VoucherScenarioFixturesTest {
    @Test
    fun `catalog covers every documented deterministic recovery scenario`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.catalog().map { it.slug }.toSet() shouldBeEqualTo
            setOf(
                "happy-allocation",
                "same-key-response-loss",
                "capacity-race",
                "allocation-review",
                "redemption-review",
                "pause-allocation-race",
                "redeem-revoke-race",
                "policy-change",
                "redis-outage",
                "bloom-false-positive",
                "delayed-duplicate-out-of-order",
            )
        fixtures.catalog().all { it.expected.durableAuthority == "POSTGRESQL" } shouldBeEqualTo true
    }

    @Test
    fun `risk fixture is server owned one shot and never accepts a customer override`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.configure("bloom-false-positive", "tenant", "principal")
        fixtures.consumeRiskSignal("tenant", "principal") shouldBeEqualTo RiskSignal.REVIEW
        fixtures.consumeRiskSignal("tenant", "principal") shouldBeEqualTo null

        fixtures.configure("redis-outage", "tenant", "principal")
        fixtures.consumeRiskSignal("tenant", "principal") shouldBeEqualTo RiskSignal.UNKNOWN
    }
}
