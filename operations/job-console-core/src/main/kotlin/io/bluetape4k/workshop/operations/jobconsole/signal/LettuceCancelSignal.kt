package io.bluetape4k.workshop.operations.jobconsole.signal

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.util.UUID

class LettuceCancelSignal(
    redisUri: String,
) : CancelSignal, AutoCloseable {
    private val client: RedisClient = RedisClient.create(redisUri)
    private val connection: StatefulRedisConnection<String, String> = client.connect()

    override fun publish(jobId: UUID): CancelSignalResult =
        CancelSignalResult(connection.sync().publish(CHANNEL, jobId.toString()) > 0)

    override fun isAvailable(): Boolean = runCatching { connection.sync().ping() == "PONG" }.getOrDefault(false)

    override fun close() {
        connection.close()
        client.shutdown()
    }

    companion object {
        const val CHANNEL: String = "job-console:cancel"
    }
}
