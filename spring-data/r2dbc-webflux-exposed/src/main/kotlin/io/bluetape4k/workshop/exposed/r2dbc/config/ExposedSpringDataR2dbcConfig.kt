package io.bluetape4k.workshop.exposed.r2dbc.config

import io.bluetape4k.spring.data.exposed.r2dbc.repository.config.EnableExposedR2dbcRepositories
import org.springframework.context.annotation.Configuration

/**
 * Spring Data Exposed R2DBC repository factory를 workshop의 QBE interface에 연결합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableExposedR2dbcRepositories(
    basePackages = ["io.bluetape4k.workshop.exposed.r2dbc.domain.repository"],
)
class ExposedSpringDataR2dbcConfig
