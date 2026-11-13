package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleScenario
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSession
import kotlin.collections.ArrayDeque

class KtorJobConsoleHttpClientRetryTest {

    companion object: KLogging()

    @Test
    fun `submission retry evidence records transient statuses and bounded delay`() {
        val responses = ArrayDeque(
            listOf(
                response(429, retryAfter = "0"),
                response(503, retryAfter = "not-a-duration"),
                response(202),
            ),
        )
        val delays = mutableListOf<Duration>()
        val client = KtorJobConsoleHttpClient(
            baseUri = URI("http://localhost"),
            sendRequest = { responses.removeFirst() },
            delay = delays::add,
        )

        val snapshot = client.submit(JobConsoleScenario())
        log.debug { "snapshot=$snapshot" }
        snapshot.retryAttempts shouldBeEqualTo 2
        snapshot.retryStatuses shouldBeEqualTo listOf(429, 503, 202)
        snapshot.retryDelay shouldBeEqualTo 2.seconds() + 100.millis()
        delays shouldBeEqualTo listOf(2.seconds(), 100.millis())

        client.retrySummary().let {
            log.debug { "client retry summary=$it" }
            it.requestCount shouldBeEqualTo 1
            it.retryCount shouldBeEqualTo 2
            it.statusCounts shouldBeEqualTo mapOf(429 to 1, 503 to 1)
            it.cumulativeDelay shouldBeEqualTo 2.seconds() + 100.millis()
        }
    }

    @Test
    fun `submission stops after the maximum transient retry budget`() {
        val calls = AtomicInteger()
        val delays = AtomicInteger()
        val client = KtorJobConsoleHttpClient(
            baseUri = URI("http://localhost"),
            sendRequest = {
                calls.incrementAndGet()
                response(429, retryAfter = "1")
            },
            delay = { delays.incrementAndGet() },
        )

        assertFailsWith<IllegalStateException> {
            client.submit(JobConsoleScenario())
        }

        calls.get() shouldBeEqualTo 9
        delays.get() shouldBeEqualTo 8
    }

    @Test
    fun `retry-after rejects negative values and clamps excessive values`() {
        val responses = ArrayDeque(
            listOf(
                response(429, retryAfter = "-1"),
                response(202),
                response(503, retryAfter = "999"),
                response(202),
            ),
        )
        val delays = mutableListOf<Duration>()
        val client = KtorJobConsoleHttpClient(
            baseUri = URI("http://localhost"),
            sendRequest = { responses.removeFirst() },
            delay = delays::add,
        )

        val fallback = client.submit(JobConsoleScenario())
        val capped = client.submit(JobConsoleScenario(idempotencyKey = "second-key"))

        log.debug { "fallback=$fallback" }
        log.debug { "capped=$capped" }
        fallback.retryDelay shouldBeEqualTo 2.seconds()
        capped.retryDelay shouldBeEqualTo 30.seconds()
        delays shouldBeEqualTo listOf(2.seconds(), 30.seconds())
    }

    private fun response(
        status: Int,
        retryAfter: String? = null,
    ): HttpResponse<String> {
        val headers = retryAfter?.let { mapOf("Retry-After" to listOf(it)) }.orEmpty()
        return object: HttpResponse<String> {
            override fun statusCode(): Int = status

            override fun request(): HttpRequest? = null

            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

            override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }

            override fun body(): String = "{\"jobId\":\"11111111-1111-1111-1111-111111111111\",\"state\":\"accepted\"}"

            override fun sslSession(): Optional<SSLSession> = Optional.empty()

            override fun uri(): URI = URI("http://localhost/v1/jobs")

            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }
}
