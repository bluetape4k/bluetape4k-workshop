package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.bucket4j.addBandwidth
import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.RateLimiter
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.logging.KLogging
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
/** Redis를 startup authority로 만들지 않고 lazy하고 재연결 가능한 Lettuce command connection을 소유합니다. */
internal class TicketRedisResources private constructor(
    /**
     * `client`은 콘서트 티켓 플래시 세일에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    val client: RedisClient,
): AutoCloseable {

    companion object: KLogging() {
        /**
         * open은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         * @param properties 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
         */
        fun open(properties: TicketRedisProperties): TicketRedisResources {
            val uri = RedisURI.create(properties.uri).apply { timeout = properties.commandTimeout }
            return TicketRedisResources(LettuceClients.clientOf(uri))
        }
    }

    /**
     * `connectionLock`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val connectionLock = ReentrantLock()
    /**
     * `closed`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val closed = AtomicBoolean()

    @Volatile
    /**
     * `connection`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private var connection: StatefulRedisConnection<String, String>? = null

    /**
     * commands은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
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

    /**
     * close은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection?.close()
        LettuceClients.shutdown(client)
    }
}

/** Redis boundary에서만 Bluetape4k Lettuce, Lua, Bucket4j adapter를 연결합니다. */
/** Redis boundary에서만 Bluetape4k Lettuce, Lua, Bucket4j adapter를 연결합니다. */
@Configuration(proxyBeanMethods = false)
internal class TicketRedisConfiguration {
    @Bean(destroyMethod = "close")
    /**
     * ticketRedisResources은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param properties 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun ticketRedisResources(properties: TicketProperties): TicketRedisResources =
        TicketRedisResources.open(properties.redis)

    @Bean
    /**
     * ticketMultiKeyLease은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param resources 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun ticketMultiKeyLease(resources: TicketRedisResources): MultiKeyLeaseAdapter =
        MultiKeyLeaseAdapter(resources::commands)

    @Bean
    /**
     * ticketForegroundLeaseGate은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param lease 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun ticketForegroundLeaseGate(lease: MultiKeyLeaseAdapter): ForegroundLeaseGate =
        ForegroundLeaseGate(lease)

    @Bean("ticketRateLimiter")
    /**
     * ticketRateLimiter은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun ticketRateLimiter(
        resources: TicketRedisResources,
        properties: TicketProperties,
    ): RateLimiter<String> {
        val redis = properties.redis
        val manager = lettuceBasedProxyManagerOf(resources.client) {}
        val configuration = bucketConfiguration {
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
