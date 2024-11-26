package io.bluetape4k.workshop.observation.config

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationTextPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ObservationLoggingConfig {

    private val logger = KotlinLogging.logger("io.bluetape4k.workshop.observation.ObservationLogger")

    @Bean
    fun observationLogger(): ObservationHandler<Observation.Context> {
        return ObservationTextPublisher { logger.debug { it } }
    }
}
