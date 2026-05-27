package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58

/**
 * T1: Single-instance leader election test.
 *
 * When only one instance attempts to acquire a lock, it must be elected and execute the action.
 * Result must be non-null and equal to the expected return value.
 */
class LeaderElectionSingleRunnerTest : AbstractLeaderElectionTest() {

    @Test
    fun `single instance acquires leadership and executes action`() {
        val lockName = "test:t1:${Base58.randomString(8)}"
        val elector = newElector()

        val result = elector.runIfLeader(lockName) { "executed" }

        result.shouldNotBeNull() shouldBeEqualTo "executed"
    }

    @Test
    fun `single instance can run multiple actions with different lockNames`() {
        val elector = newElector()

        val result1 = elector.runIfLeader("test:t1:a:${Base58.randomString(8)}") { 1 }
        val result2 = elector.runIfLeader("test:t1:b:${Base58.randomString(8)}") { 2 }

        result1.shouldNotBeNull() shouldBeEqualTo 1
        result2.shouldNotBeNull() shouldBeEqualTo 2
    }
}
