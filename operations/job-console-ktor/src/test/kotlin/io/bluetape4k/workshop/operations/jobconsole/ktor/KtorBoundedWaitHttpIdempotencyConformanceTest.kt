package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.workshop.operations.jobconsole.fixture.BoundedWaitHttpIdempotencyFixture
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(InternalAPI::class)
class KtorBoundedWaitHttpIdempotencyConformanceTest {
    @Test
    fun `Ktor HTTP host passes the shared bounded wait conformance`() = runBlocking {
        val fixture = BoundedWaitHttpIdempotencyFixture(boundedWaitConformanceConfig())
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, port = port) {
            routing {
                post("/__test/v1/jobs") {
                    val keys = call.request.headers.getAll("Idempotency-Key").orEmpty()
                    val body = call.receiveChannel().readRemaining().readByteArray().toString(UTF_8)
                    val request =
                        HttpIdempotencyRequest(
                            authenticationProfile = call.request.headers["X-Test-Authentication-Profile"] ?: "unauthenticated",
                            operation = call.request.headers["X-Test-Operation"] ?: "create-widget",
                            resourceIdentity = call.request.headers["X-Test-Resource"] ?: "widget-1",
                            idempotencyKeys = keys.ifEmpty { listOf("") },
                            requestBody = body,
                        )
                    val response = fixture.exchange(request)
                    response.headers.forEach { (name, values) -> values.forEach { value -> call.response.headers.append(name, value) } }
                    call.respondBytes(
                        bytes = response.body.toByteArray(UTF_8),
                        contentType = ContentType.parse(response.headers["content-type"]?.singleOrNull() ?: "application/json"),
                        status = HttpStatusCode.fromValue(response.statusCode),
                    )
                }
            }
        }
        server.start(wait = false)
        val adapter =
            KtorBoundedWaitHttpIdempotencyAdapter(
                fixture = fixture,
                endpoint = URI("http://127.0.0.1:$port/__test/v1/jobs"),
            )
        try {
            assertBoundedWaitHttpIdempotencyConformance(adapter, boundedWaitConformanceConfig())
        } finally {
            adapter.close()
            fixture.close()
            server.stop(500, 2_000)
        }
    }
}

private class KtorBoundedWaitHttpIdempotencyAdapter(
    private val fixture: BoundedWaitHttpIdempotencyFixture,
    private val endpoint: URI,
) : BoundedWaitHttpIdempotencyAdapter {
    private val clientExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .executor(clientExecutor)
        .build()

    fun close() = clientExecutor.close()

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
        return client.sendCancellable(httpRequest).toIdempotencyResponse()
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
    suspendCancellableCoroutine { continuation: CancellableContinuation<HttpResponse<String>> ->
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
    val headers = buildMap<String, List<String>> {
        headers().map().forEach { (name, values) ->
            if (name.lowercase() in setOf("content-type", "etag", "idempotency-replayed", "retry-after")) {
                put(name.lowercase(), values)
            }
        }
    }
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
