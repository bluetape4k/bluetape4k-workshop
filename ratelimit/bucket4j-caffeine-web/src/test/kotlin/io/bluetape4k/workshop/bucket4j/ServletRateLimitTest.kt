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

    companion object: KLogging()

    @LocalServerPort
    private val port: Int = 0

    private val client by lazy {
        WebTestClient
            .bindToServer().baseUrl("http://localhost:$port")
            .build()
    }

    @Test
    fun `hello with 5 times late limit`() {
        val url = "/hello"

        // `/hello` Rate limit is 5 requests per second
        // `/world` Rate limit is 10 requests per second
        repeat(5) {
            successfulWebRequest(url, 5 - 1 - it)
        }

        blockedWebRequestDueToRateLimit(url)
    }

    @Test
    fun `world with 10 times late limit`() {
        val url = "/world"

        repeat(10) {
            successfulWebRequest(url, 10 - 1 - it)
        }

        blockedWebRequestDueToRateLimit(url)
    }

    private fun successfulWebRequest(url: String, remainingTries: Int) {
        client.get()
            .uri(url)
            .exchange()
            .expectStatus().isOk
            .expectHeader().valueEquals("X-Rate-Limit-Remaining", remainingTries.toString())
    }

    private fun blockedWebRequestDueToRateLimit(url: String) {
        client.get()
            .uri(url)
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectBody().jsonPath("$.message").isEqualTo("Too many requests!")
    }
}
