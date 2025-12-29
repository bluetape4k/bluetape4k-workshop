package io.bluetape4k.workshop.problem

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractProblemTest {

    companion object: KLogging() {
        @JvmStatic
        protected val faker = Fakers.faker
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()


    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }
}
