package io.bluetape4k.workshop.leader

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * T7: `finally { unlock }`을 통한 lock release와 즉시 재획득 테스트입니다.
 *
 * leaseTime이 매우 길어도(30초) action 완료 직후 `LettuceLeaderElector.runImpl`이
 * `finally` block에서 lock을 release하는지 검증합니다.
 *
 * unlock이 발생하지 않으면 `elector2`는 TTL 만료까지 30초를 기다려야 합니다.
 * 그러면 테스트는 timeout으로 실패합니다. 빠르게 통과하면 `finally { unlock }`이 올바르게 동작한 것입니다.
 *
 * 참고: `connection.close()`는 Redis key를 삭제하지 않습니다. key 제거를 기대하고 호출하면 안 됩니다.
 */
class LockReleaseTest : AbstractLeaderElectionTest() {

    @Test
    fun `lock is released immediately after action completes, enabling instant re-acquisition`() {
        val lockName = "test:t7:${Base58.randomString(8)}"
        // 긴 leaseTime입니다. finally-unlock이 실행되지 않으면 elector2는 30초를 기다립니다.
        val longLeaseOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 30.seconds,
        )
        val elector1 = newElector(longLeaseOptions)
        val elector2 = newElector(longLeaseOptions)

        // elector1이 획득하고 no-op을 실행하면 finally-unlock이 즉시 발생합니다.
        elector1.runIfLeader(lockName) { /* no-op */ }

        // elector2는 즉시 획득할 수 있습니다. 30초 TTL 대기가 없습니다.
        val result = elector2.runIfLeader(lockName) { "reacquired" }

        result.shouldNotBeNull() shouldBeEqualTo "reacquired"
    }
}
