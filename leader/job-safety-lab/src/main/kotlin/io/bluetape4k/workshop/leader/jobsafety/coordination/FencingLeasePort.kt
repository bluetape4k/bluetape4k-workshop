package io.bluetape4k.workshop.leader.jobsafety.coordination

import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import java.time.Duration

interface FencingLeasePort {
    /** Initializes the externally approved epoch before the first acquisition. */
    fun bootstrap(conflictKey: ConflictKey): FenceBootstrapResult = FenceBootstrapResult.Ready

    fun acquire(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        ttl: Duration,
    ): FenceAcquireResult
}

sealed interface FenceBootstrapResult {
    data object Ready : FenceBootstrapResult

    data class BackendFailure(val cause: Throwable) : FenceBootstrapResult
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
