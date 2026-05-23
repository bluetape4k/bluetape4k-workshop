package io.bluetape4k.workshop.observability.advanced

import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Shared test configuration providing a `TestObservationRegistry` bean.
 *
 * ## Behavior / Contract
 * - Single declaration per module prevents Spring context caching collisions
 *   when multiple test classes each import a `@Primary` bean of the same type.
 * - Import via `@Import(TestObservationConfig::class)` on each test class.
 */
@TestConfiguration
class TestObservationConfig {

    @Bean
    @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}
