package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.redis.lettuce.LettuceClients
import io.bluetape4k.workshop.commerce.ticket.redis.ForegroundLeaseGate
import io.bluetape4k.workshop.commerce.ticket.redis.MultiKeyLeaseAdapter
import io.github.bucket4j.Bandwidth
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Redis를 startup authority로 만들지 않고 lazy하고 재연결 가능한 Lettuce command connection을 소유합니다. */
internal class TicketRedisResources private constructor(
    val client: RedisClient,
) : AutoCloseable {
    private val connectionLock = ReentrantLock()
    private val closed = AtomicBoolean()

    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null

    fun commands(): RedisCommands<String, String> {
        check(!closed.get()) { "ticket Redis resources are closed" }
        val available = connection
        if (available != null && available.isOpen) return available.sync()
        return connectionLock.withLock {
            val current = connection
            if (current != null && current.isOpen) {
                current.sync()
            } else {
                LettuceClients.connect(client).also { connection = it }.sync()
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.close()
        LettuceClients.shutdown(client)
    }

    companion object {
        fun open(properties: TicketRedisProperties): TicketRedisResources {
            val uri = RedisURI.create(properties.uri).apply { timeout = properties.commandTimeout }
            return TicketRedisResources(LettuceClients.clientOf(uri))
        }
    }
}

/** Redis boundary에서만 Bluetape4k Lettuce, Lua, Bucket4j adapter를 연결합니다. */
@Configuration(proxyBeanMethods = false)
internal class TicketRedisConfiguration {
    @Bean(destroyMethod = "close")
    fun ticketRedisResources(properties: TicketProperties): TicketRedisResources =
        TicketRedisResources.open(properties.redis)

    @Bean
    fun ticketMultiKeyLease(resources: TicketRedisResources): MultiKeyLeaseAdapter =
        MultiKeyLeaseAdapter(resources::commands)

    @Bean
    fun ticketForegroundLeaseGate(lease: MultiKeyLeaseAdapter): ForegroundLeaseGate =
        ForegroundLeaseGate(lease)

    @Bean("ticketRateLimiter")
    fun ticketRateLimiter(
        resources: TicketRedisResources,
        properties: TicketProperties,
    ): RateLimiter<String> {
        val redis = properties.redis
        val manager = lettuceBasedProxyManagerOf(resources.client) {}
        val configuration =
            bucketConfiguration {
                addBandwidth {
                    Bandwidth.builder()
                        .capacity(redis.rateLimitCapacity)
                        .refillGreedy(redis.rateLimitCapacity, redis.rateLimitPeriod)
                        .build()
                }
            }
        return DistributedRateLimiter(
            BucketProxyProvider(manager, configuration, keyPrefix = "ticket:rate:"),
        )
    }
}
