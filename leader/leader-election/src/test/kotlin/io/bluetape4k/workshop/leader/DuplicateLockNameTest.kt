package io.bluetape4k.workshop.leader

import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * P3-11: Duplicate lockName detection unit test.
 *
 * Verifies that [LeaderScheduledJobService] detects duplicate [LeaderGuardedJob.lockName]
 * values at startup and throws [IllegalStateException].
 *
 * No Redis container required — pure unit test.
 */
class DuplicateLockNameTest {

    @Test
    fun `duplicate lockName in job list throws IllegalStateException`() {
        val elector = mockk<io.bluetape4k.leader.LeaderElector>()
        val job1 = object : LeaderGuardedJob {
            override val lockName = "leader:cache-warmup"
            override fun execute() {}
        }
        val job2 = object : LeaderGuardedJob {
            override val lockName = "leader:cache-warmup"  // same lockName — duplicate!
            override fun execute() {}
        }

        assertFailsWith<IllegalStateException> {
            LeaderScheduledJobService(elector, listOf(job1, job2))
        }
    }

    @Test
    fun `unique lockNames in job list initialize successfully`() {
        val elector = mockk<io.bluetape4k.leader.LeaderElector>()
        val job1 = object : LeaderGuardedJob {
            override val lockName = "leader:job-a"
            override fun execute() {}
        }
        val job2 = object : LeaderGuardedJob {
            override val lockName = "leader:job-b"
            override fun execute() {}
        }

        // Must not throw
        val service = LeaderScheduledJobService(elector, listOf(job1, job2))
        assert(service != null)
    }
}
