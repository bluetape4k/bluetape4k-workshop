package io.bluetape4k.workshop.commerce.voucherpool.fixture

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.commerce.voucherpool.admission.DatabasePermitGate
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicInteger

internal class VoucherPoolFixturesTest {
    private val executor =
        VoucherPoolJdbcExecutor(
            gate = DatabasePermitGate.default(16),
            transactionManager = mockk<PlatformTransactionManager>(relaxed = true),
        )
    private val fixtures = VoucherPoolFixtures(executor)

    @Test
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
    fun `configure fixture and rollback leaves fixture state unarmed`() {
        inTransaction(commit = false) {
            fixtures.configureAfterCommit("reveal-response-loss")
            fixtures.armAfterCommit("reveal-response-loss")
        }

        fixtures.state("reveal-response-loss").armed.shouldBeFalse()
        fixtures.state("reveal-response-loss").consumed.shouldBeFalse()
    }

    @Test
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
    fun `concurrent duplicate claims have exactly one committed winner`() {
        inTransaction {
            fixtures.configureAfterCommit("pause-allocation-race")
            fixtures.armAfterCommit("pause-allocation-race")
        }
        val winners = AtomicInteger()
        MultithreadingTester()
            .workers(16)
            .rounds(1)
            .add {
                var claimed = false
                inTransaction { claimed = fixtures.claim("pause-allocation-race") }
                if (claimed) winners.incrementAndGet()
            }
            .run()

        winners.get() shouldBeEqualTo 1
        fixtures.state("pause-allocation-race").consumed.shouldBeTrue()
        fixtures.state("pause-allocation-race").claimed.shouldBeFalse()
    }

    @Test
    fun `stale completion cannot mutate a reconfigured generation or newer claim`() {
        inTransaction {
            fixtures.configureAfterCommit("restore-smoke")
            fixtures.armAfterCommit("restore-smoke")
        }
        val oldClaim = pendingTransaction { fixtures.claim("restore-smoke").shouldBeTrue() }

        inTransaction {
            fixtures.configureAfterCommit("restore-smoke")
            fixtures.armAfterCommit("restore-smoke")
        }
        val newClaim = pendingTransaction { fixtures.claim("restore-smoke").shouldBeTrue() }

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
    fun `unsupported fixture scenarios fail closed`() {
        assertFailsWith<IllegalArgumentException> { fixtures.state("unknown") }

        inTransaction(commit = false) {
            assertFailsWith<IllegalArgumentException> { fixtures.armAfterCommit("unknown") }
        }
    }

    private fun inTransaction(
        commit: Boolean = true,
        block: () -> Unit,
    ) {
        complete(pendingTransaction(block), commit)
    }

    private fun pendingTransaction(block: () -> Unit): List<TransactionSynchronization> {
        TransactionSynchronizationManager.initSynchronization()
        return try {
            block()
            TransactionSynchronizationManager.getSynchronizations()
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    private fun complete(
        synchronizations: List<TransactionSynchronization>,
        commit: Boolean,
    ) {
        if (commit) synchronizations.forEach(TransactionSynchronization::afterCommit)
        val status =
            if (commit) TransactionSynchronization.STATUS_COMMITTED else TransactionSynchronization.STATUS_ROLLED_BACK
        synchronizations.forEach { it.afterCompletion(status) }
    }
}
