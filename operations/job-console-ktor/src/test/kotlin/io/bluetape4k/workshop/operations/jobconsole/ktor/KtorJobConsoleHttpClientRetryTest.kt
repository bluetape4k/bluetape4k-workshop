package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSession
import kotlin.collections.ArrayDeque

/** Ktor caller retry가 transient status, Retry-After, bounded delay, interruption에서 안전하게 멈추는지 검증한다. */
/** Ktor caller retry가 transient status, Retry-After, bounded delay, interruption에서 안전하게 멈추는지 검증한다. */
class KtorJobConsoleHttpClientRetryTest {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "submission retry evidence records transient statuses and bounded delay" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "submission stops after the maximum transient retry budget" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "retry-after rejects negative values and clamps excessive values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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

    @Test
/**
 * 이 시나리오는 "default retry delay propagates interruption before another submission" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default retry delay propagates interruption before another submission`() {
        val firstRequest = CountDownLatch(1)
        val calls = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val client = KtorJobConsoleHttpClient(
            baseUri = URI("http://localhost"),
            sendRequest = {
                if (calls.incrementAndGet() == 1) {
                    firstRequest.countDown()
                    response(429, retryAfter = "30")
                } else {
                    response(202)
                }
            },
        )
        val worker = Thread {
            try {
                client.submit(JobConsoleScenario())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        worker.isDaemon = true
        worker.start()
        try {
            firstRequest.await(5, TimeUnit.SECONDS).shouldBeTrue()
            worker.interrupt()
            worker.join(2_000)

            worker.isAlive shouldBeEqualTo false
            failure.get().shouldBeInstanceOf<InterruptedException>()
            calls.get() shouldBeEqualTo 1
        } finally {
            if (worker.isAlive) {
                worker.interrupt()
                worker.join(2_000)
            }
        }
    }

/**
 * response은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun response(
        status: Int,
        retryAfter: String? = null,
    ): HttpResponse<String> {
/**
 * headers은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val headers = retryAfter?.let { mapOf("Retry-After" to listOf(it)) }.orEmpty()
        return object: HttpResponse<String> {
/**
 * statusCode은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun statusCode(): Int = status

/**
 * request은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun request(): HttpRequest? = null

/**
 * previousResponse은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

/**
 * headers은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }

/**
 * body은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun body(): String = "{\"jobId\":\"11111111-1111-1111-1111-111111111111\",\"state\":\"accepted\"}"

/**
 * sslSession은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun sslSession(): Optional<SSLSession> = Optional.empty()

/**
 * uri은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun uri(): URI = URI("http://localhost/v1/jobs")

/**
 * version은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }
    }
}
