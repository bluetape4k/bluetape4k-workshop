package io.bluetape4k.workshop.optimization.planning.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("planning.provider")
internal data class PlanningProviderProperties(
    val baseUrl: String = "http://localhost:8081",
    val callbackSecret: String = "",
)
