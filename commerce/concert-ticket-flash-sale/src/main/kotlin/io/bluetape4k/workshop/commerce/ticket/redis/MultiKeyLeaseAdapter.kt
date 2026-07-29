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

/** 하나의 Redis Cluster hash slot을 공유해야 하는 두 Redis key입니다. */
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

/** opaque하고 versioned된 PRF output입니다. token은 raw identity나 request key를 포함하면 안 됩니다. */
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

/** 현재 owner를 먼저 두고, response-loss recovery를 위해 보존한 read-key owner를 뒤에 둡니다. */
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

/** #1065에서 추적하는 재사용 가능한 bluetape4k-lettuce feature로 나중에 교체할 수 있는 좁은 seam입니다. */
fun interface MultiKeyLeasePort {
    fun acquire(request: LeaseRequest): LeaseDecision
}

/** atomic Redis Lua script로 구현한 application-owned two-key lease입니다. */
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

/** new-purchase admission error는 안정적이며 Redis 세부사항과 의도적으로 분리합니다. */
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

/** 새 foreground purchase admission에만 fail-closed 동작을 적용합니다. */
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
