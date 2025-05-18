package io.bluetape4k.workshop.resilience.config

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.resilience.events.CircuitBreakerRegistryEventConsumer
import io.bluetape4k.workshop.resilience.events.RetryRegistryEventConsumer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.registry.RegistryEventConsumer
import io.github.resilience4j.retry.Retry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class Resilience4jConfig {

    companion object: KLoggingChannel()

    @Bean
    @ConditionalOnMissingBean
    fun myCircuitBreakerRegistryEventConsumer(): RegistryEventConsumer<CircuitBreaker> {
        return CircuitBreakerRegistryEventConsumer()
    }

    @Bean
    @ConditionalOnMissingBean
    fun myRetryRegistryEventConsumer(): RegistryEventConsumer<Retry> {
        return RetryRegistryEventConsumer()
    }
}
