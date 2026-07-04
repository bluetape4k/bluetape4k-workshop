package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.uninitialized
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("webflux")
abstract class AbstractRateLimitTest {

    companion object : KLoggingChannel() {
        private const val REMAINING_HEADER = "X-Rate-Limit-Remaining"
        private const val TOO_MANY_REQUESTS_MESSAGE = "Too many requests!"
    }

    @Autowired
    private val context: ApplicationContext = uninitialized()

    protected val client: WebTestClient by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    protected fun successfulWebRequest(url: String, remainingTries: Int) {
        client.get()
            .uri(url)
            .exchangeSuccessfully()
            .expectHeader()
            .valueEquals(REMAINING_HEADER, remainingTries.toString())
    }

    protected fun blockedWebRequestDueToRateLimit(url: String) {
        client
            .get()
            .uri(url)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectBody()
            .jsonPath("message").isEqualTo(TOO_MANY_REQUESTS_MESSAGE)
    }
}
