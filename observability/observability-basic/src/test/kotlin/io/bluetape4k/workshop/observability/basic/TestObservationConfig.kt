package io.bluetape4k.workshop.observability.basic

import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Shared test configuration providing a `TestObservationRegistry` bean.
 *
 * ## Behavior / Contract
 * - Declared once per module to avoid Spring context caching collisions
 *   when multiple test classes each declare a `@Primary` bean of the same type.
 * - Import via `@Import(TestObservationConfig::class)` on each test class.
 */
@TestConfiguration
class TestObservationConfig {

    @Bean
    @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}
