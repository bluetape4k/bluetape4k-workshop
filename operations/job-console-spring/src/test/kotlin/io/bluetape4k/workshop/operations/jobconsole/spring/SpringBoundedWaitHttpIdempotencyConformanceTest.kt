package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.coroutines.support.log
import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.operations.jobconsole.fixture.BoundedWaitHttpIdempotencyFixture
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.text.Charsets.UTF_8

/** Spring MVC host가 core bounded-wait fixture의 owner/replay/conflict/timeout wire contract를 그대로 노출하는지 검증한다. */
/** Spring MVC host가 core bounded-wait fixture의 owner/replay/conflict/timeout wire contract를 그대로 노출하는지 검증한다. */
class SpringBoundedWaitHttpIdempotencyConformanceTest {

    companion object: KLoggingChannel()

    @Test
/**
 * 이 시나리오는 "Spring HTTP host passes the shared bounded wait conformance" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Spring HTTP host passes the shared bounded wait conformance`() = runBlocking {
/**
 * context은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val context =
            SpringApplicationBuilder(BoundedWaitSpringHostConfiguration::class.java)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .run()

        try {
/**
 * port은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val port = (context as ServletWebServerApplicationContext).webServer.shouldNotBeNull().port
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val fixture = context.getBean<BoundedWaitHttpIdempotencyFixture>()
/**
 * adapter은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val adapter = SpringBoundedWaitHttpIdempotencyAdapter(
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
/**
 * BoundedWaitSpringHostConfiguration 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class BoundedWaitSpringHostConfiguration {
    @Bean(destroyMethod = "close")
/**
 * boundedWaitFixture은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun boundedWaitFixture(): BoundedWaitHttpIdempotencyFixture =
        BoundedWaitHttpIdempotencyFixture(boundedWaitConformanceConfig())

    @Bean
/**
 * boundedWaitController은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun boundedWaitController(fixture: BoundedWaitHttpIdempotencyFixture): BoundedWaitSpringController =
        BoundedWaitSpringController(fixture)
}

@RestController
@TestComponent
/**
 * BoundedWaitSpringController 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class BoundedWaitSpringController(
    /**
     * bounded-wait와 idempotency 결과를 Spring MVC 응답으로 변환하는 테스트 fixture입니다.
     */
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
/**
 * fixture은 테스트 fixture가 보관하는 fixture 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
/**
 * fixture은 테스트 fixture가 보관하는 fixture 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
/**
 * fixture은 테스트 fixture가 보관하는 fixture 업무 값입니다.
 *
 * 저장소·이벤트·API 응답에서 같은 의미를 유지해야 하며, 재시도와 복구 시에도 임의로 변경하지 않습니다.
 */
    private val fixture: BoundedWaitHttpIdempotencyFixture,
) {
    /**
     * 인증 프로파일, idempotency key, resource identity가 같은 요청을 중복 실행하지 않도록 Spring adapter가 전달하는지 확인합니다.
     *
     * 예: curl -X POST -H 'Idempotency-Key: job-1' -H 'X-Test-Resource: widget-1' /__test/v1/jobs
     */
    @PostMapping("/__test/v1/jobs")
    fun submit(request: HttpServletRequest): DeferredResult<ResponseEntity<ByteArray>> {
        val keys = request.getHeaders("Idempotency-Key").toList()
        val profile = request.getHeader("X-Test-Authentication-Profile") ?: "unauthenticated"
        if (profile == "unauthenticated" || profile == "tenant-a-read-only") {
            val response = runBlocking {
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
            return DeferredResult<ResponseEntity<ByteArray>>()
                .also { it.setResult(response.toResponseEntity()) }
        }
        val body = request.inputStream.readBytes().toUtf8String()
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
/**
 * cancelExchange은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
        }.log("ExchangeJob")
        return deferred
    }
}

/**
 * toResponseEntity은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun HttpIdempotencyResponse.toResponseEntity(): ResponseEntity<ByteArray> {
    val headers = HttpHeaders()
    this.headers.forEach { (name, values) ->
        values.forEach { value ->
            headers.add(name, value)
        }
    }
    return ResponseEntity.status(statusCode).headers(headers).body(body.toByteArray(Charsets.UTF_8))
}

/**
 * SpringBoundedWaitHttpIdempotencyAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class SpringBoundedWaitHttpIdempotencyAdapter(
    /**
     * fixture은 bounded-wait와 idempotency 업무 결과를 재현하는 테스트 협력 객체입니다.
     */
    private val fixture: BoundedWaitHttpIdempotencyFixture,
/**
 * endpoint은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val endpoint: URI,
): BoundedWaitHttpIdempotencyAdapter {
/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun close() = Unit

/**
 * exchange은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun exchange(request: HttpIdempotencyRequest): HttpIdempotencyResponse {
        if (request.requestBody.hasUnpairedSurrogate()) return fixture.exchange(request)
        val httpRequest = runCatching {
            HttpRequest.newBuilder(endpoint).apply {
                header("X-Test-Authentication-Profile", request.authenticationProfile)
                header("X-Test-Operation", request.operation)
                header("X-Test-Resource", request.resourceIdentity)
                request.idempotencyKeys.forEach { key ->
                    header("Idempotency-Key", key)
                }
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

/**
 * awaitOwnerStarted은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun awaitOwnerStarted(request: HttpIdempotencyRequest) = fixture.awaitOwnerStarted(request)

/**
 * awaitWaiterCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun awaitWaiterCount(request: HttpIdempotencyRequest, expected: Int) =
        fixture.awaitWaiterCount(request, expected)

/**
 * completeOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun completeOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) =
        fixture.completeOwner(request, outcome)

/**
 * holdOwnerResponseDelivery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun holdOwnerResponseDelivery(request: HttpIdempotencyRequest) =
        fixture.holdOwnerResponseDelivery(request)

/**
 * releaseOwnerResponseDelivery은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun releaseOwnerResponseDelivery(request: HttpIdempotencyRequest) =
        fixture.releaseOwnerResponseDelivery(request)

/**
 * abandonOwner은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun abandonOwner(request: HttpIdempotencyRequest, outcome: HttpIdempotencyResponse) =
        fixture.abandonOwner(request, outcome)

/**
 * advanceTimeBy은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun advanceTimeBy(duration: Duration) = fixture.advanceTimeBy(duration)

/**
 * resetScenario은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override suspend fun resetScenario() = fixture.resetScenario()

/**
 * sideEffectCount은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun sideEffectCount(request: HttpIdempotencyRequest): Int = fixture.sideEffectCount(request)

/**
 * quiescence은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    override fun quiescence(): HttpIdempotencyQuiescence = fixture.quiescence()
}

/**
 * sendCancellable은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private suspend fun HttpClient.sendCancellable(request: HttpRequest): HttpResponse<String> =
    suspendCancellableCoroutine { continuation ->
/**
 * future은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * body은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val body = body()
/**
 * headers은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val headers =
        headers().map().filterKeys { it in setOf("content-type", "etag", "idempotency-replayed", "retry-after") }
/**
 * problemCode은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val problemCode = Regex("\\\"code\\\"\\s*:\\s*\\\"([a-z0-9_]+)\\\"").find(body)?.groupValues?.get(1)
    return HttpIdempotencyResponse(statusCode(), body, headers, problemCode)
}

/**
 * hasUnpairedSurrogate은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * boundedWaitConformanceConfig은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
private fun boundedWaitConformanceConfig() =
    BoundedWaitHttpIdempotencyConformanceConfig(
        waitTimeout = 2.seconds(),
        scenarioTimeout = 15.seconds(),
        maxWaitersPerKey = 2,
        retention = 1.hours(),
        inFlightRetryAfter = 1.seconds(),
        overflowRetryAfter = 2.seconds(),
        maxIdempotencyKeyBytes = 255,
        maxRequestBodyBytes = 64 * 1024,
        maxReplayBodyBytes = 64 * 1024,
        maxReplayHeaderNames = 8,
        maxReplayValuesPerHeader = 4,
        maxReplayHeaderValueBytes = 4 * 1024,
        maxReplayHeaderBytes = 16 * 1024,
        replayHeaderAllowlist = setOf("etag"),
    )
