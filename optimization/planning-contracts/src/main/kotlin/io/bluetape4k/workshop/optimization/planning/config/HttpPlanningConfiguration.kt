package io.bluetape4k.workshop.optimization.planning.config

import io.bluetape4k.workshop.optimization.planning.adapter.http.CallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.adapter.http.CustomSolverPlanningEngine
import io.bluetape4k.workshop.optimization.planning.adapter.http.HmacSha256CallbackSignatureVerifier
import io.bluetape4k.workshop.optimization.planning.adapter.http.TimefoldPlatformPlanningEngine
import io.bluetape4k.workshop.optimization.planning.domain.PlanningEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import tools.jackson.databind.ObjectMapper

@Configuration(proxyBeanMethods = false)
@Profile("timefold | custom-solver")
internal class HttpPlanningConfiguration {

    @Bean(destroyMethod = "close")
    @Profile("timefold")
    fun timefoldPlanningEngine(
        properties: PlanningProviderProperties,
        objectMapper: ObjectMapper,
    ): PlanningEngine = TimefoldPlatformPlanningEngine(properties.baseUrl, objectMapper)

    @Bean(destroyMethod = "close")
    @Profile("custom-solver")
    fun customSolverPlanningEngine(
        properties: PlanningProviderProperties,
        objectMapper: ObjectMapper,
    ): PlanningEngine = CustomSolverPlanningEngine(properties.baseUrl, objectMapper)

    @Bean
    fun hmacCallbackSignatureVerifier(properties: PlanningProviderProperties): CallbackSignatureVerifier =
        HmacSha256CallbackSignatureVerifier(properties.callbackSecret)
}
