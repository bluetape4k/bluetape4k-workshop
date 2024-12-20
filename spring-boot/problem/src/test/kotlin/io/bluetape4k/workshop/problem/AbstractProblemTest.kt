package io.bluetape4k.workshop.problem

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractProblemTest {

    companion object: KLogging() {
        @JvmStatic
        protected val faker = Fakers.faker
    }
}
