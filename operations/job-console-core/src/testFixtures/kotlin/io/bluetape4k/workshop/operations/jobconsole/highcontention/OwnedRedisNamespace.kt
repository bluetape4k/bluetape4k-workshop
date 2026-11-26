package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.workshop.shared.redis.OwnedRedisDeletionResult as SharedOwnedRedisDeletionResult
import io.bluetape4k.workshop.shared.redis.OwnedRedisKeyCommands as SharedOwnedRedisKeyCommands
import io.bluetape4k.workshop.shared.redis.OwnedRedisNamespace as SharedOwnedRedisNamespace
import io.bluetape4k.workshop.shared.redis.OwnedRedisScanPage as SharedOwnedRedisScanPage
import io.bluetape4k.workshop.shared.redis.OwnedRedisWriterBarrier as SharedOwnedRedisWriterBarrier
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands

typealias OwnedRedisScanPage = SharedOwnedRedisScanPage
typealias OwnedRedisKeyCommands = SharedOwnedRedisKeyCommands
typealias OwnedRedisWriterBarrier = SharedOwnedRedisWriterBarrier
typealias OwnedRedisDeletionResult = SharedOwnedRedisDeletionResult

/**
 * Job Console이 허용하는 implementation policy만 공통 Redis fixture에 전달한다.
 */
object OwnedRedisNamespace {

    private val JOB_IMPLEMENTATIONS = setOf("job-core", "job-spring", "job-ktor")

    fun parse(
        namespace: String,
        deleteUpperBound: Int,
        commands: RedisCommands<String, String>,
        maximumScanIterations: Int = 1_024,
    ): SharedOwnedRedisNamespace =
        parse(
            namespace = namespace,
            deleteUpperBound = deleteUpperBound,
            commands = LettuceOwnedRedisKeyCommands(commands),
            maximumScanIterations = maximumScanIterations,
        )

    fun parse(
        namespace: String,
        deleteUpperBound: Int,
        commands: OwnedRedisKeyCommands,
        maximumScanIterations: Int = 1_024,
    ): SharedOwnedRedisNamespace =
        SharedOwnedRedisNamespace.parse(
            namespace = namespace,
            deleteUpperBound = deleteUpperBound,
            commands = commands,
            maximumScanIterations = maximumScanIterations,
            implementationPolicy = JOB_IMPLEMENTATIONS::contains,
        )
}

private class LettuceOwnedRedisKeyCommands(
    private val commands: RedisCommands<String, String>,
) : OwnedRedisKeyCommands {

    override fun scan(
        cursor: String,
        pattern: String,
        count: Long,
    ): OwnedRedisScanPage {
        val page = commands.scan(
            ScanCursor.of(cursor),
            ScanArgs().match(pattern).limit(count),
        )
        return OwnedRedisScanPage(
            nextCursor = page.cursor,
            keys = page.keys,
        )
    }

    override fun unlink(keys: List<String>): Long =
        commands.unlink(*keys.toTypedArray())
}
