package io.bluetape4k.workshop.bucket4j.advanced.config

import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.AsyncBucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ClientSideConfig
import io.github.bucket4j.distributed.proxy.ExecutionStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * 서로 다른 세 가지 rate-limit 전략을 설정합니다.
 *
 * - **IP 기반**(`ipRateLimiter`): client IP 주소별로 제한합니다.
 *   한 출발지에서 익명 endpoint를 과도하게 호출하지 못하게 할 때 적합합니다.
 * - **User 기반**(`userRateLimiter`): 인증된 user ID별로 제한합니다.
 *   인증 사용자는 익명 IP보다 높은 한도를 받습니다.
 * - **Combined**(`combinedRateLimiter`): `"ip:userId"` 복합 bucket key를 사용합니다.
 *   (IP, userId) 쌍마다 하나의 bucket을 유지하므로, 사용자는 자신의 combined quota만 소진하고
 *   전역 IP quota나 다른 사용자의 quota를 소진하지 않습니다. 반대 방향도 동일합니다.
 *
 * 세 전략은 같은 [ProxyManager]와 같은 Redis instance를 공유하지만,
 * 각 limiter는 capacity가 다른 별도 [BucketConfiguration]을 사용합니다.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    // ------------------------------------------------------------------
    // 공유 Redis proxy manager
    // ------------------------------------------------------------------

    @Bean
    fun proxyManager(lettuceClient: RedisClient): LettuceBasedProxyManager<ByteArray> {
        return lettuceBasedProxyManagerOf(lettuceClient) {
            ClientSideConfig.getDefault()
                .withExpirationAfterWriteStrategy(
                    ExpirationAfterWriteStrategy
                        .basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(90))
                )
                .withExecutionStrategy(ExecutionStrategy.background(VirtualThreadExecutor))
        }
    }

    // ------------------------------------------------------------------
    // 전략별 BucketConfiguration
    // ------------------------------------------------------------------

    /** IP 기반 bucket입니다. 익명 traffic에 10초당 20 token, 분당 100 token을 허용합니다. */
    @Bean
    @Qualifier("ipBucketConfiguration")
    fun ipBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(20).refillIntervally(20, Duration.ofSeconds(10)) }
        addLimit { it.capacity(100).refillGreedy(10, Duration.ofMinutes(1)) }
    }

    /** User 기반 bucket입니다. 인증 traffic에 10초당 50 token, 분당 200 token을 허용합니다. */
    @Bean
    @Qualifier("userBucketConfiguration")
    fun userBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(50).refillIntervally(50, Duration.ofSeconds(10)) }
        addLimit { it.capacity(200).refillGreedy(20, Duration.ofMinutes(1)) }
    }

    /** Combined(IP+userId) bucket입니다. 10초당 10 token, 분당 50 token을 허용합니다. */
    @Bean
    @Qualifier("combinedBucketConfiguration")
    fun combinedBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(10).refillIntervally(10, Duration.ofSeconds(10)) }
        addLimit { it.capacity(50).refillGreedy(5, Duration.ofMinutes(1)) }
    }

    // ------------------------------------------------------------------
    // 전략별 AsyncBucketProxyProvider
    // ------------------------------------------------------------------

    @Bean
    @Qualifier("ipBucketProxyProvider")
    fun ipBucketProxyProvider(
        proxyManager: ProxyManager<ByteArray>,
        @Qualifier("ipBucketConfiguration") ipBucketConfiguration: BucketConfiguration,
    ): AsyncBucketProxyProvider =
        AsyncBucketProxyProvider(proxyManager.asAsync(), ipBucketConfiguration)

    @Bean
    @Qualifier("userBucketProxyProvider")
    fun userBucketProxyProvider(
        proxyManager: ProxyManager<ByteArray>,
        @Qualifier("userBucketConfiguration") userBucketConfiguration: BucketConfiguration,
    ): AsyncBucketProxyProvider =
        AsyncBucketProxyProvider(proxyManager.asAsync(), userBucketConfiguration)

    @Bean
    @Qualifier("combinedBucketProxyProvider")
    fun combinedBucketProxyProvider(
        proxyManager: ProxyManager<ByteArray>,
        @Qualifier("combinedBucketConfiguration") combinedBucketConfiguration: BucketConfiguration,
    ): AsyncBucketProxyProvider =
        AsyncBucketProxyProvider(proxyManager.asAsync(), combinedBucketConfiguration)

    // ------------------------------------------------------------------
    // 전략별 DistributedSuspendRateLimiter
    // ------------------------------------------------------------------

    @Bean
    @Qualifier("ipRateLimiter")
    fun ipRateLimiter(
        @Qualifier("ipBucketProxyProvider") ipBucketProxyProvider: AsyncBucketProxyProvider,
    ): DistributedSuspendRateLimiter = DistributedSuspendRateLimiter(ipBucketProxyProvider)

    @Bean
    @Qualifier("userRateLimiter")
    fun userRateLimiter(
        @Qualifier("userBucketProxyProvider") userBucketProxyProvider: AsyncBucketProxyProvider,
    ): DistributedSuspendRateLimiter = DistributedSuspendRateLimiter(userBucketProxyProvider)

    @Bean
    @Qualifier("combinedRateLimiter")
    fun combinedRateLimiter(
        @Qualifier("combinedBucketProxyProvider") combinedBucketProxyProvider: AsyncBucketProxyProvider,
    ): DistributedSuspendRateLimiter = DistributedSuspendRateLimiter(combinedBucketProxyProvider)
}
