package io.bluetape4k.workshop.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.coroutines.model.Banner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnableWebFlux
abstract class AbstractCoroutineApplicationTest {

    companion object: KLoggingChannel() {
        const val REPEAT_SIZE = 3
        val expectedBanner = Banner.TEST_BANNER
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client by lazy {
        WebTestClient
            .bindToApplicationContext(context)
            // .configureClient()
            .build()
    }
}
