package io.bluetape4k.workshop.observability.basic

import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * `TestObservationRegistry` bean 을 제공하는 shared test configuration 입니다.
 *
 * ## Behavior / Contract
 * - 여러 test class 가 같은 type 의 `@Primary` bean 을 각각 선언할 때 생기는 Spring context caching collision 을 피하려고 module 당 한 번만 선언합니다.
 * - 각 test class 에서 `@Import(TestObservationConfig::class)` 로 import 합니다.
 */
@TestConfiguration
class TestObservationConfig {

    @Bean
    @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}
