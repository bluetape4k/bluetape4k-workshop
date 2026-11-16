package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

/**
 * T1: Single-instance leader election test입니다.
 *
 * 하나의 instance만 lock 획득을 시도하면 반드시 선출되어 action을 실행해야 합니다.
 * 결과는 non-null이어야 하며 기대 반환값과 같아야 합니다.
 */
class LeaderElectionSingleRunnerTest: AbstractLeaderElectionTest() {

    companion object: KLogging()

    @Test
    fun `single instance acquires leadership and executes action`() {
        val lockName = "test:t1:${Base58.randomString(8)}"
        val elector = newElector()

        val result = elector.runIfLeader(lockName) { "executed" }

        result shouldBeEqualTo "executed"
    }

    @Test
    fun `single instance can run multiple actions with different lockNames`() {
        val elector = newElector()

        val result1 = elector.runIfLeader("test:t1:a:${Base58.randomString(8)}") { 1 }
        val result2 = elector.runIfLeader("test:t1:b:${Base58.randomString(8)}") { 2 }

        result1 shouldBeEqualTo 1
        result2 shouldBeEqualTo 2
    }
}
