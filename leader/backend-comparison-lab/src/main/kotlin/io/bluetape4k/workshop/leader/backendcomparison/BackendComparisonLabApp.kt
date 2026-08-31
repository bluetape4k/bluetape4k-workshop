package io.bluetape4k.workshop.leader.backendcomparison

import io.bluetape4k.workshop.leader.backendcomparison.observability.LeaderBackendDiagnosticsConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * leader backend comparison lab의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
@Import(LeaderBackendDiagnosticsConfiguration::class)
class BackendComparisonLabApp

fun main(args: Array<String>) {
    runApplication<BackendComparisonLabApp>(*args)
}
