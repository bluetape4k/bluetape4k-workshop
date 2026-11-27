package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.redis.lettuce.LettuceClients
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection

/** upstream tuned [LettuceClients]를 사용하는 Redis client/connection factory입니다. */
class LettuceRedisClientFactory {

    fun clientOf(endpoint: RedisEndpoint): RedisClient = LettuceClients.clientOf(endpoint.url)

    fun connect(client: RedisClient): StatefulRedisConnection<String, String> =
        LettuceClients.connect(client)

    /** cached connection과 client를 upstream shutdown 계약으로 정리합니다. */
    fun close(client: RedisClient) {
        LettuceClients.shutdown(client)
    }
}
