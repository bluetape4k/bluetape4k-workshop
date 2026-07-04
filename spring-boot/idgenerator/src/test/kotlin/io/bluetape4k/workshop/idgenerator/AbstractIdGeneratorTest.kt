package io.bluetape4k.workshop.idgenerator

import io.bluetape4k.logging.KLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@EnableWebFlux
abstract class AbstractIdGeneratorTest @Autowired constructor(
    protected val context: ApplicationContext,
) {

    companion object : KLogging()

    protected val client: WebTestClient by lazy {
        WebTestClient
            .bindToApplicationContext(context)
            .configureClient()
            .build()
    }
}
