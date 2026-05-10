package io.bluetape4k.workshop.micrometer

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import io.bluetape4k.testcontainers.http.BluetapeHttpServer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractTracingTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        private val bluetapeHttpServer by lazy { BluetapeHttpServer.Launcher.bluetapeHttpServer }

        @JvmStatic
        @DynamicPropertySource
        fun tracingProperties(registry: DynamicPropertyRegistry) {
            registry.add("workshop.micrometer.jsonplaceholder-base-url") { bluetapeHttpServer.jsonplaceholderUrl }
        }
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

}
