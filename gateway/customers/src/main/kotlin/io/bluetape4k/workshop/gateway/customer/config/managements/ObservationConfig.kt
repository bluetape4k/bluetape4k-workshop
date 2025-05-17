package io.bluetape4k.workshop.gateway.customer.config.managements

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.aop.ObservedAspect
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 3에서 Micrometer 를 이용한 Observability를 활용하기 위한 환경설정입니다.
 * AoP 를 활용하므로 spring-boot-starter-aop 를 참조해야 합니다.
 *
 * 참고:
 *   - [Observability with Spring Boot 3](https://www.baeldung.com/spring-boot-3-observability)
 *   - [Micrometer Observability](https://micrometer.io/docs/concepts#_observability)
 *
 */
@Configuration
@ConditionalOnClass(ObservedAspect::class)
class ObservationConfig {

    companion object: KLoggingChannel() {
        private val ignorePaths = listOf(
            "/actuator",
            "/swagger",
            "/v2/api-docs",
            "/v3/api-docs",
        )
    }

    @ConditionalOnClass(ObservationRegistry::class)
    @Bean
    fun observedAspect(registry: ObservationRegistry): ObservedAspect {
        return ObservedAspect(registry)
    }

    /**
     * Metric 측정을 수행할 Http Path의 Filter를 정의합니다.
     * `/actuator` 등 관리용 url은 metric 측정을 하지 않습니다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun metricsHttpServerUriFilter(): MeterFilter {
        return MeterFilter
            .deny { id ->
                val tag = id?.getTag("uri")
                tag?.let { t -> ignorePaths.any { path -> t.contains(path) } } ?: false
            }
    }
}
