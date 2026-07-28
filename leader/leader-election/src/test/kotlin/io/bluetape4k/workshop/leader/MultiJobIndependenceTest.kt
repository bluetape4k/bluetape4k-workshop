package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58

/**
 * T4: 독립 lock name을 가진 multiple job 테스트입니다.
 *
 * 단일 elector는 lockName마다 하나씩 여러 lock을 동시에 보유할 수 있습니다.
 * 같은 instance가 순차 실행하면 두 job 모두 실행되어야 합니다.
 */
class MultiJobIndependenceTest : AbstractLeaderElectionTest() {

    @Test
    fun `two jobs with different lockNames are both executed by the same elector`() {
        val lockA = "test:t4:job-a:${Base58.randomString(8)}"
        val lockB = "test:t4:job-b:${Base58.randomString(8)}"
        val elector = newElector()

        val resultA = elector.runIfLeader(lockA) { "A" }
        val resultB = elector.runIfLeader(lockB) { "B" }

        resultA.shouldNotBeNull() shouldBeEqualTo "A"
        resultB.shouldNotBeNull() shouldBeEqualTo "B"
    }
}
