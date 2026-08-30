package io.bluetape4k.workshop.leader.jobsafety.audit

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/**
 * 기본 `MEMORY` audit transport에서 사용하는 DNS/socket 없는 `HttpClient` fake입니다.
 *
 * fake는 요청 또는 payload를 보관하지 않습니다. 요청 body의 `contentLength()`를 한 번
 * 읽어 publisher 계약을 검증하고, 응답 status와 cancellation만 호출자에게 전달합니다.
 * `responses`에 넣은 future는 테스트가 delivery를 gate하거나 status를 script하는 용도로
 * 사용할 수 있습니다. queue가 비면 sentinel endpoint에 대한 204 response가 즉시 반환됩니다.
 *
 * 이 구현은 `HttpClient`의 Java 25 lifecycle API도 명시적으로 구현합니다. fake가 만든
 * 작업은 없지만 caller-owned in-flight response future를 `shutdownNow()`에서 취소하고,
 * `awaitTermination`은 지정된 timeout 안에만 기다립니다.
 */
internal class InMemoryAuditHttpClient(
    responses: Iterable<*> = emptyList<Any?>(),
) : HttpClient() {

    private val scriptedResponses = ConcurrentLinkedQueue<CompletableFuture<*>>().apply {
        responses.forEach { response ->
            add(
                when (response) {
                    is CompletableFuture<*> -> response
                    is HttpResponse<*> -> CompletableFuture.completedFuture(response)
                    else -> throw IllegalArgumentException(
                        "responses must contain HttpResponse or CompletableFuture values",
                    )
                },
            )
        }
    }
    private val inFlight = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()
    private val requestCounter = java.util.concurrent.atomic.AtomicInteger()
    private val shuttingDown = java.util.concurrent.atomic.AtomicBoolean()
    private val terminated = java.util.concurrent.atomic.AtomicBoolean()
    private val termination = CountDownLatch(1)

    /** fake에 전달된 request 수입니다. request 자체는 보관하지 않습니다. */
    val requestCount: Int
        get() = requestCounter.get()

    /** 다음 async response future를 thread-safe script queue에 추가합니다. */
    fun enqueue(response: CompletableFuture<*>) {
        check(!shuttingDown.get()) { "in-memory audit HTTP client is shut down" }
        scriptedResponses.add(response)
    }

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))

    override fun followRedirects(): Redirect = Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<Authenticator> = Optional.empty()

    override fun version(): Version = Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()

    override fun <T> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        throw UnsupportedOperationException("in-memory audit HTTP client supports async delivery only")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        validateRequest(request)
        check(!shuttingDown.get()) { "in-memory audit HTTP client is shut down" }

        request.bodyPublisher().ifPresent { it.contentLength() }
        requestCounter.incrementAndGet()

        val response = scriptedResponses.poll() ?: completedResponse<Any?>(status = 204)
        inFlight.add(response)
        response.whenComplete { _, _ ->
            inFlight.remove(response)
            markTerminatedIfIdle()
        }
        if (shuttingDown.get()) {
            response.cancel(true)
            markTerminatedIfIdle()
        }
        return response as CompletableFuture<HttpResponse<T>>
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    /** 새 요청을 막고 기존 scripted response가 완료되기를 기다립니다. */
    override fun shutdown() {
        shuttingDown.set(true)
        markTerminatedIfIdle()
    }

    /** 새 요청을 막고 모든 gated response future를 cancellation으로 완료합니다. */
    override fun shutdownNow() {
        shuttingDown.set(true)
        inFlight.toList().forEach { it.cancel(true) }
        markTerminatedIfIdle()
    }

    /** 지정한 timeout보다 오래 기다리지 않고 종료 여부를 반환합니다. */
    override fun awaitTermination(timeout: Duration): Boolean {
        require(!timeout.isNegative) { "timeout must not be negative" }
        if (terminated.get()) return true
        val nanos = try {
            timeout.toNanos()
        } catch (e: ArithmeticException) {
            Long.MAX_VALUE
        }
        return termination.await(nanos, TimeUnit.NANOSECONDS)
    }

    override fun isTerminated(): Boolean = terminated.get()

    /** fake의 in-flight response를 취소하는 멱등적 close입니다. */
    override fun close() {
        shutdownNow()
        awaitTermination(Duration.ZERO)
    }

    /**
     * 지정한 시간 안에 scripted request 수에 도달할 때까지 기다립니다.
     *
     * 이 helper는 test seam에만 필요한 bounded wait이며 request나 payload를 캡처하지
     * 않습니다.
     */
    fun awaitRequestCount(expected: Int, timeout: Duration) {
        require(expected >= 0) { "expected must not be negative" }
        require(!timeout.isNegative) { "timeout must not be negative" }
        val deadline = boundedDeadline(timeout)
        while (requestCount < expected) {
            if (System.nanoTime() >= deadline) {
                throw TimeoutException("expected at least $expected requests, got $requestCount")
            }
            Thread.onSpinWait()
        }
    }

    /** Kotlin duration overload for tests using `5.seconds` style timeouts. */
    fun awaitRequestCount(expected: Int, timeout: kotlin.time.Duration) {
        require(!timeout.isNegative()) { "timeout must not be negative" }
        val javaTimeout = if (timeout.isInfinite()) {
            Duration.ofNanos(Long.MAX_VALUE)
        } else {
            Duration.ofNanos(timeout.inWholeNanoseconds)
        }
        awaitRequestCount(expected, javaTimeout)
    }

    private fun validateRequest(request: HttpRequest) {
        require(request.headers().firstValue("Authorization").isEmpty) {
            "authorization header is not allowed in the MEMORY audit transport"
        }
    }

    private fun markTerminatedIfIdle() {
        if (shuttingDown.get() && inFlight.isEmpty() && terminated.compareAndSet(false, true)) {
            termination.countDown()
        }
    }

    private fun boundedDeadline(timeout: Duration): Long {
        val now = System.nanoTime()
        val nanos = try {
            timeout.toNanos()
        } catch (e: ArithmeticException) {
            Long.MAX_VALUE
        }
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private companion object {
        val SENTINEL_URI: URI = URI("https://audit.invalid/in-memory")

        private fun <T> completedResponse(status: Int): CompletableFuture<HttpResponse<T>> =
            CompletableFuture.completedFuture(object : HttpResponse<T> {
                override fun statusCode(): Int = status

                override fun request(): HttpRequest = HttpRequest
                    .newBuilder(SENTINEL_URI)
                    .build()

                override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

                override fun headers(): java.net.http.HttpHeaders =
                    java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }

                override fun body(): T? = null

                override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

                override fun uri(): URI = SENTINEL_URI

                override fun version(): Version = Version.HTTP_1_1
            })
    }
}
