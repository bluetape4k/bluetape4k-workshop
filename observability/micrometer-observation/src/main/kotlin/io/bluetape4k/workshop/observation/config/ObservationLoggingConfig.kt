package io.bluetape4k.workshop.observation.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationTextPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ObservationLoggingConfig {

    companion object: KLogging()

    @Bean
    fun observationLogger(): ObservationHandler<Observation.Context> {
        return ObservationTextPublisher { log.debug { it } }
    }
}
