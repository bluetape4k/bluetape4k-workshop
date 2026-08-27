package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.redis.lettuce.lease.FencingAcquireResult
import io.bluetape4k.redis.lettuce.lease.FencingBootstrapResult
import io.bluetape4k.redis.lettuce.lease.FencingOwnerId as LettuceFencingOwnerId
import io.bluetape4k.redis.lettuce.lease.FencingReleaseResult
import io.bluetape4k.redis.lettuce.lease.FencingRenewResult
import io.bluetape4k.redis.lettuce.lease.FencingToken as LettuceFencingToken
import io.bluetape4k.redis.lettuce.lease.LettuceFencingLease
import io.bluetape4k.redis.lettuce.lease.LettuceFencingLeaseConfig
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceBootstrapResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLease
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.lettuce.core.RedisException
import io.lettuce.core.api.StatefulRedisConnection
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

sealed interface FenceRenewResult {
    data class Renewed(val token: FencingToken) : FenceRenewResult

    data object OwnershipLost : FenceRenewResult

    data class BackendFailure(val cause: Throwable) : FenceRenewResult
}

sealed interface FenceReleaseResult {
    data object Released : FenceReleaseResult

    data object OwnershipLost : FenceReleaseResult

    data class BackendFailure(val cause: Throwable) : FenceReleaseResult
}

internal class RedisJobFencingLease(
    override val conflictKey: ConflictKey,
    override val ownerId: FencingOwnerId,
    override val token: FencingToken,
    internal val backendLease: LettuceFencingLease,
    internal val backendOwnerId: LettuceFencingOwnerId,
    internal val backendToken: LettuceFencingToken,
    private val adapter: RedisJobFencingLeaseAdapter,
) : FencingLease {
    override fun release() {
        when (val result = adapter.release(this)) {
            FenceReleaseResult.Released,
            FenceReleaseResult.OwnershipLost,
            -> Unit

            is FenceReleaseResult.BackendFailure ->
                throw RedisException("job fencing lease release failed", result.cause)
        }
    }
}

/**
 * Workshop adapter for the shared Lettuce fencing-lease primitive.
 *
 * The epoch is supplied by the rollout authority. Bootstrap is explicit and never repairs a missing counter or
 * silently chooses an epoch. Resource names are deterministic safe components derived from the domain key; Redis
 * key derivation and owner/token validation remain inside [LettuceFencingLease].
 */
class RedisJobFencingLeaseAdapter(
    private val connection: StatefulRedisConnection<String, String>,
    private val namespaceEpoch: NamespaceEpoch,
    private val namespace: String = DEFAULT_NAMESPACE,
) : FencingLeasePort {
    override fun bootstrap(conflictKey: ConflictKey): FenceBootstrapResult =
        try {
            when (leaseFor(conflictKey).bootstrap()) {
                FencingBootstrapResult.Initialized,
                FencingBootstrapResult.AlreadyInitialized,
                -> FenceBootstrapResult.Ready

                is FencingBootstrapResult.IntegrityFailure ->
                    FenceBootstrapResult.BackendFailure(IllegalStateException("fencing state integrity failure"))

                is FencingBootstrapResult.BackendFailure ->
                    FenceBootstrapResult.BackendFailure(IllegalStateException("fencing backend unavailable"))
            }
        } catch (error: Exception) {
            FenceBootstrapResult.BackendFailure(error)
        }

    override fun acquire(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        ttl: Duration,
    ): FenceAcquireResult {
        val backendOwner = LettuceFencingOwnerId.from(ownerId.value)
        return try {
            when (val result = leaseFor(conflictKey).acquire(backendOwner, ttl)) {
                is FencingAcquireResult.Acquired ->
                    FenceAcquireResult.Acquired(
                        lease(conflictKey, ownerId, backendOwner, result.token),
                    )

                is FencingAcquireResult.AlreadyOwned ->
                    FenceAcquireResult.AlreadyOwned(
                        lease(conflictKey, ownerId, backendOwner, result.token),
                    )

                is FencingAcquireResult.Contended -> FenceAcquireResult.Contended
                FencingAcquireResult.CounterUnavailable ->
                    FenceAcquireResult.BackendFailure(IllegalStateException("fencing counter unavailable"))

                FencingAcquireResult.SequenceExhausted ->
                    FenceAcquireResult.BackendFailure(IllegalStateException("fencing sequence exhausted"))

                is FencingAcquireResult.IntegrityFailure ->
                    FenceAcquireResult.BackendFailure(IllegalStateException("fencing state integrity failure"))

                is FencingAcquireResult.BackendFailure ->
                    FenceAcquireResult.BackendFailure(IllegalStateException("fencing backend unavailable"))
            }
        } catch (error: Exception) {
            FenceAcquireResult.BackendFailure(error)
        }
    }

    fun renew(lease: FencingLease, ttl: Duration): FenceRenewResult {
        val redisLease = lease as? RedisJobFencingLease
            ?: return FenceRenewResult.BackendFailure(IllegalArgumentException("lease was not created by this adapter"))
        if (redisLease.token.epoch != namespaceEpoch.value) {
            return FenceRenewResult.BackendFailure(
                IllegalArgumentException("fencing lease epoch does not match the configured ordering domain"),
            )
        }
        return try {
            when (redisLease.backendLease.renew(redisLease.backendOwnerId, redisLease.backendToken, ttl)) {
                FencingRenewResult.Renewed -> FenceRenewResult.Renewed(redisLease.token)
                FencingRenewResult.Lost,
                FencingRenewResult.OwnershipMismatch,
                -> FenceRenewResult.OwnershipLost

                is FencingRenewResult.IntegrityFailure ->
                    FenceRenewResult.BackendFailure(IllegalStateException("fencing state integrity failure"))

                is FencingRenewResult.BackendFailure ->
                    FenceRenewResult.BackendFailure(IllegalStateException("fencing backend unavailable"))
            }
        } catch (error: Exception) {
            FenceRenewResult.BackendFailure(error)
        }
    }

    fun release(lease: FencingLease): FenceReleaseResult {
        val redisLease = lease as? RedisJobFencingLease
            ?: return FenceReleaseResult.BackendFailure(IllegalArgumentException("lease was not created by this adapter"))
        if (redisLease.token.epoch != namespaceEpoch.value) {
            return FenceReleaseResult.BackendFailure(
                IllegalArgumentException("fencing lease epoch does not match the configured ordering domain"),
            )
        }
        return try {
            when (redisLease.backendLease.release(redisLease.backendOwnerId, redisLease.backendToken)) {
                FencingReleaseResult.Released -> FenceReleaseResult.Released
                FencingReleaseResult.Lost,
                FencingReleaseResult.OwnershipMismatch,
                -> FenceReleaseResult.OwnershipLost

                is FencingReleaseResult.IntegrityFailure ->
                    FenceReleaseResult.BackendFailure(IllegalStateException("fencing state integrity failure"))

                is FencingReleaseResult.BackendFailure ->
                    FenceReleaseResult.BackendFailure(IllegalStateException("fencing backend unavailable"))
            }
        } catch (error: Exception) {
            FenceReleaseResult.BackendFailure(error)
        }
    }

    internal fun resourceNameFor(conflictKey: ConflictKey): String =
        "resource-${UUID.nameUUIDFromBytes(conflictKey.value.toByteArray(StandardCharsets.UTF_8))}"
            .replace("-", "")

    private fun leaseFor(conflictKey: ConflictKey): LettuceFencingLease =
        LettuceFencingLease(
            connection,
            LettuceFencingLeaseConfig(
                namespace = namespace,
                resourceName = resourceNameFor(conflictKey),
                epoch = namespaceEpoch.value,
            ),
        )

    private fun lease(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        backendOwnerId: LettuceFencingOwnerId,
        backendToken: LettuceFencingToken,
    ): RedisJobFencingLease =
        RedisJobFencingLease(
            conflictKey = conflictKey,
            ownerId = ownerId,
            token = FencingToken(backendToken.epoch, backendToken.sequence),
            backendLease = leaseFor(conflictKey),
            backendOwnerId = backendOwnerId,
            backendToken = backendToken,
            adapter = this,
        )

    private companion object {
        private const val DEFAULT_NAMESPACE = "job-safety"
    }
}
