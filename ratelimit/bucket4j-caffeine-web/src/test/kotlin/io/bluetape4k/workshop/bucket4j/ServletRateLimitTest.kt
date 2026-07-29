package io.bluetape4k.workshop.bucket4j

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("servlet")
class ServletRateLimitTest {

    companion object : KLogging() {
        private const val HELLO_PATH = "/hello"
        private const val WORLD_PATH = "/world"
        private const val REMAINING_HEADER = "X-Rate-Limit-Remaining"
        private const val TOO_MANY_REQUESTS_MESSAGE = "Too many requests!"
    }

    @LocalServerPort
    private val port: Int = 0

    private val client by lazy {
        WebTestClient
            .bindToServer().baseUrl("http://localhost:$port")
            .build()
    }

    @Test
    fun `hello with 5 times rate limit`() {
        // `/hello` rate limit은 초당 5 request입니다.
        // `/world` rate limit은 초당 10 request입니다.
        repeat(5) {
            successfulWebRequest(HELLO_PATH, 5 - 1 - it)
        }

        blockedWebRequestDueToRateLimit(HELLO_PATH)
    }

    @Test
    fun `world with 10 times rate limit`() {
        repeat(10) {
            successfulWebRequest(WORLD_PATH, 10 - 1 - it)
        }

        blockedWebRequestDueToRateLimit(WORLD_PATH)
    }

    private fun successfulWebRequest(url: String, remainingTries: Int) {
        client.get()
            .uri(url)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals(REMAINING_HEADER, remainingTries.toString())
    }

    private fun blockedWebRequestDueToRateLimit(url: String) {
        client.get()
            .uri(url)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectBody().jsonPath("$.message").isEqualTo(TOO_MANY_REQUESTS_MESSAGE)
    }
}
