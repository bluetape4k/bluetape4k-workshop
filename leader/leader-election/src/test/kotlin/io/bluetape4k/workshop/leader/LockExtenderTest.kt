package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.workshop.leader.job.LockExtenderJob
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58

/**
 * [LockExtenderJob]과 [LockExtender] API의 테스트입니다.
 *
 * ## 검증하는 주요 동작
 * - [LockExtender.extendActiveLock]는 `runIfLeader` 안에서 호출하면 `true`를 반환합니다.
 * - [LockExtender.extendActiveLock]는 leader scope 밖에서 호출하면 `false`를 반환합니다.
 * - [LockExtenderJob.execute]는 elector가 scope 안에 있을 때 error 없이 완료됩니다.
 */
class LockExtenderTest : AbstractLeaderElectionTest() {

    @Test
    fun `extendActiveLock returns true inside runIfLeader`() {
        val lockName = "test:extend:${Base58.randomString(8)}"
        val elector = newElector()

        val extended = elector.runIfLeader(lockName) {
            LockExtender.extendActiveLock(LockExtenderJob.EXTENSION_DURATION)
        }

        extended.shouldNotBeNull()
        extended.shouldBeTrue()
    }

    @Test
    fun `extendActiveLock returns false outside any leader scope`() {
        // runIfLeader 밖에서 호출하면 LockExtender에는 active handle이 없습니다.
        val extended = LockExtender.extendActiveLock(LockExtenderJob.EXTENSION_DURATION)
        extended shouldBeEqualTo false
    }

    @Test
    fun `LockExtenderJob executes successfully as leader`() {
        val lockName = LockExtenderJob().lockName
        val elector = newElector()
        val job = LockExtenderJob()

        val result = elector.runIfLeader(lockName) { job.execute() }

        result.shouldNotBeNull()
    }
}
