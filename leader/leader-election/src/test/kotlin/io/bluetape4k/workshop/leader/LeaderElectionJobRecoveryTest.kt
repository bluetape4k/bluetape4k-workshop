package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import io.bluetape4k.assertions.assertFailsWith

/**
 * T3: job 실패 뒤 leader re-election입니다.
 *
 * job이 예외를 던질 때 lock이 `runImpl.finally { lock.unlock() }`로 release되어
 * 직후 다른 instance가 lock을 획득할 수 있는지 검증합니다.
 */
class LeaderElectionJobRecoveryTest : AbstractLeaderElectionTest() {

    @Test
    fun `lock is released after job throws exception, allowing re-election`() {
        val lockName = "test:t3:${Base58.randomString(8)}"
        val elector1 = newElector()
        val elector2 = newElector()

        // elector1이 lock을 획득한 뒤 예외를 던집니다. lock은 finally block에서 release되어야 합니다.
        assertFailsWith<IllegalStateException> {
            elector1.runIfLeader(lockName) {
                throw IllegalStateException("intentional failure")
            }
        }

        // elector2는 lock을 즉시 획득할 수 있습니다(leaseTime 대기 불필요).
        val recovered = elector2.runIfLeader(lockName) { "recovered" }

        recovered.shouldNotBeNull() shouldBeEqualTo "recovered"
    }
}
