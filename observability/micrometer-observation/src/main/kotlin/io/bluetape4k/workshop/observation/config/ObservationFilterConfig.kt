package io.bluetape4k.workshop.observation.config

import io.bluetape4k.logging.KLogging
import io.micrometer.observation.ObservationRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.filter.ServerHttpObservationFilter
import org.springframework.web.server.WebFilter

/**
 * spring-boot-starter-aop 를 참조하고, spring-boot-starter-web 의존성이 있는 경우에만 활성화됩니다.
 */
@Configuration
class ObservationFilterConfig {

    companion object: KLogging()

    @ConditionalOnBean(ObservationRegistry::class)
    @ConditionalOnClass(WebFilter::class)       // spring web 의존성이 있는 경우에만 활성화됩니다.
    @ConditionalOnMissingBean(ServerHttpObservationFilter::class)
    @Bean
    fun observationFilter(observationRegistry: ObservationRegistry): ServerHttpObservationFilter {
        return ServerHttpObservationFilter(observationRegistry)
    }
}
