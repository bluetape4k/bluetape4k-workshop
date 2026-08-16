package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.workshop.operations.jobconsole.fixture.BoundedWaitHttpIdempotencyFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpringBoundedWaitHttpIdempotencyConformanceTest {
    @Test
    fun `Spring HTTP host passes the shared bounded wait conformance`() = runBlocking {
        val context =
            SpringApplicationBuilder(BoundedWaitSpringHostConfiguration::class.java)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .run()
        try {
            val port = requireNotNull((context as ServletWebServerApplicationContext).webServer).port
            val fixture = context.getBean(BoundedWaitHttpIdempotencyFixture::class.java)
            val adapter =
                SpringBoundedWaitHttpIdempotencyAdapter(
                    fixture = fixture,
                    endpoint = URI("http://127.0.0.1:$port/__test/v1/jobs"),
                )
            assertBoundedWaitHttpIdempotencyConformance(adapter, boundedWaitConformanceConfig())
            adapter.close()
        } finally {
            context.close()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    exclude = [
        DataSourceAutoConfiguration::class,
        DataSourceTransactionManagerAutoConfiguration::class,
    ],
)
internal class BoundedWaitSpringHostConfiguration {
    @Bean(destroyMethod = "close")
    fun boundedWaitFixture(): BoundedWaitHttpIdempotencyFixture =
        BoundedWaitHttpIdempotencyFixture(boundedWaitConformanceConfig())

    @Bean
    fun boundedWaitController(fixture: BoundedWaitHttpIdempotencyFixture): BoundedWaitSpringController =
        BoundedWaitSpringController(fixture)
}

@RestController
@TestComponent
internal class BoundedWaitSpringController(
    private val fixture: BoundedWaitHttpIdempotencyFixture,
) {
    @PostMapping("/__test/v1/jobs")
    fun submit(request: HttpServletRequest): DeferredResult<ResponseEntity<ByteArray>> {
        val keys = request.getHeaders("Idempotency-Key").toList()
        val profile = request.getHeader("X-Test-Authentication-Profile") ?: "unauthenticated"
        if (profile == "unauthenticated" || profile == "tenant-a-read-only") {
            val response =
                runBlocking {
                    fixture.exchange(
                        HttpIdempotencyRequest(
                            authenticationProfile = profile,
                            operation = request.getHeader("X-Test-Operation") ?: "create-widget",
                            resourceIdentity = request.getHeader("X-Test-Resource") ?: "widget-1",
                            idempotencyKeys = keys.ifEmpty { listOf("") },
                            requestBody = "",
                        ),
                    )
                }
            return DeferredResult<ResponseEntity<ByteArray>>().also { it.setResult(response.toResponseEntity()) }
        }
        val body = request.inputStream.readBytes().toString(UTF_8)
        val requestValue = HttpIdempotencyRequest(
            authenticationProfile = profile,
            operation = request.getHeader("X-Test-Operation") ?: "create-widget",
            resourceIdentity = request.getHeader("X-Test-Resource") ?: "widget-1",
            idempotencyKeys = keys.ifEmpty { listOf("") },
            requestBody = body,
        )
        val deferred = DeferredResult<ResponseEntity<ByteArray>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var exchangeJob: Job? = null
        fun cancelExchange() {
            exchangeJob?.cancel()
            scope.cancel()
        }
        deferred.onCompletion(::cancelExchange)
        deferred.onTimeout(::cancelExchange)
        deferred.onError { cancelExchange() }
        exchangeJob = scope.launch {
            try {
                val response = fixture.exchange(requestValue)
                deferred.setResult(response.toResponseEntity())
            } catch (failure: Throwable) {
                if (failure is kotlinx.coroutines.CancellationException) {
                    // Complete the servlet async result even after the peer disconnects. Leaving a
                    // DeferredResult pending would retain a Tomcat request until its timeout and
                    // starve later conformance scenarios.
                    deferred.setResult(ResponseEntity.status(503).body(ByteArray(0)))
                } else {
                    deferred.setErrorResult(failure)
                }
            }
        }
        return deferred
    }
}

private fun HttpIdempotencyResponse.toResponseEntity(): ResponseEntity<ByteArray> {
    val headers = HttpHeaders()
    this.headers.forEach { (name, values) -> values.forEach { value -> headers.add(name, value) } }
    return ResponseEntity.status(statusCode).headers(headers).body(body.toByteArray(UTF_8))
}

private class SpringBoundedWaitHttpIdempotencyAdapter(
    private val fixture: BoundedWaitHttpIdempotencyFixture,
    private val endpoint: URI,
) : BoundedWaitHttpIdempotencyAdapter {
    fun close() = Unit

    override suspend fun exchange(request: HttpIdempotencyRequest): HttpIdempotencyResponse {
        if (request.requestBody.hasUnpairedSurrogate()) return fixture.exchange(request)
        val httpRequest = runCatching {
            HttpRequest.newBuilder(endpoint).apply {
                header("X-Test-Authentication-Profile", request.authenticationProfile)
                header("X-Test-Operation", request.operation)
                header("X-Test-Resource", request.resourceIdentity)
                request.idempotencyKeys.forEach { key -> header("Idempotency-Key", key) }
                header("Connection", "close")
                header("Content-Type", "application/json")
                POST(HttpRequest.BodyPublishers.ofString(request.requestBody, UTF_8))
            }.build()
        }.getOrElse { return fixture.exchange(request) }
        val clientExecutor = Executors.newVirtualThreadPerTaskExecutor()
        return try {
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .executor(clientExecutor)
                .build()
                .sendCancellable(httpRequest)
                .toIdempotencyResponse()
        } finally {
            clientExecutor.close()
        }
    }

    override suspend fun awaitOwnerStarted(request: HttpIdempotencyRequest) = fixture.awaitOwnerStarted(request)

    override suspend fun awaitWaiterCount(request: HttpIdempotencyRequest, expected: Int) =
        fixture.awaitWaiterCount(request, expected)

    override suspend fun completeOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) =
        fixture.completeOwner(request, outcome)

    override suspend fun holdOwnerResponseDelivery(request: HttpIdempotencyRequest) =
        fixture.holdOwnerResponseDelivery(request)

    override suspend fun releaseOwnerResponseDelivery(request: HttpIdempotencyRequest) =
        fixture.releaseOwnerResponseDelivery(request)

    override suspend fun abandonOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) =
        fixture.abandonOwner(request, outcome)

    override suspend fun advanceTimeBy(duration: Duration) = fixture.advanceTimeBy(duration)

    override suspend fun resetScenario() = fixture.resetScenario()

    override fun sideEffectCount(request: HttpIdempotencyRequest): Int = fixture.sideEffectCount(request)

    override fun quiescence(): HttpIdempotencyQuiescence = fixture.quiescence()
}

private suspend fun HttpClient.sendCancellable(request: HttpRequest): HttpResponse<String> =
    suspendCancellableCoroutine { continuation ->
        val future = sendAsync(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        continuation.invokeOnCancellation { future.cancel(true) }
        future.whenComplete { response, failure ->
            if (failure == null) {
                if (continuation.isActive) continuation.resume(response)
            } else if (failure !is java.util.concurrent.CancellationException && continuation.isActive) {
                continuation.resumeWithException(failure)
            }
        }
    }

private fun HttpResponse<String>.toIdempotencyResponse(): HttpIdempotencyResponse {
    val body = body()
    val headers = headers().map().filterKeys { it in setOf("content-type", "etag", "idempotency-replayed", "retry-after") }
    val problemCode = Regex("\\\"code\\\"\\s*:\\s*\\\"([a-z0-9_]+)\\\"").find(body)?.groupValues?.get(1)
    return HttpIdempotencyResponse(statusCode(), body, headers, problemCode)
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val character = this[index]
        when {
            character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> index += 2
            character.isHighSurrogate() || character.isLowSurrogate() -> return true
            else -> index++
        }
    }
    return false
}

private fun boundedWaitConformanceConfig() =
    BoundedWaitHttpIdempotencyConformanceConfig(
        waitTimeout = Duration.ofSeconds(2),
        scenarioTimeout = Duration.ofSeconds(15),
        maxWaitersPerKey = 2,
        retention = Duration.ofHours(1),
        inFlightRetryAfter = Duration.ofSeconds(1),
        overflowRetryAfter = Duration.ofSeconds(2),
        maxIdempotencyKeyBytes = 255,
        maxRequestBodyBytes = 64 * 1024,
        maxReplayBodyBytes = 64 * 1024,
        maxReplayHeaderNames = 8,
        maxReplayValuesPerHeader = 4,
        maxReplayHeaderValueBytes = 4 * 1024,
        maxReplayHeaderBytes = 16 * 1024,
        replayHeaderAllowlist = setOf("etag"),
    )
