package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * P3-12: Job isolation test — first job failure does not block subsequent jobs.
 *
 * Verifies that [LeaderScheduledJobService.runJobs] wraps each job in an independent
 * try/catch. An exception from one job must not prevent the next job from running.
 *
 * Uses a real [LettuceLeaderElector] (via [AbstractLeaderElectionTest]) — each job
 * has a unique lockName so both jobs acquire their own lock independently.
 */
class JobIsolationTest : AbstractLeaderElectionTest() {

    @Test
    fun `failing job does not prevent subsequent jobs from running`() {
        val successCount = AtomicInteger(0)

        val failingJob = object : LeaderGuardedJob {
            override val lockName = "test:isolate:fail:${UUID.randomUUID()}"
            override fun execute() {
                throw RuntimeException("intentional failure in failingJob")
            }
        }
        val successJob = object : LeaderGuardedJob {
            override val lockName = "test:isolate:success:${UUID.randomUUID()}"
            override fun execute() {
                successCount.incrementAndGet()
            }
        }

        // Use real elector — failingJob throws inside runIfLeader lambda,
        // which propagates and is caught by the try/catch in runJobs().
        // successJob runs independently with its own lockName.
        val elector = newElector()
        val service = LeaderScheduledJobService(elector, listOf(failingJob, successJob))
        service.runJobs()

        // successJob must have run despite failingJob's exception
        successCount.get() shouldBeEqualTo 1
    }
}
