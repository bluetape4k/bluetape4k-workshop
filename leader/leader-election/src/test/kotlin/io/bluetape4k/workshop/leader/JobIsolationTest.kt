package io.bluetape4k.workshop.leader

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.leader.job.LeaderGuardedJob
import io.bluetape4k.workshop.leader.job.LeaderScheduledJobService
import org.junit.jupiter.api.Test
import io.bluetape4k.codec.Base58
import java.util.concurrent.atomic.AtomicInteger

/**
 * P3-12: Job isolation test - 첫 job 실패가 이후 job을 막지 않습니다.
 *
 * [LeaderScheduledJobService.runJobs]가 각 job을 독립적인 try/catch로 감싸는지 검증합니다.
 * 한 job의 예외가 다음 job 실행을 막으면 안 됩니다.
 *
 * [AbstractLeaderElectionTest]를 통해 실제 [LettuceLeaderElector]를 사용합니다.
 * 각 job은 고유한 lockName을 가지므로 두 job 모두 자신의 lock을 독립적으로 획득합니다.
 */
class JobIsolationTest : AbstractLeaderElectionTest() {

    @Test
    fun `failing job does not prevent subsequent jobs from running`() {
        val successCount = AtomicInteger(0)

        val failingJob = object : LeaderGuardedJob {
            override val lockName = "test:isolate:fail:${Base58.randomString(8)}"
            override fun execute() {
                throw RuntimeException("intentional failure in failingJob")
            }
        }
        val successJob = object : LeaderGuardedJob {
            override val lockName = "test:isolate:success:${Base58.randomString(8)}"
            override fun execute() {
                successCount.incrementAndGet()
            }
        }

        // 실제 elector를 사용합니다. failingJob은 runIfLeader lambda 안에서 예외를 던지고,
        // 이 예외는 전파되어 runJobs()의 try/catch가 잡습니다.
        // successJob은 자신의 lockName으로 독립 실행됩니다.
        val elector = newElector()
        val service = LeaderScheduledJobService(elector, listOf(failingJob, successJob))
        service.runJobs()

        // failingJob 예외와 관계없이 successJob은 실행되어야 합니다.
        successCount.get() shouldBeEqualTo 1
    }
}
