package io.bluetape4k.workshop.spring.security.webflux.jwt

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.requireNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
abstract class AbstractJwtApplicationTest {

    companion object : KLoggingChannel()

    @Autowired
    private var injectedContext: ApplicationContext? = null

    protected val context: ApplicationContext
        get() = injectedContext.requireNotNull("context")

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient
            .bindToApplicationContext(context)
            .configureClient()
            .build()
    }
}
