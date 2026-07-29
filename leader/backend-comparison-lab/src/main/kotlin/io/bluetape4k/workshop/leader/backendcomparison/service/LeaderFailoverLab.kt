package io.bluetape4k.workshop.leader.backendcomparison.service

import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenario
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioEvent
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioKind
import io.bluetape4k.workshop.leader.backendcomparison.domain.LeaderScenarioReport
import org.springframework.stereotype.Service

/**
 * leader backend 동작을 비교하기 위한 deterministic scenario report를 만듭니다.
 *
 * 이 서비스는 의도적으로 실제 backend client를 시작하지 않습니다. 실제 Redis, ZooKeeper,
 * Kubernetes 연습 경로는 기존 워크숍 모듈에 두고, 이 lab은 눈에 보이는 `runIfLeader`
 * 계약과 backend별 handoff trigger만 모델링합니다.
 */
@Service
class LeaderFailoverLab(
    private val catalog: LeaderBackendCatalog,
) {

    /**
     * 요청한 backend에 대한 deterministic comparison scenario를 실행합니다.
     */
    fun run(scenario: LeaderScenario): LeaderScenarioReport {
        val profile = catalog.findById(scenario.backendId)

        return when (scenario.kind) {
            LeaderScenarioKind.STEADY_LEADER ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "${scenario.jobName} ran inside runIfLeader."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = profile.metricsAndEvents,
                    summary = "node-a executed ${scenario.jobName}; node-b skipped because ${skipSummary(profile.id)}.",
                )

            LeaderScenarioKind.CONTENTION_SKIP ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "executed", "First contender owns the guarded tick."),
                        LeaderScenarioEvent("node-b", "skipped", skipDetail(profile.id)),
                        LeaderScenarioEvent("node-c", "skipped", skipDetail(profile.id)),
                    ),
                    metricsToInspect = profile.metricsAndEvents,
                    summary = "one contender executes; remaining contenders receive the skip signal.",
                )

            LeaderScenarioKind.ACTION_FAILURE ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent("node-a", "failed", "${scenario.jobName} failed inside the elected block."),
                        LeaderScenarioEvent("node-a", "released", releaseDetail(profile.id)),
                        LeaderScenarioEvent("node-b", "executed-after-recovery", "Next eligible guarded tick can run."),
                    ),
                    metricsToInspect = profile.metricsAndEvents,
                    summary = "node-a failure is visible; the next eligible run can recover through ${profile.failoverTrigger}.",
                )

            LeaderScenarioKind.BACKEND_LOSS_HANDOFF ->
                LeaderScenarioReport(
                    backendId = profile.id,
                    scenario = scenario.kind,
                    handoffTrigger = profile.failoverTrigger,
                    events = listOf(
                        LeaderScenarioEvent(primaryActor(profile.id), "lost-leadership", profile.failoverTrigger),
                        LeaderScenarioEvent(
                            secondaryActor(profile.id),
                            "executed-after-handoff",
                            "Next guarded tick observes available leadership.",
                        ),
                    ),
                    metricsToInspect = profile.metricsAndEvents,
                    summary = handoffSummary(profile.id),
                )
        }
    }

    private fun skipDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held until release or TTL expiry."
            "zookeeper-curator" -> "ZooKeeper mutex path is owned by another session."
            "kubernetes-lease" -> "Lease holder identity has not expired yet."
            else -> "Leadership is already owned."
        }

    private fun skipSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Redis lock is held"
            "zookeeper-curator" -> "ZooKeeper session owns the znode"
            "kubernetes-lease" -> "the Lease holder is still current"
            else -> "the backend reports an active leader"
        }

    private fun releaseDetail(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "Lettuce elector releases the lock in the action boundary."
            "zookeeper-curator" -> "Curator session or mutex ownership is cleared before the next run."
            "kubernetes-lease" -> "Lease ownership remains observable until expiry or update."
            else -> "Backend releases or expires ownership."
        }

    private fun primaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-a" else "node-a"

    private fun secondaryActor(backendId: String): String =
        if (backendId == "kubernetes-lease") "pod-b" else "node-b"

    private fun handoffSummary(backendId: String): String =
        when (backendId) {
            "redis-lettuce" -> "node-a disappears; node-b executes after the Redis lease expires."
            "zookeeper-curator" -> "node-a loses its ZooKeeper session; node-b executes after the ephemeral znode is gone."
            "kubernetes-lease" -> "pod-a loses the Lease; pod-b observes expiry and executes after the next guarded tick."
            else -> "a new candidate executes after the backend exposes leadership loss."
        }
}
