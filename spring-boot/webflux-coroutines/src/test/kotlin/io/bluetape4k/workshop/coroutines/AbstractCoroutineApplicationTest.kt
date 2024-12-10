package io.bluetape4k.workshop.coroutines

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.coroutines.model.Banner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.WebTestClient.RequestBodySpec
import org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
abstract class AbstractCoroutineApplicationTest {

    companion object: KLogging() {
        const val REPEAT_SIZE = 3
        val banner = Banner("제목", "동해물과 백두산이 마르고 닳도록")
    }

    @Autowired
    protected val client: WebTestClient = uninitialized()

    protected fun clientGet(uri: String): RequestHeadersSpec<*> =
        client.get().uri(uri).accept(MediaType.APPLICATION_JSON)

    protected fun clientPost(uri: String): RequestBodySpec =
        client.post().uri(uri).accept(MediaType.APPLICATION_JSON)

}
