package io.bluetape4k.workshop.leader.backendcomparison.observability

import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.micrometer.InstrumentedLeaderElector
import io.bluetape4k.workshop.leader.backendcomparison.service.LeaderBackendCatalog
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot diagnostics endpoint와 health selector가 workshop profile provider를
 * 선택하도록 구성합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LeaderBackendDiagnosticsProperties::class)
class LeaderBackendDiagnosticsConfiguration {

    /**
     * local delegate를 Micrometer decorator로 감싸 Spring 운영 표면에 연결합니다.
     */
    @Bean("workshopLeaderElector")
    @ConditionalOnMissingBean(name = ["workshopLeaderElector"])
    fun workshopLeaderElector(
        catalog: LeaderBackendCatalog,
        properties: LeaderBackendDiagnosticsProperties,
        meterRegistry: MeterRegistry,
    ): LeaderElector = InstrumentedLeaderElector(
        delegate = ProfiledLeaderElector(catalog, properties),
        registry = meterRegistry,
    )
}
