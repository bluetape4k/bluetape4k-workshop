package io.bluetape4k.workshop.gateway.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CircuitBreakerConfig {

    companion object: KLogging()

    @Autowired
    private val resilience4jConfig: Resilience4JConfigurationProperties = uninitialized()

    @Bean
    fun reactiveResiliency4jCircuitBreakerFactory(
        circuitBreakerRegistry: CircuitBreakerRegistry,
        timeLimiterRegistry: TimeLimiterRegistry,
    ): ReactiveResilience4JCircuitBreakerFactory {
        return ReactiveResilience4JCircuitBreakerFactory(
            circuitBreakerRegistry,
            timeLimiterRegistry,
            resilience4jConfig
        )
    }
}
