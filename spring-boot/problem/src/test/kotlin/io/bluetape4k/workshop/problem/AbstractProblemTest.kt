package io.bluetape4k.workshop.problem

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractProblemTest @Autowired constructor(
    protected val context: ApplicationContext,
) {

    companion object : KLogging() {
        @JvmStatic
        protected val faker = Fakers.faker
    }

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }
}
