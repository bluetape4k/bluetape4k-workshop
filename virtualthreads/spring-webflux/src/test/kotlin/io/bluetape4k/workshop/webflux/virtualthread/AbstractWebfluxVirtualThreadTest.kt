package io.bluetape4k.workshop.webflux.virtualthread

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT1M")       // Timeout for WebTestClient : 1 minute
abstract class AbstractWebfluxVirtualThreadTest {

    companion object: KLogging()

    @Autowired
    protected val client: WebTestClient = uninitialized()

    protected fun getApi(path: String): WebTestClient.ResponseSpec {
        return client.get().uri(path).exchange()
    }

    protected fun <B: Any> postApi(path: String, body: B): WebTestClient.ResponseSpec {
        return client
            .post()
            .uri(path)
            .bodyValue(body)
            .exchange()
    }
}
