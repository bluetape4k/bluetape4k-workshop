package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId

fun interface LeaderElectionPort {
    fun tryAcquire(jobName: JobName): LeaderLease?
}

interface LeaderLease {
    val ownerId: LeaderOwnerId

    fun release()
}
