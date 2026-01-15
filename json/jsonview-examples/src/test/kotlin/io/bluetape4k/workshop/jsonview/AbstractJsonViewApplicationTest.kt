package io.bluetape4k.workshop.jsonview

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractJsonViewApplicationTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        protected val faker = Fakers.faker
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    protected fun WebTestClient.httpGet(url: String): WebTestClient.ResponseSpec =
        this.get()
            .uri(url)
            .exchangeSuccessfully()

}
