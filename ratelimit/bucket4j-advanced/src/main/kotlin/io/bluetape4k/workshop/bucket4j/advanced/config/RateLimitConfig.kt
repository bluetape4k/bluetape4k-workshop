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
 * Configures three distinct rate-limit strategies:
 *
 * - **IP-based** (`ipRateLimiter`): limits by client IP address.
 *   Suitable for anonymous endpoints that should not be hammered from one origin.
 * - **User-based** (`userRateLimiter`): limits by authenticated user ID.
 *   Authenticated users get a higher ceiling than anonymous IPs.
 * - **Combined** (`combinedRateLimiter`): uses a composite `"ip:userId"` bucket key.
 *   A single bucket is maintained per (IP, userId) pair, so a user can exhaust only
 *   their own combined quota — not the global IP quota — and vice-versa.
 *
 * All three share the same [ProxyManager] and therefore the same Redis instance,
 * but each limiter uses a distinct [BucketConfiguration] with different capacities.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    // ------------------------------------------------------------------
    // Shared Redis proxy manager
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
    // BucketConfiguration per strategy
    // ------------------------------------------------------------------

    /** IP-based bucket: 20 tokens per 10 s, 100 tokens per minute (anonymous traffic). */
    @Bean
    @Qualifier("ipBucketConfiguration")
    fun ipBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(20).refillIntervally(20, Duration.ofSeconds(10)) }
        addLimit { it.capacity(100).refillGreedy(10, Duration.ofMinutes(1)) }
    }

    /** User-based bucket: 50 tokens per 10 s, 200 tokens per minute (authenticated traffic). */
    @Bean
    @Qualifier("userBucketConfiguration")
    fun userBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(50).refillIntervally(50, Duration.ofSeconds(10)) }
        addLimit { it.capacity(200).refillGreedy(20, Duration.ofMinutes(1)) }
    }

    /** Combined (IP+userId) bucket: 10 tokens per 10 s, 50 tokens per minute. */
    @Bean
    @Qualifier("combinedBucketConfiguration")
    fun combinedBucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit { it.capacity(10).refillIntervally(10, Duration.ofSeconds(10)) }
        addLimit { it.capacity(50).refillGreedy(5, Duration.ofMinutes(1)) }
    }

    // ------------------------------------------------------------------
    // AsyncBucketProxyProvider per strategy
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
    // DistributedSuspendRateLimiter per strategy
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
