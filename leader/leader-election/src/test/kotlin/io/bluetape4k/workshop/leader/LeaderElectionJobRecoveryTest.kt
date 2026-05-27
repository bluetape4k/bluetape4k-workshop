package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith

/**
 * T3: Leader re-election after job failure.
 *
 * Verifies that when a job throws an exception, the lock is released via
 * `runImpl.finally { lock.unlock() }`, allowing another instance to acquire
 * the lock immediately after.
 */
class LeaderElectionJobRecoveryTest : AbstractLeaderElectionTest() {

    @Test
    fun `lock is released after job throws exception, allowing re-election`() {
        val lockName = "test:t3:${Base58.randomString(8)}"
        val elector1 = newElector()
        val elector2 = newElector()

        // elector1 acquires lock, then throws — lock must be released in finally block
        assertFailsWith<IllegalStateException> {
            elector1.runIfLeader(lockName) {
                throw IllegalStateException("intentional failure")
            }
        }

        // elector2 can acquire the lock immediately (no leaseTime wait needed)
        val recovered = elector2.runIfLeader(lockName) { "recovered" }

        recovered.shouldNotBeNull() shouldBeEqualTo "recovered"
    }
}
