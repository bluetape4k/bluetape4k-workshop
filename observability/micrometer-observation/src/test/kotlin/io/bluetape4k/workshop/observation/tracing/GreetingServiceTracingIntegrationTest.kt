package io.bluetape4k.workshop.observation.tracing

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.observation.service.GreetingService
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.tracing.test.simple.SimpleTracer
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

@SpringBootTest
@ComponentScan(basePackageClasses = [GreetingService::class])
@EnableAutoConfiguration
@AutoConfigureObservability
class GreetingServiceTracingIntegrationTest {

    @TestConfiguration
    class ObservationTestConfiguration {
        @Bean
        fun observationRegistry(): TestObservationRegistry {
            return TestObservationRegistry.create()
        }

        @Bean
        fun simpleTracer(): SimpleTracer {
            return SimpleTracer()
        }
    }

    companion object: KLogging()

    @Autowired
    private val observationRegistry: TestObservationRegistry = uninitialized()

    @Autowired
    private val tracer: SimpleTracer = uninitialized()

    @Autowired
    private val service: GreetingService = uninitialized()

    @Test
    fun `context loading`() {
        observationRegistry.shouldNotBeNull()
        tracer.shouldNotBeNull()
        service.shouldNotBeNull()
    }

    @Test
    fun `tracing for greeting`() {
        service.sayHello()

        tracer.spans.forEach {
            log.debug { "span: $it" }
        }

        tracer.spans.any { it.name == "greetingService" }.shouldBeTrue()
    }
}
