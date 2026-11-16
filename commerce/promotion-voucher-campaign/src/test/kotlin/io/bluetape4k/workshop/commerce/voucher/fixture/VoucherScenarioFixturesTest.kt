package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal class VoucherScenarioFixturesTest {

    companion object: KLogging()

    @Test
    fun `catalog covers every documented deterministic recovery scenario`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.catalog().map { it.slug }.toSet() shouldBeEqualTo setOf(
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

        fixtures.catalog().all { it.expected.durableAuthority == "POSTGRESQL" }.shouldBeTrue()
    }

    @Test
    fun `risk fixture is server owned one shot and never accepts a customer override`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.configure("bloom-false-positive", "tenant", "principal")
        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.REDEMPTION
        ).shouldBeNull()

        fixtures.consumeRiskSignal(
            "tenant",
            "principal",
            FixtureRiskOperation.ALLOCATION
        ) shouldBeEqualTo RiskSignal.REVIEW

        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()

        fixtures.configure("redis-outage", "tenant", "principal")
        fixtures.consumeRiskSignal(
            "tenant",
            "principal",
            FixtureRiskOperation.ALLOCATION
        ) shouldBeEqualTo RiskSignal.UNKNOWN

        fixtures.configure("redemption-review", "tenant", "principal")
        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()

        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.REDEMPTION
        ) shouldBeEqualTo RiskSignal.REVIEW
    }

    @Test
    fun `reset clears only the selected tenant`() {
        val fixtures = VoucherScenarioFixtures()
        fixtures.configure("allocation-review", "tenant-a", "principal")
        fixtures.configure("allocation-review", "tenant-b", "principal")

        fixtures.reset("tenant-a") shouldBeEqualTo 1
        fixtures.consumeRiskSignal(
            tenantId = "tenant-a",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()

        fixtures.consumeRiskSignal(
            tenantId = "tenant-b",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ) shouldBeEqualTo RiskSignal.REVIEW
    }

    @Test
    fun `client choreography scenarios never pretend to arm a server signal`() {
        val fixtures = VoucherScenarioFixtures()

        fixtures.configure(
            slug = "capacity-race",
            tenantId = "tenant",
            principalRef = "principal"
        ).riskOperation.shouldBeNull()

        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()

        fixtures.reset("tenant") shouldBeEqualTo 0
    }

    @Test
    fun `risk fixture follows the enclosing transaction outcome`() {
        val fixtures = VoucherScenarioFixtures()

        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.configure("allocation-review", "tenant", "principal")
            fixtures.consumeRiskSignal(
                tenantId = "tenant",
                principalRef = "principal",
                operation = FixtureRiskOperation.ALLOCATION
            ).shouldBeNull()
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()

        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.configure("allocation-review", "tenant", "principal")

            val synchronizations = TransactionSynchronizationManager.getSynchronizations()
            synchronizations.forEach { it.afterCommit() }
            synchronizations.forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ) shouldBeEqualTo RiskSignal.REVIEW
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
        fixtures.consumeRiskSignal(
            "tenant",
            "principal",
            FixtureRiskOperation.ALLOCATION
        ) shouldBeEqualTo RiskSignal.REVIEW

        fixtures.configure("allocation-review", "tenant", "principal")
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixtures.reset("tenant") shouldBeEqualTo 1
            val synchronizations = TransactionSynchronizationManager.getSynchronizations()
            synchronizations.forEach { it.afterCommit() }
            synchronizations.forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixtures.consumeRiskSignal(
            tenantId = "tenant",
            principalRef = "principal",
            operation = FixtureRiskOperation.ALLOCATION
        ).shouldBeNull()
    }
}
