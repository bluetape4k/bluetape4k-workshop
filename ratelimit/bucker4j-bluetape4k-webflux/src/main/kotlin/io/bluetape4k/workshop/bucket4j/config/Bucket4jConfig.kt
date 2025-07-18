package io.bluetape4k.workshop.bucket4j.config

import io.bluetape4k.bucket4j.bucketConfiguration
import io.bluetape4k.bucket4j.distributed.AsyncBucketProxyProvider
import io.bluetape4k.bucket4j.distributed.BucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedRateLimiter
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
import io.github.bucket4j.distributed.proxy.ClientSideConfig
import io.github.bucket4j.distributed.proxy.ExecutionStrategy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration


@Configuration
class Bucket4jConfig {

    /**
     * Bucket의 Rate Limit 설정 정보
     *
     * @return
     */
    @Bean
    fun bucketConfiguration(): BucketConfiguration = bucketConfiguration {
        addLimit {
            it.capacity(10).refillIntervally(10, Duration.ofSeconds(10))
        }
        addLimit {
            it.capacity(100).refillGreedy(10, Duration.ofMinutes(1))
        }
    }

    /**
     * 분산환경에서 Bucket 정보를 관리하는 ProxyManager 를 생성합니다.
     *
     * @param lettuceClient  Lettuce Redis Client
     * @return
     */
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

    /**
     * Key 기반으로 비동기 방식의 [io.github.bucket4j.distributed.BucketProxy]를 제공하는 Provider 를 생성합니다.
     *
     * @param proxyManager  [io.github.bucket4j.distributed.BucketProxy] 를 관리하는 [ProxyManager]
     * @param bucketConfiguration [BucketConfiguration] 인스턴스
     * @return [BucketProxyProvider] 인스턴스
     */
    @Bean
    fun bucketProxyProvider(
        proxyManager: ProxyManager<ByteArray>,
        bucketConfiguration: BucketConfiguration,
    ): BucketProxyProvider {
        return BucketProxyProvider(proxyManager, bucketConfiguration)
    }

    /**
     * 동기 방식의 분산형 Rate Limiter 를 생성합니다.
     *
     * @param bucketProxyProvider [io.github.bucket4j.distributed.BucketProxy] 를 제공하는 Provider
     */
    @Bean
    fun distributedRateLimiter(bucketProxyProvider: BucketProxyProvider) =
        DistributedRateLimiter(bucketProxyProvider)

    /**
     * Key 기반으로 비동기 방식의 [io.github.bucket4j.distributed.AsyncBucketProxy]를 제공하는 Provider 를 생성합니다.
     *
     * @param proxyManager  [io.github.bucket4j.distributed.BucketProxy] 를 관리하는 [ProxyManager]
     * @param bucketConfiguration [BucketConfiguration] 인스턴스
     * @return [AsyncBucketProxyProvider] 인스턴스
     */
    @Bean
    fun asyncBucketProxyProvider(
        proxyManager: ProxyManager<ByteArray>,
        bucketConfiguration: BucketConfiguration,
    ): AsyncBucketProxyProvider {
        return AsyncBucketProxyProvider(proxyManager.asAsync(), bucketConfiguration)
    }

    /**
     * 비동기 방식의 분산형 Rate Limiter 를 생성합니다.
     *
     * @param asyncBucketProxyProvider [io.github.bucket4j.distributed.AsyncBucketProxy] 를 제공하는 Provider
     */
    @Bean
    fun distributedSuspendRateLimiter(asyncBucketProxyProvider: AsyncBucketProxyProvider) =
        DistributedSuspendRateLimiter(asyncBucketProxyProvider)
}
