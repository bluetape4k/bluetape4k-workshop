package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest
abstract class AbstractSecurityApplicationTest {

    companion object: KLoggingChannel()

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToApplicationContext(context)
            .configureClient()
            .build()
    }
}
