package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.workshop.leader.job.LockExtenderJob
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58

/**
 * Tests for [LockExtenderJob] and the [LockExtender] API.
 *
 * ## Key behaviours verified
 * - [LockExtender.extendActiveLock] returns `true` when called inside `runIfLeader`.
 * - [LockExtender.extendActiveLock] returns `false` when called outside any leader scope.
 * - [LockExtenderJob.execute] completes without error when the elector is in scope.
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
        // LockExtender has no active handle when called outside runIfLeader.
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
