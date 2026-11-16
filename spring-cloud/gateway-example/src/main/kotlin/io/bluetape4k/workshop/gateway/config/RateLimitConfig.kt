package io.bluetape4k.workshop.gateway.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    companion object: KLoggingChannel()

    /**
     * Spring Cloud Gateway 에서 제공하는 RateLimit 인데, 문제는 단순한 RateLimit 이라는 점이다.
     * Bucket4j 는 다양한 Path 를 Filtering 하고, 복합적인 Rate Limit 을 적용할 수 있지만,
     * [RedisRateLimiter] 는 단순한 RateLimit 만을 제공한다.
     *
     * @return
     */
    @Bean
    fun rateLimiter(): RedisRateLimiter {
        return RedisRateLimiter(1, 2)
    }

}
