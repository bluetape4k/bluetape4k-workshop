package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.workshop.leader.job.LockAssertJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Tests for [LockAssertJob] and the [LockAssert] API.
 *
 * ## Key behaviours verified
 * - [LockAssert.assertLocked] passes silently inside `runIfLeader`.
 * - [LockAssert.assertLocked] throws [IllegalStateException] outside any leader scope.
 * - [LockAssert.isLocked] returns `true` inside scope and `false` outside.
 * - [LockAssertJob.execute] completes without error when running as leader.
 */
class LockAssertTest : AbstractLeaderElectionTest() {

    @Test
    fun `assertLocked passes silently inside runIfLeader`() {
        val lockName = "test:assert:${UUID.randomUUID()}"
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
        assertThrows<IllegalStateException> {
            LockAssert.assertLocked()
        }
    }

    @Test
    fun `isLocked returns true inside runIfLeader`() {
        val lockName = "test:assert:locked:${UUID.randomUUID()}"
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
