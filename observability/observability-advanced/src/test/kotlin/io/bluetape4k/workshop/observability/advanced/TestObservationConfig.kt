package io.bluetape4k.workshop.observability.advanced

import io.micrometer.observation.tck.TestObservationRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * `TestObservationRegistry` bean 을 제공하는 shared test configuration 입니다.
 *
 * ## Behavior / Contract
 * - module 당 하나만 선언해 여러 test class 가 같은 type 의 `@Primary` bean 을 각각 import 할 때 생기는 Spring context caching collision 을 방지합니다.
 * - 각 test class 에서 `@Import(TestObservationConfig::class)` 로 import 합니다.
 */
@TestConfiguration
class TestObservationConfig {

    @Bean
    @Primary
    fun testObservationRegistry(): TestObservationRegistry = TestObservationRegistry.create()
}
