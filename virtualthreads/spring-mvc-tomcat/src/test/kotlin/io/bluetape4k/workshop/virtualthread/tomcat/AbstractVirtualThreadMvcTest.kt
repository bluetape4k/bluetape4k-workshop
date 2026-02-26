package io.bluetape4k.workshop.virtualthread.tomcat

import io.bluetape4k.junit5.faker.Fakers
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractVirtualThreadMvcTest {

    companion object: KLoggingChannel() {
        @JvmStatic
        val faker = Fakers.faker
    }

    @Autowired
    protected val context: ApplicationContext = uninitialized()

    @LocalServerPort
    protected var port: Int = 0

    // MVC 에서 WebTestClient 를 사용하려면 이렇게 해야 합니다.
    protected val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
    }
}
