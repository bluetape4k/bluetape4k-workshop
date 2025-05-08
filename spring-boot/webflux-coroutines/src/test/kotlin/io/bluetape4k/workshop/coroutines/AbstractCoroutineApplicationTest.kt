package io.bluetape4k.workshop.coroutines

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.uninitialized
import io.bluetape4k.workshop.coroutines.model.Banner
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
abstract class AbstractCoroutineApplicationTest {

    companion object: KLogging() {
        const val REPEAT_SIZE = 3
        val expectedBanner = Banner.TEST_BANNER
    }

    @Autowired
    protected val client: WebTestClient = uninitialized()

}
