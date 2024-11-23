package io.bluetape4k.workshop.virtualthread.tomcat

import io.bluetape4k.logging.KLogging
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractVirtualThreadMvcTest {

    companion object: KLogging()

    protected fun WebTestClient.get(path: String) =
        get()
            .uri(path)
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful


    protected fun <T: Any> WebTestClient.post(path: String, bodyValue: T) =
        post()
            .uri(path)
            .bodyValue(bodyValue)
            .accept(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().is2xxSuccessful


}
