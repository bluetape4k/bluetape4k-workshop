package io.bluetape4k.workshop.commerce.metering.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MeteringProperties::class)
class MeteringConfiguration {
    @Bean
    fun meteringClock(): Clock = Clock.systemUTC()
}
