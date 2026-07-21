package io.bluetape4k.workshop.commerce.ticket.redis

import io.bluetape4k.redis.lettuce.script.RedisScript
import io.bluetape4k.redis.lettuce.script.RedisScriptRunner
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireNotEmpty
import io.lettuce.core.RedisException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import java.io.Serial
import java.io.Serializable
import java.time.Duration

/** Two Redis keys that must share one Redis Cluster hash slot. */
data class LeaseKeys(
    val ip: String,
    val user: String,
) : Serializable {
    init {
        (ip != user).requireEquals(true, "leaseKeys.areDistinct")
        hashTag(ip).requireEquals(hashTag(user), "leaseKeys.hashTag")
    }

    fun asArray(): Array<String> = arrayOf(ip, user)

    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L

        private val HASH_TAG = Regex("\\{([^{}]+)}")

        private fun hashTag(key: String): String =
            requireNotNull(HASH_TAG.find(key)?.groupValues?.get(1)) {
                "lease key must contain a non-empty Redis hash tag"
            }
    }
}

/** Opaque, versioned PRF output. The token must not contain a raw identity or request key. */
data class LeaseOwner(
    val version: Int,
    val token: String,
) : Serializable {
    init {
        version.requireGt(0, "version")
        token.length.requireEquals(TOKEN_LENGTH, "token.length")
        token.all { it.isLetterOrDigit() || it == '-' || it == '_' }.requireEquals(true, "token.isBase64Url")
    }

    val wireValue: String = "v$version:$token"

    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
        private const val TOKEN_LENGTH = 43
    }
}

/** Current owner first, followed by retained read-key owners for response-loss recovery. */
data class LeaseRequest(
    val keys: LeaseKeys,
    val ownerCandidates: List<LeaseOwner>,
    val ttl: Duration,
) : Serializable {
    init {
        ownerCandidates.requireNotEmpty("ownerCandidates")
        ownerCandidates.map { it.version }.distinct().size
            .requireEquals(ownerCandidates.size, "ownerCandidates.distinctVersions")
        ttl.requireGt(Duration.ZERO, "ttl")
    }

    val currentOwner: LeaseOwner get() = ownerCandidates.first()

    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

sealed interface LeaseDecision : Serializable {
    data class Acquired(val version: Int) : LeaseDecision {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    data class AlreadyOwned(val version: Int) : LeaseDecision {
        companion object {
            @Serial
            private const val serialVersionUID: Long = 1L
        }
    }

    data object Busy : LeaseDecision {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Narrow seam that can later be replaced by the reusable bluetape4k-lettuce feature tracked in #1065. */
fun interface MultiKeyLeasePort {
    fun acquire(request: LeaseRequest): LeaseDecision
}

/** Application-owned two-key lease implemented with atomic Redis Lua scripts. */
class MultiKeyLeaseAdapter(
    private val commandsProvider: () -> RedisCommands<String, String>,
) : MultiKeyLeasePort {
    constructor(commands: RedisCommands<String, String>) : this({ commands })

    override fun acquire(request: LeaseRequest): LeaseDecision {
        val response =
            RedisScriptRunner.run<String>(
                commandsProvider(),
                ACQUIRE,
                ScriptOutputType.VALUE,
                request.keys.asArray(),
                request.currentOwner.wireValue,
                request.ttl.toMillis().toString(),
                *request.ownerCandidates.map { it.wireValue }.toTypedArray(),
            )
        if (response == BUSY) return LeaseDecision.Busy

        val (status, wireOwner) = response.split(RESPONSE_SEPARATOR, limit = 2).requirePair()
        val owner = request.ownerCandidates.singleOrNull { it.wireValue == wireOwner }
            ?: throw RedisException("Redis returned an unknown lease owner")
        return when (status) {
            ACQUIRED -> LeaseDecision.Acquired(owner.version)
            OWNED -> LeaseDecision.AlreadyOwned(owner.version)
            else -> throw RedisException("Redis returned an unknown lease status")
        }
    }

    fun renew(request: LeaseRequest): Boolean =
        runIntegerScript(RENEW, request) == 1L

    fun release(request: LeaseRequest): Boolean =
        runIntegerScript(RELEASE, request) == 1L

    private fun runIntegerScript(
        script: RedisScript,
        request: LeaseRequest,
    ): Long =
        RedisScriptRunner.run(
            commandsProvider(),
            script,
            ScriptOutputType.INTEGER,
            request.keys.asArray(),
            request.ttl.toMillis().toString(),
            *request.ownerCandidates.map { it.wireValue }.toTypedArray(),
        )

    private fun List<String>.requirePair(): Pair<String, String> {
        if (size != 2) throw RedisException("Redis returned a malformed lease response")
        return this[0] to this[1]
    }

    companion object {
        private const val BUSY = "B"
        private const val ACQUIRED = "A"
        private const val OWNED = "O"
        private const val RESPONSE_SEPARATOR = '|'

        private val ACQUIRE =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if value then
                    if selected and selected ~= value then return 'B' end
                    selected = value
                  end
                end

                local status = 'O'
                if not selected then
                  selected = ARGV[1]
                  status = 'A'
                else
                  local retained = false
                  for index = 3, #ARGV do
                    if ARGV[index] == selected then retained = true break end
                  end
                  if not retained then return 'B' end
                end

                for _, key in ipairs(KEYS) do
                  redis.call('SET', key, selected, 'PX', ARGV[2])
                end
                return status .. '|' .. selected
                """.trimIndent(),
            )

        private val RENEW =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if not value then return 0 end
                  if selected and selected ~= value then return 0 end
                  selected = value
                end
                local owned = false
                for index = 2, #ARGV do
                  if ARGV[index] == selected then owned = true break end
                end
                if not owned then return 0 end
                for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, ARGV[1]) end
                return 1
                """.trimIndent(),
            )

        private val RELEASE =
            RedisScript(
                """
                local selected = nil
                for _, key in ipairs(KEYS) do
                  local value = redis.call('GET', key)
                  if value then
                    if selected and selected ~= value then return 0 end
                    selected = value
                  end
                end
                if not selected then return 0 end
                local owned = false
                for index = 2, #ARGV do
                  if ARGV[index] == selected then owned = true break end
                end
                if not owned then return 0 end
                redis.call('DEL', unpack(KEYS))
                return 1
                """.trimIndent(),
            )
    }
}

/** New-purchase admission errors are stable and intentionally separate from Redis details. */
class AdmissionTemporarilyUnavailable(cause: Throwable) :
    IllegalStateException("admission_temporarily_unavailable", cause) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

class PurchaseApprovalInProgress : IllegalStateException("purchase_approval_in_progress") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/** Applies fail-closed behavior only to new foreground purchase admission. */
class ForegroundLeaseGate(
    private val lease: MultiKeyLeasePort,
) {
    fun acquire(request: LeaseRequest): LeaseDecision =
        try {
            when (val decision = lease.acquire(request)) {
                LeaseDecision.Busy -> throw PurchaseApprovalInProgress()
                else -> decision
            }
        } catch (failure: RedisException) {
            log.warn(failure) { "foreground_lease_unavailable" }
            throw AdmissionTemporarilyUnavailable(failure)
        }

    companion object : KLogging()
}
