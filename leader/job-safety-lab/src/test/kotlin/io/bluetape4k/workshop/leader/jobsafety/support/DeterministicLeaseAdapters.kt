package io.bluetape4k.workshop.leader.jobsafety.support

import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLease
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderElectionPort
import io.bluetape4k.workshop.leader.jobsafety.coordination.LeaderLease
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId
import java.time.Duration

internal class RecordingLeaderElection(
    private val events: MutableList<String>,
    private val acquired: Boolean = true,
    private val releaseFailure: Boolean = false,
) : LeaderElectionPort {
    override fun tryAcquire(jobName: JobName): LeaderLease? {
        events += "leader.acquire"
        if (!acquired) return null

        return object : LeaderLease {
            override val ownerId: LeaderOwnerId = LeaderOwnerId("leader-owner")

            override fun release() {
                events += "leader.release"
                if (releaseFailure) error("leader release failed")
            }
        }
    }
}

internal class RecordingFencingLease(
    private val events: MutableList<String>,
    private val outcome: Outcome = Outcome.ACQUIRED,
    private val releaseFailure: Boolean = false,
) : FencingLeasePort {
    enum class Outcome { ACQUIRED, ALREADY_OWNED, CONTENDED, BACKEND_FAILURE }

    override fun acquire(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        ttl: Duration,
    ): FenceAcquireResult {
        events += "fence.acquire"
        val lease =
            object : FencingLease {
                override val conflictKey: ConflictKey = conflictKey
                override val ownerId: FencingOwnerId = ownerId
                override val token: FencingToken = FencingToken(42L)

                override fun release() {
                    events += "fence.release"
                    if (releaseFailure) error("fence release failed")
                }
            }

        return when (outcome) {
            Outcome.ACQUIRED -> FenceAcquireResult.Acquired(lease)
            Outcome.ALREADY_OWNED -> FenceAcquireResult.AlreadyOwned(lease)
            Outcome.CONTENDED -> FenceAcquireResult.Contended
            Outcome.BACKEND_FAILURE -> FenceAcquireResult.BackendFailure(IllegalStateException("redis unavailable"))
        }
    }
}
