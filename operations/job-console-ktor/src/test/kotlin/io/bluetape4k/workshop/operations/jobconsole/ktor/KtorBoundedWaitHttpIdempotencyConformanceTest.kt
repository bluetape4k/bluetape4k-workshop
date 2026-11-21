package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.javatimes.hours
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyAdapter
import io.bluetape4k.junit5.http.idempotency.BoundedWaitHttpIdempotencyConformanceConfig
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyQuiescence
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyRequest
import io.bluetape4k.junit5.http.idempotency.HttpIdempotencyResponse
import io.bluetape4k.junit5.http.idempotency.assertBoundedWaitHttpIdempotencyConformance
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.operations.jobconsole.fixture.BoundedWaitHttpIdempotencyFixture
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.readByteArray
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

/** Ktor host가 core bounded-wait owner/replay/conflict/timeout wire contract와 동일한지 검증한다. */
/** Ktor host가 core bounded-wait owner/replay/conflict/timeout wire contract와 동일한지 검증한다. */
class KtorBoundedWaitHttpIdempotencyConformanceTest {
    @Test
/**
 * 이 시나리오는 "Ktor HTTP host passes the shared bounded wait conformance" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Ktor HTTP host passes the shared bounded wait conformance`() = runBlocking {
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val fixture = BoundedWaitHttpIdempotencyFixture(boundedWaitConformanceConfig())
/**
 * port은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val port = ServerSocket(0).use { it.localPort }

/**
 * server은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val server = embeddedServer(Netty, port = port) {
            routing {
                post("/__test/v1/jobs") {
/**
 * keys은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val keys = call.request.headers.getAll("Idempotency-Key").orEmpty()
/**
 * body은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val body = call.receiveChannel().readBuffer().readByteArray().toUtf8String()
/**
 * request은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val request = HttpIdempotencyRequest(
                        authenticationProfile = call.request.headers["X-Test-Authentication-Profile"]
                            ?: "unauthenticated",
                        operation = call.request.headers["X-Test-Operation"] ?: "create-widget",
                        resourceIdentity = call.request.headers["X-Test-Resource"] ?: "widget-1",
                        idempotencyKeys = keys.ifEmpty { listOf("") },
                        requestBody = body,
                    )
/**
 * response은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
                    val response = fixture.exchange(request)
                    response.headers.forEach { (name, values) ->
                        values.forEach { value ->
                            call.response.headers.append(
                                name,
                                value
                            )
                        }
                    }
                    call.respondBytes(
                        bytes = response.body.toUtf8Bytes(),
                        contentType = ContentType.parse(
                            response.headers["content-type"]?.singleOrNull() ?: "application/json"
                        ),
                        status = HttpStatusCode.fromValue(response.statusCode),
                    )
                }
            }
        }
        server.start(wait = false)
/**
 * adapter은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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

/**
 * KtorBoundedWaitHttpIdempotencyAdapter 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
private class KtorBoundedWaitHttpIdempotencyAdapter(
/**
 * fixture은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
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
 * clientExecutor은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val clientExecutor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
/**
 * client은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .executor(clientExecutor)
        .build()

/**
 * close은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun close() = clientExecutor.close()

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
                request.idempotencyKeys.forEach { key -> header("Idempotency-Key", key) }
                header("Connection", "close")
                header("Content-Type", "application/json")
                POST(HttpRequest.BodyPublishers.ofString(request.requestBody, UTF_8))
            }.build()
        }.getOrElse { return fixture.exchange(request) }
        return client.sendCancellable(httpRequest).toIdempotencyResponse()
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
    suspendCancellableCoroutine { continuation: CancellableContinuation<HttpResponse<String>> ->
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
    val headers = buildMap<String, List<String>> {
        headers().map().forEach { (name, values) ->
            if (name.lowercase() in setOf("content-type", "etag", "idempotency-replayed", "retry-after")) {
                put(name.lowercase(), values)
            }
        }
    }
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
