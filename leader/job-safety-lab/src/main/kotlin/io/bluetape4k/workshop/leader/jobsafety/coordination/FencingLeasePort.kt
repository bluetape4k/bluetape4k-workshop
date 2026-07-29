package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import java.time.Duration

fun interface FencingLeasePort {
    fun acquire(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        ttl: Duration,
    ): FenceAcquireResult
}

interface FencingLease {
    val conflictKey: ConflictKey
    val ownerId: FencingOwnerId
    val token: FencingToken

    fun release()
}

sealed interface FenceAcquireResult {
    data class Acquired(val lease: FencingLease) : FenceAcquireResult

    data class AlreadyOwned(val lease: FencingLease) : FenceAcquireResult

    data object Contended : FenceAcquireResult

    data class BackendFailure(val cause: Throwable) : FenceAcquireResult
}
