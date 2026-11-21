package io.bluetape4k.workshop.commerce.voucherpool.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * VoucherPoolFixturesTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolFixturesTest {

/**
 * executor은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val executor = VoucherPoolJdbcExecutor(
        gate = DatabasePermitGate.default(16),
        transactionManager = mockk<PlatformTransactionManager>(relaxed = true),
    )
/**
 * fixtures은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val fixtures = VoucherPoolFixtures(executor)

    @Test
/**
 * 이 시나리오는 "catalog covers every deterministic recovery scenario" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `catalog covers every deterministic recovery scenario`() {
        fixtures.catalog().map(FixtureScenario::slug).toSet() shouldBeEqualTo
                setOf(
                    "redis-outage",
                    "bloom-false-positive",
                    "reveal-response-loss",
                    "pause-allocation-race",
                    "redeem-revoke-race",
                    "worker-takeover",
                    "ciphertext-quarantine",
                    "restore-smoke",
                )
    }

    @Test
/**
 * 이 시나리오는 "fixture signal arms only after transaction commit" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixture signal arms only after transaction commit`() {
        inTransaction {
            fixtures.configureAfterCommit("reveal-response-loss")
            fixtures.armAfterCommit("reveal-response-loss")

            fixtures.state("reveal-response-loss").armed.shouldBeFalse()
            fixtures.state("reveal-response-loss").consumed.shouldBeFalse()
        }
        fixtures.state("reveal-response-loss").armed.shouldBeTrue()

        inTransaction {
            fixtures.claim("reveal-response-loss").shouldBeTrue()
            fixtures.state("reveal-response-loss").claimed.shouldBeTrue()
            fixtures.state("reveal-response-loss").consumed.shouldBeFalse()
        }
        fixtures.state("reveal-response-loss").consumed.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "configure fixture and rollback leaves fixture state unarmed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configure fixture and rollback leaves fixture state unarmed`() {
        inTransaction(commit = false) {
            fixtures.configureAfterCommit("reveal-response-loss")
            fixtures.armAfterCommit("reveal-response-loss")
        }

        fixtures.state("reveal-response-loss").armed.shouldBeFalse()
        fixtures.state("reveal-response-loss").consumed.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "duplicate arm is idempotent and reconfiguration enables deterministic replay" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `duplicate arm is idempotent and reconfiguration enables deterministic replay`() {
        inTransaction {
            fixtures.configureAfterCommit("worker-takeover")
            fixtures.armAfterCommit("worker-takeover")
            fixtures.armAfterCommit("worker-takeover")
        }

        inTransaction { fixtures.claim("worker-takeover").shouldBeTrue() }
        inTransaction { fixtures.claim("worker-takeover").shouldBeFalse() }

        inTransaction {
            fixtures.configureAfterCommit("worker-takeover")
            fixtures.armAfterCommit("worker-takeover")
        }

        fixtures.state("worker-takeover") shouldBeEqualTo
                FixtureState(FixtureScenario.WORKER_TAKEOVER, armed = true, consumed = false)
    }

    @Test
/**
 * 이 시나리오는 "claimed fixture is restored when transaction rolls back" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `claimed fixture is restored when transaction rolls back`() {
        inTransaction {
            fixtures.configureAfterCommit("redis-outage")
            fixtures.armAfterCommit("redis-outage")
        }

        inTransaction(commit = false) {
            fixtures.claim("redis-outage").shouldBeTrue()
            fixtures.state("redis-outage").claimed.shouldBeTrue()
            fixtures.state("redis-outage").consumed.shouldBeFalse()
        }

        fixtures.state("redis-outage") shouldBeEqualTo
                FixtureState(FixtureScenario.REDIS_OUTAGE, armed = true, consumed = false)
    }

    @Test
/**
 * 이 시나리오는 "concurrent duplicate claims have exactly one committed winner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `concurrent duplicate claims have exactly one committed winner`() {
        inTransaction {
            fixtures.configureAfterCommit("pause-allocation-race")
            fixtures.armAfterCommit("pause-allocation-race")
        }

        val winners = AtomicInteger()
        MultithreadingTester()
            .workers(Runtimex.availableProcessors)
            .rounds(2)
            .add {
                var claimed = false
                inTransaction {
                    claimed = fixtures.claim("pause-allocation-race")
                }
                if (claimed) winners.incrementAndGet()
            }
            .run()

        winners.get() shouldBeEqualTo 1
        fixtures.state("pause-allocation-race").consumed.shouldBeTrue()
        fixtures.state("pause-allocation-race").claimed.shouldBeFalse()
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
/**
 * 이 시나리오는 "virtual threads duplicate claims have exactly one committed winner" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `virtual threads duplicate claims have exactly one committed winner`() {
        inTransaction {
            fixtures.configureAfterCommit("pause-allocation-race")
            fixtures.armAfterCommit("pause-allocation-race")
        }

        val winners = AtomicInteger()
        StructuredTaskScopeTester()
            .rounds(Runtimex.availableProcessors * 2)
            .add {
                var claimed = false
                inTransaction {
                    claimed = fixtures.claim("pause-allocation-race")
                }
                if (claimed) winners.incrementAndGet()
            }
            .run()

        winners.get() shouldBeEqualTo 1
        fixtures.state("pause-allocation-race").consumed.shouldBeTrue()
        fixtures.state("pause-allocation-race").claimed.shouldBeFalse()
    }

    @Test
/**
 * 이 시나리오는 "stale completion cannot mutate a reconfigured generation or newer claim" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `stale completion cannot mutate a reconfigured generation or newer claim`() {
        inTransaction {
            fixtures.configureAfterCommit("restore-smoke")
            fixtures.armAfterCommit("restore-smoke")
        }
        val oldClaim = pendingTransaction {
            fixtures.claim("restore-smoke").shouldBeTrue()
        }

        inTransaction {
            fixtures.configureAfterCommit("restore-smoke")
            fixtures.armAfterCommit("restore-smoke")
        }
        val newClaim = pendingTransaction {
            fixtures.claim("restore-smoke").shouldBeTrue()
        }

        complete(oldClaim, commit = false)
        fixtures.state("restore-smoke").claimed.shouldBeTrue()
        fixtures.state("restore-smoke").consumed.shouldBeFalse()

        complete(oldClaim, commit = true)
        fixtures.state("restore-smoke").claimed.shouldBeTrue()
        fixtures.state("restore-smoke").consumed.shouldBeFalse()

        complete(newClaim, commit = false)
        fixtures.state("restore-smoke") shouldBeEqualTo
                FixtureState(FixtureScenario.RESTORE_SMOKE, armed = true, consumed = false)
    }

    @Test
/**
 * 이 시나리오는 "unsupported fixture scenarios fail closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unsupported fixture scenarios fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            fixtures.state("unknown")
        }

        inTransaction(commit = false) {
            assertFailsWith<IllegalArgumentException> {
                fixtures.armAfterCommit("unknown")
            }
        }
    }

/**
 * inTransaction은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun inTransaction(
        commit: Boolean = true,
        block: () -> Unit,
    ) {
        complete(pendingTransaction(block), commit)
    }

/**
 * pendingTransaction은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private inline fun pendingTransaction(block: () -> Unit): List<TransactionSynchronization> {
        TransactionSynchronizationManager.initSynchronization()
        return try {
            block()
            TransactionSynchronizationManager.getSynchronizations()
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

/**
 * complete은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun complete(
        synchronizations: List<TransactionSynchronization>,
        commit: Boolean,
    ) {
        if (commit) {
            synchronizations.forEach(TransactionSynchronization::afterCommit)
        }
/**
 * status은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val status = when {
            commit -> TransactionSynchronization.STATUS_COMMITTED
            else   -> TransactionSynchronization.STATUS_ROLLED_BACK
        }

        synchronizations.forEach {
            it.afterCompletion(status)
        }
    }
}
