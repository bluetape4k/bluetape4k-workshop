package io.bluetape4k.workshop.exposed.virtualthread

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("h2")
// @ActiveProfiles("mysql")
@SpringBootTest(
    classes = [ExposedSqlVirtualThreadMvcApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
abstract class AbstractExposedTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker
    }
}
