package io.bluetape4k.workshop.exposed

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("h2")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractExposedSqlTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker
    }
}
