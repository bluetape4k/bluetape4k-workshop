package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

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
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.REDEMPTION) shouldBeEqualTo null
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo RiskSignal.REVIEW
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null

        fixtures.configure("redis-outage", "tenant", "principal")
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo RiskSignal.UNKNOWN

        fixtures.configure("redemption-review", "tenant", "principal")
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.REDEMPTION) shouldBeEqualTo RiskSignal.REVIEW
    }

    @Test
    fun `reset clears only the selected tenant`() {
        val fixtures = VoucherScenarioFixtures()
        fixtures.configure("allocation-review", "tenant-a", "principal")
        fixtures.configure("allocation-review", "tenant-b", "principal")

        fixtures.reset("tenant-a") shouldBeEqualTo 1
        fixtures.consumeRiskSignal("tenant-a", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null
        fixtures.consumeRiskSignal("tenant-b", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo RiskSignal.REVIEW
    }

    @Test
    fun `client choreography scenarios never pretend to arm a server signal`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.configure("capacity-race", "tenant", "principal").riskOperation shouldBeEqualTo null
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null
        fixtures.reset("tenant") shouldBeEqualTo 0
    }

    @Test
    fun `risk fixture follows the enclosing transaction outcome`() {
        val fixtures = VoucherScenarioFixtures()

        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.configure("allocation-review", "tenant", "principal")
            fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null

        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.configure("allocation-review", "tenant", "principal")
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo RiskSignal.REVIEW
    }

    @Test
    fun `fixture reset follows the enclosing transaction outcome`() {
        val fixtures = VoucherScenarioFixtures()
        fixtures.configure("allocation-review", "tenant", "principal")

        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.reset("tenant") shouldBeEqualTo 1
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo RiskSignal.REVIEW

        fixtures.configure("allocation-review", "tenant", "principal")
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.reset("tenant") shouldBeEqualTo 1
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal("tenant", "principal", FixtureRiskOperation.ALLOCATION) shouldBeEqualTo null
    }
}
