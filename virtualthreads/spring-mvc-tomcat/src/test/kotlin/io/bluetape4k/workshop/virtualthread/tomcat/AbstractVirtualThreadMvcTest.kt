package io.bluetape4k.workshop.virtualthread.tomcat

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractVirtualThreadMvcTest {

    companion object: KLogging() {
        @JvmStatic
        val faker = Fakers.faker
    }

}
