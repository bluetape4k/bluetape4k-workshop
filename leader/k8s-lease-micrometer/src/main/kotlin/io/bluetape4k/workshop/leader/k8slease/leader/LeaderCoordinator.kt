package io.bluetape4k.workshop.leader.k8slease.leader

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * 이 instance가 leader로 선출된 경우에만 coroutine task를 실행하는 application boundary입니다.
 */
interface LeaderCoordinator {
    /**
     * 이 instance가 [lockName]을 소유하면 [action]을 실행하고, skip되면 `null`을 반환합니다.
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?
}

/**
 * 실제 Kubernetes Lease backend가 비활성화됐을 때 사용하는 smoke-safe coordinator입니다.
 */
class DisabledLeaderCoordinator : LeaderCoordinator {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        log.debug { "Kubernetes Lease coordinator disabled. lockName=$lockName" }
        return null
    }

    private companion object : KLogging()
}

/**
 * `bluetape4k-leader` coroutine elector가 뒷받침하는 coordinator입니다.
 */
class ElectorLeaderCoordinator(
    private val elector: SuspendLeaderElector,
) : LeaderCoordinator {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        elector.runIfLeader(lockName, action)
}
