package io.bluetape4k.workshop.leader.jobsafety.coordination.redis

import io.bluetape4k.redis.lettuce.script.RedisScript
import io.bluetape4k.redis.lettuce.script.RedisScriptRunner
import io.bluetape4k.support.requireGt
import io.bluetape4k.workshop.leader.jobsafety.coordination.FenceAcquireResult
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLease
import io.bluetape4k.workshop.leader.jobsafety.coordination.FencingLeasePort
import io.bluetape4k.workshop.leader.jobsafety.domain.ConflictKey
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingOwnerId
import io.bluetape4k.workshop.leader.jobsafety.domain.FencingToken
import io.bluetape4k.workshop.leader.jobsafety.domain.NamespaceEpoch
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

data class JobFencingKeys(
    val lease: String,
    val counter: String,
    val epoch: String,
) {
    fun asAcquireKeys(): Array<String> = arrayOf(lease, counter, epoch)

    fun asOwnershipKeys(): Array<String> = arrayOf(lease, epoch)
}

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

class RedisJobFencingLeaseAdapter(
    private val commandsProvider: () -> RedisCommands<String, String>,
    private val namespaceEpoch: NamespaceEpoch,
) : FencingLeasePort {
    constructor(
        commands: RedisCommands<String, String>,
        namespaceEpoch: NamespaceEpoch,
    ) : this({ commands }, namespaceEpoch)

    override fun acquire(
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
        ttl: Duration,
    ): FenceAcquireResult {
        ttl.requireGt(Duration.ZERO, "ttl")
        return try {
            val response =
                RedisScriptRunner.run<String>(
                    commandsProvider(),
                    JobFencingScripts.acquire,
                    ScriptOutputType.VALUE,
                    keysFor(conflictKey).asAcquireKeys(),
                    ownerWire(ownerId),
                    ttl.toMillis().toString(),
                    namespaceEpoch.value.toString(),
                )
            parseAcquire(response, conflictKey, ownerId)
        } catch (e: Exception) {
            FenceAcquireResult.BackendFailure(e)
        }
    }

    fun renew(lease: FencingLease, ttl: Duration): FenceRenewResult {
        ttl.requireGt(Duration.ZERO, "ttl")
        return try {
            when (
                runInteger(
                    script = JobFencingScripts.renew,
                    lease = lease,
                    ttl.toMillis().toString(),
                    namespaceEpoch.value.toString(),
                )
            ) {
                1L -> FenceRenewResult.Renewed(lease.token)
                0L -> FenceRenewResult.OwnershipLost
                else -> FenceRenewResult.BackendFailure(RedisException("job fencing namespace mismatch"))
            }
        } catch (e: Exception) {
            FenceRenewResult.BackendFailure(e)
        }
    }

    fun release(lease: FencingLease): FenceReleaseResult =
        try {
            when (
                runInteger(
                    script = JobFencingScripts.release,
                    lease = lease,
                    namespaceEpoch.value.toString(),
                )
            ) {
                1L -> FenceReleaseResult.Released
                0L -> FenceReleaseResult.OwnershipLost
                else -> FenceReleaseResult.BackendFailure(RedisException("job fencing namespace mismatch"))
            }
        } catch (e: Exception) {
            FenceReleaseResult.BackendFailure(e)
        }

    internal fun keysFor(conflictKey: ConflictKey): JobFencingKeys {
        val resourceTag = digest(conflictKey.value).take(24)
        val prefix = "job-fence:{$resourceTag}"
        return JobFencingKeys(
            lease = "$prefix:lease",
            counter = "$prefix:counter",
            epoch = "$prefix:epoch",
        )
    }

    private fun parseAcquire(
        response: String,
        conflictKey: ConflictKey,
        ownerId: FencingOwnerId,
    ): FenceAcquireResult =
        when (response) {
            CONTENDED -> FenceAcquireResult.Contended
            EPOCH_MISMATCH -> FenceAcquireResult.BackendFailure(RedisException("job fencing namespace mismatch"))
            MALFORMED_LEASE -> FenceAcquireResult.BackendFailure(RedisException("malformed job fencing lease"))
            HISTORY_UNSAFE -> FenceAcquireResult.BackendFailure(RedisException("job fencing counter history is unsafe"))
            else -> {
                val parts = response.split(RESPONSE_SEPARATOR, limit = 2)
                if (parts.size != 2) {
                    return FenceAcquireResult.BackendFailure(RedisException("malformed job fencing response"))
                }
                val token =
                    parts[1].toLongOrNull()?.takeIf { it > 0L }?.let(::FencingToken)
                        ?: return FenceAcquireResult.BackendFailure(RedisException("invalid job fencing token"))
                val lease = RedisJobFencingLease(conflictKey, ownerId, token, this)
                when (parts[0]) {
                    ACQUIRED -> FenceAcquireResult.Acquired(lease)
                    OWNED -> FenceAcquireResult.AlreadyOwned(lease)
                    else -> FenceAcquireResult.BackendFailure(RedisException("unknown job fencing response"))
                }
            }
        }

    private fun runInteger(
        script: RedisScript,
        lease: FencingLease,
        vararg trailingArgs: String,
    ): Long =
        RedisScriptRunner.run(
            commandsProvider(),
            script,
            ScriptOutputType.INTEGER,
            keysFor(lease.conflictKey).asOwnershipKeys(),
            ownerWire(lease.ownerId),
            lease.token.value.toString(),
            *trailingArgs,
        )

    private fun ownerWire(ownerId: FencingOwnerId): String = digest("job-fence-owner:${ownerId.value}")

    private fun digest(value: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
        )

    companion object {
        private const val CONTENDED = "C"
        private const val EPOCH_MISMATCH = "E"
        private const val MALFORMED_LEASE = "X"
        private const val HISTORY_UNSAFE = "H"
        private const val ACQUIRED = "A"
        private const val OWNED = "O"
        private const val RESPONSE_SEPARATOR = '|'
    }
}
