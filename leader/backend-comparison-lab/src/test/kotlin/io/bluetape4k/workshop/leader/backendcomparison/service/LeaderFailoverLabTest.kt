package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderFailoverLabTest {

    private val lab = LeaderFailoverLab(LeaderBackendCatalog())

    @Test
    fun `steady leader scenario executes one node and skips follower`() {
        val report = lab.run(LeaderScenario.steadyLeader("redis-lettuce"))

        report.backendId shouldBeEqualTo "redis-lettuce"
        report.events.map { it.outcome } shouldBeEqualTo listOf("executed", "skipped")
        report.summary shouldBeEqualTo "node-a executed report-sync; node-b skipped because Redis lock is held."
    }

    @Test
    fun `contention skip scenario keeps followers out of the guarded job`() {
        val report = lab.run(LeaderScenario.contentionSkip("redis-lettuce"))

        report.events.map { it.outcome } shouldBeEqualTo listOf("executed", "skipped", "skipped")
        report.summary shouldBeEqualTo "one contender executes; remaining contenders receive the skip signal."
    }

    @Test
    fun `action failure scenario records release then next eligible run`() {
        val report = lab.run(LeaderScenario.actionFailure("zookeeper-curator"))

        report.events.map { it.outcome } shouldContain "failed"
        report.events.map { it.outcome } shouldContain "executed-after-recovery"
        report.handoffTrigger shouldBeEqualTo "ZooKeeper session loss"
    }

    @Test
    fun `backend loss handoff uses backend specific trigger`() {
        val report = lab.run(LeaderScenario.backendLossHandoff("kubernetes-lease"))

        report.handoffTrigger shouldBeEqualTo "Lease expiry and resource-version update"
        report.metricsToInspect shouldContain "leader-micrometer meters"
        report.summary shouldBeEqualTo
            "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
    }
}
