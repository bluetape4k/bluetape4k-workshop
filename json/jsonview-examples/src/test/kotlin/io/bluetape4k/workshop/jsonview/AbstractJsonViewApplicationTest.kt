package io.bluetape4k.workshop.jsonview

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractJsonViewApplicationTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker
    }
}
