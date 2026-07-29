package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.workshop.leader.job.LockAssertJob
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58

/**
 * [LockAssertJob]과 [LockAssert] API의 테스트입니다.
 *
 * ## 검증하는 주요 동작
 * - [LockAssert.assertLocked]는 `runIfLeader` 안에서 조용히 통과합니다.
 * - [LockAssert.assertLocked]는 leader scope 밖에서 [IllegalStateException]을 던집니다.
 * - [LockAssert.isLocked]는 scope 안에서 `true`, 밖에서 `false`를 반환합니다.
 * - [LockAssertJob.execute]는 leader로 실행될 때 error 없이 완료됩니다.
 */
class LockAssertTest : AbstractLeaderElectionTest() {

    @Test
    fun `assertLocked passes silently inside runIfLeader`() {
        val lockName = "test:assert:${Base58.randomString(8)}"
        val elector = newElector()

        val result = elector.runIfLeader(lockName) {
            LockAssert.assertLocked()          // must not throw
            LockAssert.assertLocked(lockName)  // named variant must not throw
            "ok"
        }

        result.shouldNotBeNull()
    }

    @Test
    fun `assertLocked throws IllegalStateException outside runIfLeader`() {
        assertFailsWith<IllegalStateException> {
            LockAssert.assertLocked()
        }
    }

    @Test
    fun `isLocked returns true inside runIfLeader`() {
        val lockName = "test:assert:locked:${Base58.randomString(8)}"
        val elector = newElector()

        val isLocked = elector.runIfLeader(lockName) {
            LockAssert.isLocked()
        }

        isLocked.shouldNotBeNull()
        isLocked.shouldBeTrue()
    }

    @Test
    fun `isLocked returns false outside runIfLeader`() {
        LockAssert.isLocked().shouldBeFalse()
    }

    @Test
    fun `LockAssertJob executes successfully as leader`() {
        val lockName = LockAssertJob().lockName
        val elector = newElector()
        val job = LockAssertJob()

        val result = elector.runIfLeader(lockName) { job.execute() }

        result.shouldNotBeNull()
    }
}
