package io.bluetape4k.workshop.spring.modulith.events.util

import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestConstructor

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
annotation class IntegrationTest()
