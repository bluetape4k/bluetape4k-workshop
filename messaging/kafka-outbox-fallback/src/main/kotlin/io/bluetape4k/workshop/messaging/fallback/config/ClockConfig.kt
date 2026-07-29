package io.bluetape4k.workshop.messaging.fallback.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * publication retry 와 reconciliation component 가 사용하는 time source 입니다.
 */
@Configuration
class ClockConfig {

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
