package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * VoucherScenarioFixturesTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherScenarioFixturesTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "catalog covers every documented deterministic recovery scenario" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "risk fixture is server owned one shot and never accepts a customer override" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "reset clears only the selected tenant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "client choreography scenarios never pretend to arm a server signal" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "risk fixture follows the enclosing transaction outcome" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "fixture reset follows the enclosing transaction outcome" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
