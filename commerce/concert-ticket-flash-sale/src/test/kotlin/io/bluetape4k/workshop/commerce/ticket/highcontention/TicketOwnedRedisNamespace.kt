package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import java.util.TreeSet

data class TicketOwnedRedisScanPage(
    val nextCursor: String,
    val keys: List<String>,
)

interface TicketOwnedRedisKeyCommands {

    fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): TicketOwnedRedisScanPage

    fun unlink(keys: List<String>): Long
}

fun interface TicketOwnedRedisWriterBarrier {

    fun pause(): AutoCloseable

    companion object {
        val NONE: TicketOwnedRedisWriterBarrier = TicketOwnedRedisWriterBarrier { AutoCloseable {} }
    }
}

data class TicketOwnedRedisDeletionResult(
    val deletedKeys: List<String>,
)

class TicketOwnedRedisNamespace private constructor(
    private val namespace: String,
    private val deleteUpperBound: Int,
    private val commands: TicketOwnedRedisKeyCommands,
    private val maximumScanIterations: Int,
) {

    fun owns(key: String): Boolean {
        if (!key.startsWith(namespace)) {
            return false
        }
        val suffixComponents = key.removePrefix(namespace).split(':')
        return suffixComponents.size == OWNED_KEY_COMPONENT_COUNT &&
                suffixComponents.all(OWNER_COMPONENT_PATTERN::matches)
    }

    fun deleteOwnedKeys(
        writerBarrier: TicketOwnedRedisWriterBarrier,
    ): TicketOwnedRedisDeletionResult =
        writerBarrier.pause().use {
            val candidates = scanAll()
            check(candidates.all(::owns)) {
                "Redis SCAN returned a key outside the owned namespace."
            }
            check(candidates.size <= deleteUpperBound) {
                "Owned Redis key count[${candidates.size}] exceeds delete upper bound[$deleteUpperBound]."
            }

            if (candidates.isNotEmpty()) {
                val unlinkedKeyCount = commands.unlink(candidates)
                check(unlinkedKeyCount == candidates.size.toLong()) {
                    "Redis UNLINK removed $unlinkedKeyCount of ${candidates.size} owned keys."
                }
            }

            check(scanAll().isEmpty()) {
                "Owned Redis namespace did not converge after UNLINK."
            }
            TicketOwnedRedisDeletionResult(candidates)
        }

    private fun scanAll(): List<String> {
        val candidates = TreeSet<String>()
        var cursor = INITIAL_CURSOR
        var scanIterations = 0

        do {
            check(++scanIterations <= maximumScanIterations) {
                "Redis SCAN did not converge within $maximumScanIterations iterations."
            }
            val page = commands.scan(
                cursor = cursor,
                pattern = "$namespace*",
                count = deleteUpperBound.toLong(),
            )
            candidates += page.keys
            cursor = page.nextCursor
        } while (cursor != INITIAL_CURSOR)

        return candidates.toList()
    }

    companion object {
        private const val INITIAL_CURSOR = "0"
        private const val NAMESPACE_COMPONENT_COUNT = 5
        private const val OWNED_KEY_COMPONENT_COUNT = 2
        private val OWNER_COMPONENT_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: RedisCommands<String, String>,
            maximumScanIterations: Int = 1_024,
        ): TicketOwnedRedisNamespace =
            parse(
                namespace = namespace,
                deleteUpperBound = deleteUpperBound,
                commands = LettuceTicketOwnedRedisKeyCommands(commands),
                maximumScanIterations = maximumScanIterations,
            )

        fun parse(
            namespace: String,
            deleteUpperBound: Int,
            commands: TicketOwnedRedisKeyCommands,
            maximumScanIterations: Int = 1_024,
        ): TicketOwnedRedisNamespace {
            val validatedNamespace = namespace.requireNotBlank("namespace")
            require(validatedNamespace.endsWith(':')) {
                "namespace[$validatedNamespace] must end with a terminal delimiter."
            }
            val components = validatedNamespace.dropLast(1).split(':')
            components.size.requireEquals(NAMESPACE_COMPONENT_COUNT, "namespaceComponentCount")
            components[0].requireEquals("hc", "namespaceRoot")
            components[1].requireEquals("v1", "namespaceVersion")
            require(OWNER_COMPONENT_PATTERN.matches(components[2])) {
                "runId[${components[2]}] must be a valid namespace component."
            }
            components[3].requireEquals("ticket-spring", "implementation")
            components[4].requireEquals("redis-key-loss", "profileId")

            return TicketOwnedRedisNamespace(
                namespace = validatedNamespace,
                deleteUpperBound = deleteUpperBound.requirePositiveNumber("deleteUpperBound"),
                commands = commands,
                maximumScanIterations = maximumScanIterations.requirePositiveNumber("maximumScanIterations"),
            )
        }
    }
}

private class LettuceTicketOwnedRedisKeyCommands(
    private val commands: RedisCommands<String, String>,
) : TicketOwnedRedisKeyCommands {

    override fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): TicketOwnedRedisScanPage {
        val page = commands.scan(
            ScanCursor.of(cursor),
            ScanArgs().match(pattern).limit(count),
        )
        return TicketOwnedRedisScanPage(
            nextCursor = page.cursor,
            keys = page.keys,
        )
    }

    override fun unlink(keys: List<String>): Long =
        commands.unlink(*keys.toTypedArray())
}
