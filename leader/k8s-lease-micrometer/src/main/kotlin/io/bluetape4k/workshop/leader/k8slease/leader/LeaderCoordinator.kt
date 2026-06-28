package io.bluetape4k.workshop.leader.k8slease.leader

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug

/**
 * Application boundary for running a coroutine task only when this instance is elected leader.
 */
interface LeaderCoordinator {
    /**
     * Executes [action] when this instance owns [lockName], or returns `null` when skipped.
     */
    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?
}

/**
 * Smoke-safe coordinator used when the real Kubernetes Lease backend is disabled.
 */
class DisabledLeaderCoordinator : LeaderCoordinator {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        log.debug { "Kubernetes Lease coordinator disabled. lockName=$lockName" }
        return null
    }

    private companion object : KLogging()
}

/**
 * Coordinator backed by a `bluetape4k-leader` coroutine elector.
 */
class ElectorLeaderCoordinator(
    private val elector: SuspendLeaderElector,
) : LeaderCoordinator {
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        elector.runIfLeader(lockName, action)
}
