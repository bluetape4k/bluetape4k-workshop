package io.bluetape4k.workshop.commerce.ticket.highcontention

import io.bluetape4k.workshop.shared.redis.OwnedRedisDeletionResult as SharedOwnedRedisDeletionResult
import io.bluetape4k.workshop.shared.redis.OwnedRedisKeyCommands as SharedOwnedRedisKeyCommands
import io.bluetape4k.workshop.shared.redis.OwnedRedisNamespace as SharedOwnedRedisNamespace
import io.bluetape4k.workshop.shared.redis.OwnedRedisScanPage as SharedOwnedRedisScanPage
import io.bluetape4k.workshop.shared.redis.OwnedRedisWriterBarrier as SharedOwnedRedisWriterBarrier
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands

typealias TicketOwnedRedisScanPage = SharedOwnedRedisScanPage
typealias TicketOwnedRedisKeyCommands = SharedOwnedRedisKeyCommands
typealias TicketOwnedRedisWriterBarrier = SharedOwnedRedisWriterBarrier
typealias TicketOwnedRedisDeletionResult = SharedOwnedRedisDeletionResult

/**
 * Ticket Flash Sale가 허용하는 implementation policy만 공통 Redis fixture에 전달한다.
 */
object TicketOwnedRedisNamespace {

    fun parse(
        namespace: String,
        deleteUpperBound: Int,
        commands: RedisCommands<String, String>,
        maximumScanIterations: Int = 1_024,
    ): SharedOwnedRedisNamespace =
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
    ): SharedOwnedRedisNamespace =
        SharedOwnedRedisNamespace.parse(
            namespace = namespace,
            deleteUpperBound = deleteUpperBound,
            commands = commands,
            maximumScanIterations = maximumScanIterations,
            implementationPolicy = setOf("ticket-spring")::contains,
        )
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
