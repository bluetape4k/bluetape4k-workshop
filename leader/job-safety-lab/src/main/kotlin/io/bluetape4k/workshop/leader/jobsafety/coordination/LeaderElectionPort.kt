package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.workshop.leader.jobsafety.domain.JobName
import io.bluetape4k.workshop.leader.jobsafety.domain.LeaderOwnerId
import kotlin.time.Duration

fun interface LeaderElectionPort {
    fun tryAcquire(jobName: JobName): LeaderLease?
}

interface LeaderLease {
    val ownerId: LeaderOwnerId

    /**
     * 요청 thread에서 호출해도 owner thread의 실제 [io.bluetape4k.leader.LockExtender]를
     * 사용하도록 전달하는 workshop proxy입니다. 기존 fake lease는 기본 [ExtendOutcome.Rejected]
     * 를 반환해 새 경계를 명시적으로 opt-in합니다.
     */
    fun extendViaLockExtender(lockAtMostFor: Duration): ExtendOutcome = ExtendOutcome.Rejected

    fun release()
}
