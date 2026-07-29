package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import io.mockk.mockk
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

/**
 * P3-11: 중복 lockName 감지 unit test입니다.
 *
 * [LeaderScheduledJobService]가 startup 때 중복 [LeaderGuardedJob.lockName] 값을 감지하고
 * [IllegalStateException]을 던지는지 검증합니다.
 *
 * Redis container가 필요 없는 순수 unit test입니다.
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

        // 예외가 발생하면 안 됩니다.
        val service = LeaderScheduledJobService(elector, listOf(job1, job2))
        service.shouldNotBeNull()
    }
}
