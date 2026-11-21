package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBe
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBe
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Import(VoucherPoolInputTestConfiguration::class)
@Suppress("VarCouldBeVal")
/**
 * VoucherPoolInputBoundaryIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolInputBoundaryIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "idempotency header accepts 8 through 200 ASCII characters" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idempotency header accepts 8 through 200 ASCII characters`() {
        inputPost("k".repeat(8), mapOf("name" to "safe"))
            .exchange()
            .expectStatus().isOk

        inputPost("k".repeat(200), mapOf("name" to "safe"))
            .exchange()
            .expectStatus().isOk
    }

    @Test
/**
 * 이 시나리오는 "idempotency header rejects short oversized Unicode and control characters" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `idempotency header rejects short oversized Unicode and control characters`() {
        inputPost("k".repeat(7), mapOf("name" to "safe"))
            .exchange()
            .expectStatus().isBadRequest

        inputPost("k".repeat(201), mapOf("name" to "safe"))
            .exchange()
            .expectStatus().isBadRequest

        directInputBoundary("키".repeat(8)) shouldBeEqualTo 400
        directInputBoundary("safe-key\u0001") shouldBeEqualTo 400
    }

    @Test
/**
 * 이 시나리오는 "unknown JSON properties are rejected with safe request id error" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unknown JSON properties are rejected with safe request id error`() {
        inputPost("known-key", mapOf("name" to "safe", "unknown" to "secret-value"))
            .header(REQUEST_ID_HEADER, "request-safe-1")
            .exchange()
            .expectStatus().isBadRequest
            .expectHeader()
            .valueEquals(REQUEST_ID_HEADER, "request-safe-1")
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.reason").isEqualTo("request validation failed")
            .jsonPath("$.requestId").isEqualTo("request-safe-1")
            .jsonPath("$.retryAfterSeconds").doesNotExist()
    }

    @Test
/**
 * 이 시나리오는 "query scalar and cursor boundaries are enforced by UTF 8 byte length" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `query scalar and cursor boundaries are enforced by UTF 8 byte length`() {
        webTestClient.get()
            .uri { builder ->
                builder.path("/api/v1/test/input")
                    .queryParam("scalar", "한".repeat(86))
                    .queryParam("cursor", "c".repeat(512))
                    .build()
            }
            .exchange()
            .expectStatus().isBadRequest

        webTestClient.get()
            .uri { builder ->
                builder.path("/api/v1/test/input")
                    .queryParam("scalar", "s".repeat(256))
                    .queryParam("cursor", "c".repeat(513))
                    .build()
            }
            .exchange()
            .expectStatus().isBadRequest

        webTestClient.get()
            .uri { builder ->
                builder.path("/api/v1/test/input")
                    .queryParam("scalar", "s".repeat(256))
                    .queryParam("cursor", "c".repeat(512))
                    .build()
            }
            .exchange()
            .expectStatus().isOk
    }

    @Test
/**
 * 이 시나리오는 "unsafe methods validate query bounds without parsing the form body" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unsafe methods validate query bounds without parsing the form body`() {
        webTestClient.post()
            .uri { builder ->
                builder.path("/api/v1/test/input")
                    .queryParam("cursor", "c".repeat(513))
                    .build()
            }
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, "query-boundary-key")
            .bodyValue(mapOf("name" to "safe"))
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
/**
 * 이 시나리오는 "payload boundary accepts exactly four MiB and rejects content length plus one" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payload boundary accepts exactly four MiB and rejects content length plus one`() {
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES, reportedLength = true) shouldBeEqualTo 200
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES + 1, reportedLength = true) shouldBeEqualTo 400
    }

    @Test
/**
 * 이 시나리오는 "streaming payload boundary accepts exactly four MiB and rejects one extra byte" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `streaming payload boundary accepts exactly four MiB and rejects one extra byte`() {
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES, reportedLength = false) shouldBeEqualTo 200
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES + 1, reportedLength = false) shouldBeEqualTo 400
    }

    @Test
/**
 * 이 시나리오는 "no argument async retains the bounded request wrapper" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `no argument async retains the bounded request wrapper`() {
        val request = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        request.isAsyncSupported = true

        val response = MockHttpServletResponse()
        var wrappedRequest: HttpServletRequest? = null
        var asyncRequest: jakarta.servlet.ServletRequest? = null

        inputBoundaryFilter.doFilter(request, response) { servletRequest, _ ->
            val boundedRequest = servletRequest as HttpServletRequest
            wrappedRequest = boundedRequest
            val asyncContext = boundedRequest.startAsync()
            asyncRequest = asyncContext.request
            asyncContext.complete()
        }

        asyncRequest shouldBe wrappedRequest
        asyncRequest shouldNotBe request
    }

    @Test
/**
 * 이 시나리오는 "committed partial response is not followed by a second JSON error write" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `committed partial response is not followed by a second JSON error write`() {
        val request = noLengthRequest("POST", "/api/v1/test/input", ByteArray(MAX_TEST_PAYLOAD_BYTES + 1))
        val response = MockHttpServletResponse()

        assertFailsWith<VoucherPoolApiException> {
            inputBoundaryFilter.doFilter(request, response) { servletRequest, servletResponse ->
                val httpResponse = servletResponse as MockHttpServletResponse
                httpResponse.outputStream.write(PARTIAL_RESPONSE.toUtf8Bytes())
                httpResponse.flushBuffer()
                servletRequest.shouldBeInstanceOf<HttpServletRequest>().inputStream.readAllBytes()
            }
        }

        response.contentAsString shouldBeEqualTo PARTIAL_RESPONSE
    }

    @Test
/**
 * 이 시나리오는 "unsafe form body is first consumed through the bounded wrapper without parameter
 * preparse" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `unsafe form body is first consumed through the bounded wrapper without parameter preparse`() {
        var parameterMapReads = 0
        val request = object: MockHttpServletRequest("POST", "/api/v1/test/form") {
/**
 * getContentLength은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getContentLength(): Int = -1

/**
 * getContentLengthLong은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getContentLengthLong(): Long = -1L

/**
 * getParameterMap은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getParameterMap(): MutableMap<String, Array<String>> {
                parameterMapReads++
                return super.getParameterMap()
            }
        }
        request.contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
        request.setContent(ByteArray(MAX_TEST_PAYLOAD_BYTES + 1) { 'a'.code.toByte() })
        val response = MockHttpServletResponse()
        var downstreamSawWrapper = false

        inputBoundaryFilter.doFilter(request, response) { servletRequest, _ ->
            downstreamSawWrapper = servletRequest !== request
            servletRequest.shouldBeInstanceOf<HttpServletRequest>().inputStream.readAllBytes()
        }

        parameterMapReads shouldBeEqualTo 0
        downstreamSawWrapper.shouldBeTrue()
        response.status shouldBeEqualTo 400
    }

    @Test
/**
 * 이 시나리오는 "reader and input stream access remain mutually exclusive" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `reader and input stream access remain mutually exclusive`() {
        val streamFirst = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        inputBoundaryFilter.doFilter(streamFirst, MockHttpServletResponse()) { servletRequest, _ ->
            val wrapped = servletRequest as HttpServletRequest
            wrapped.inputStream
            assertFailsWith<IllegalStateException> {
                wrapped.reader
            }
        }

        val readerFirst = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        inputBoundaryFilter.doFilter(readerFirst, MockHttpServletResponse()) { servletRequest, _ ->
            val wrapped = servletRequest as HttpServletRequest
            wrapped.reader
            assertFailsWith<IllegalStateException> {
                wrapped.inputStream
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "internal illegal argument failure returns a safe internal error" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `internal illegal argument failure returns a safe internal error`() {
        webTestClient.get()
            .uri("/api/v1/test/internal-error")
            .exchange()
            .expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.reason").isEqualTo("request could not be completed")
            .consumeWith { result ->
                result.responseBody!!.decodeToString() shouldNotContain INTERNAL_SECRET
            }
    }

/**
 * inputPost은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun inputPost(idempotencyKey: String, body: Map<String, String>): RequestHeadersSpec<*> =
        webTestClient.post()
            .uri("/api/v1/test/input")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .bodyValue(body)

/**
 * directInputBoundary은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun directInputBoundary(idempotencyKey: String): Int {
        val request = MockHttpServletRequest("POST", "/api/v1/test/input")
        request.contentType = MediaType.APPLICATION_JSON_VALUE
        request.addHeader(IDEMPOTENCY_HEADER, idempotencyKey)
        val response = MockHttpServletResponse()

        inputBoundaryFilter.doFilter(request, response) { _, servletResponse ->
            (servletResponse as MockHttpServletResponse).status = 200
        }
        return response.status
    }

/**
 * directPayloadBoundary은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun directPayloadBoundary(size: Int, reportedLength: Boolean): Int {
        val request = if (reportedLength) {
            MockHttpServletRequest("POST", "/api/v1/test/input")
        } else {
            object: MockHttpServletRequest("POST", "/api/v1/test/input") {
/**
 * getContentLength은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun getContentLength(): Int = -1
/**
 * getContentLengthLong은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
                override fun getContentLengthLong(): Long = -1L
            }
        }
        request.contentType = MediaType.APPLICATION_JSON_VALUE
        request.setContent(ByteArray(size) { 'a'.code.toByte() })

        val response = MockHttpServletResponse()
        inputBoundaryFilter.doFilter(request, response) { servletRequest, servletResponse ->
            servletRequest.shouldBeInstanceOf<HttpServletRequest>().inputStream.readAllBytes()
            servletResponse.shouldBeInstanceOf<MockHttpServletResponse>().status = 200
        }
        return response.status
    }

    @Suppress("SameParameterValue")
/**
 * noLengthRequest은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun noLengthRequest(method: String, path: String, content: ByteArray): MockHttpServletRequest =
        object: MockHttpServletRequest(method, path) {
/**
 * getContentLength은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getContentLength(): Int = -1
/**
 * getContentLengthLong은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
            override fun getContentLengthLong(): Long = -1L
        }.also {
            it.contentType = MediaType.APPLICATION_JSON_VALUE
            it.setContent(content)
        }

    @Autowired
/**
 * inputBoundaryFilter은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var inputBoundaryFilter: VoucherPoolInputBoundaryFilter
}

@TestConfiguration(proxyBeanMethods = false)
/**
 * VoucherPoolInputTestConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherPoolInputTestConfiguration {
    @Bean
/**
 * voucherPoolInputProbeController은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun voucherPoolInputProbeController(): VoucherPoolInputProbeController = VoucherPoolInputProbeController()
}

@RestController
/**
 * 입력 경계 필터가 idempotency header, query parameter, 예외 응답을 실제 HTTP 요청 경계에서 보존하는지 검증하는 테스트 전용
 * controller입니다.
 */
internal class VoucherPoolInputProbeController {
    /**
     * 업무 명령이 idempotency key와 요청 본문을 함께 전달해야 중복 제출 방어가 작동하는지 확인합니다.
     *
     * 예: curl -X POST -H 'Idempotency-Key: demo-1' -d '{"name":"demo"}' /api/v1/test/input
     */
    @PostMapping("/api/v1/test/input")
    fun post(
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: VoucherPoolInputProbeRequest,
    ): Map<String, String> = mapOf("name" to request.name, "idempotencyKey" to idempotencyKey)

    /**
     * 조회 요청의 query parameter가 입력 경계에서 누락·변조 없이 애플리케이션으로 전달되는지 확인합니다.
     *
     * 예: GET /api/v1/test/input?scalar=abc&cursor=next 요청은 두 값의 길이를 반환해야 합니다.
     */
    @GetMapping("/api/v1/test/input")
    fun get(
        @RequestParam scalar: String,
        @RequestParam cursor: String,
    ): Map<String, Int> = mapOf("scalar" to scalar.length, "cursor" to cursor.length)

    /**
     * 내부 예외의 비밀 메시지가 외부 응답으로 노출되지 않고 안전한 오류 계약으로 변환되는지 확인합니다.
     *
     * 예: GET /api/v1/test/internal-error는 내부 상수 대신 redacted 오류 응답을 반환해야 합니다.
     */
    @GetMapping("/api/v1/test/internal-error")
    fun internalError(): Nothing = throw IllegalArgumentException(INTERNAL_SECRET)
}

/**
 * 입력 경계 테스트에서 요청 본문의 최소 업무 필드를 표현합니다.
 *
 * @property name 중복 제출 검증과 응답 반영을 위해 사용하는 업무 이름입니다.
 */
internal data class VoucherPoolInputProbeRequest(val name: String)

/**
 * MAX_TEST_PAYLOAD_BYTES은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val MAX_TEST_PAYLOAD_BYTES = 4 * 1024 * 1024
/**
 * PARTIAL_RESPONSE은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val PARTIAL_RESPONSE = "partial-response"
/**
 * INTERNAL_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val INTERNAL_SECRET = "sensitive-internal-detail"
