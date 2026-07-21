package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets.UTF_8

@Import(VoucherPoolInputTestConfiguration::class)
@Suppress("VarCouldBeVal")
internal class VoucherPoolInputBoundaryIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Test
    fun `idempotency header accepts 8 through 200 ASCII characters`() {
        inputPost("k".repeat(8), mapOf("name" to "safe"))
            .exchange().expectStatus().isOk
        inputPost("k".repeat(200), mapOf("name" to "safe"))
            .exchange().expectStatus().isOk
    }

    @Test
    fun `idempotency header rejects short oversized Unicode and control characters`() {
        inputPost("k".repeat(7), mapOf("name" to "safe"))
            .exchange().expectStatus().isBadRequest
        inputPost("k".repeat(201), mapOf("name" to "safe"))
            .exchange().expectStatus().isBadRequest
        directInputBoundary("키".repeat(8)) shouldBeEqualTo 400
        directInputBoundary("safe-key\u0001") shouldBeEqualTo 400
    }

    @Test
    fun `unknown JSON properties are rejected with safe request id error`() {
        inputPost("known-key", mapOf("name" to "safe", "unknown" to "secret-value"))
            .header(REQUEST_ID_HEADER, "request-safe-1")
            .exchange().expectStatus().isBadRequest
            .expectHeader().valueEquals(REQUEST_ID_HEADER, "request-safe-1")
            .expectBody()
            .jsonPath("$.code").isEqualTo("INVALID_REQUEST")
            .jsonPath("$.reason").isEqualTo("request validation failed")
            .jsonPath("$.requestId").isEqualTo("request-safe-1")
            .jsonPath("$.retryAfterSeconds").doesNotExist()
    }

    @Test
    fun `query scalar and cursor boundaries are enforced by UTF 8 byte length`() {
        webTestClient.get().uri { builder ->
            builder.path("/api/v1/test/input")
                .queryParam("scalar", "한".repeat(86))
                .queryParam("cursor", "c".repeat(512))
                .build()
        }.exchange().expectStatus().isBadRequest

        webTestClient.get().uri { builder ->
            builder.path("/api/v1/test/input")
                .queryParam("scalar", "s".repeat(256))
                .queryParam("cursor", "c".repeat(513))
                .build()
        }.exchange().expectStatus().isBadRequest

        webTestClient.get().uri { builder ->
            builder.path("/api/v1/test/input")
                .queryParam("scalar", "s".repeat(256))
                .queryParam("cursor", "c".repeat(512))
                .build()
        }.exchange().expectStatus().isOk
    }

    @Test
    fun `unsafe methods validate query bounds without parsing the form body`() {
        webTestClient.post().uri { builder ->
            builder.path("/api/v1/test/input")
                .queryParam("cursor", "c".repeat(513))
                .build()
        }
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, "query-boundary-key")
            .bodyValue(mapOf("name" to "safe"))
            .exchange().expectStatus().isBadRequest
    }

    @Test
    fun `payload boundary accepts exactly four MiB and rejects content length plus one`() {
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES, reportedLength = true) shouldBeEqualTo 200
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES + 1, reportedLength = true) shouldBeEqualTo 400
    }

    @Test
    fun `streaming payload boundary accepts exactly four MiB and rejects one extra byte`() {
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES, reportedLength = false) shouldBeEqualTo 200
        directPayloadBoundary(MAX_TEST_PAYLOAD_BYTES + 1, reportedLength = false) shouldBeEqualTo 400
    }

    @Test
    fun `no argument async retains the bounded request wrapper`() {
        val request = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        request.isAsyncSupported = true
        val response = MockHttpServletResponse()
        var wrappedRequest: HttpServletRequest? = null
        var asyncRequest: jakarta.servlet.ServletRequest? = null

        inputBoundaryFilter.doFilter(request, response, FilterChain { servletRequest, _ ->
            val boundedRequest = servletRequest as HttpServletRequest
            wrappedRequest = boundedRequest
            val asyncContext = boundedRequest.startAsync()
            asyncRequest = asyncContext.request
            asyncContext.complete()
        })

        (asyncRequest === wrappedRequest) shouldBeEqualTo true
        (asyncRequest !== request) shouldBeEqualTo true
    }

    @Test
    fun `committed partial response is not followed by a second JSON error write`() {
        val request = noLengthRequest("POST", "/api/v1/test/input", ByteArray(MAX_TEST_PAYLOAD_BYTES + 1))
        val response = MockHttpServletResponse()

        assertFailsWith<VoucherPoolApiException> {
            inputBoundaryFilter.doFilter(request, response, FilterChain { servletRequest, servletResponse ->
                val httpResponse = servletResponse as MockHttpServletResponse
                httpResponse.outputStream.write(PARTIAL_RESPONSE.toByteArray(UTF_8))
                httpResponse.flushBuffer()
                (servletRequest as HttpServletRequest).inputStream.readAllBytes()
            })
        }

        response.contentAsString shouldBeEqualTo PARTIAL_RESPONSE
    }

    @Test
    fun `unsafe form body is first consumed through the bounded wrapper without parameter preparse`() {
        var parameterMapReads = 0
        val request =
            object : MockHttpServletRequest("POST", "/api/v1/test/form") {
                override fun getContentLength(): Int = -1

                override fun getContentLengthLong(): Long = -1L

                override fun getParameterMap(): MutableMap<String, Array<String>> {
                    parameterMapReads++
                    return super.getParameterMap()
                }
            }
        request.contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
        request.setContent(ByteArray(MAX_TEST_PAYLOAD_BYTES + 1) { 'a'.code.toByte() })
        val response = MockHttpServletResponse()
        var downstreamSawWrapper = false

        inputBoundaryFilter.doFilter(request, response, FilterChain { servletRequest, _ ->
            downstreamSawWrapper = servletRequest !== request
            (servletRequest as HttpServletRequest).inputStream.readAllBytes()
        })

        parameterMapReads shouldBeEqualTo 0
        downstreamSawWrapper shouldBeEqualTo true
        response.status shouldBeEqualTo 400
    }

    @Test
    fun `reader and input stream access remain mutually exclusive`() {
        val streamFirst = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        inputBoundaryFilter.doFilter(streamFirst, MockHttpServletResponse(), FilterChain { servletRequest, _ ->
            val wrapped = servletRequest as HttpServletRequest
            wrapped.inputStream
            assertFailsWith<IllegalStateException> { wrapped.reader }
        })

        val readerFirst = noLengthRequest("POST", "/api/v1/test/input", ByteArray(1))
        inputBoundaryFilter.doFilter(readerFirst, MockHttpServletResponse(), FilterChain { servletRequest, _ ->
            val wrapped = servletRequest as HttpServletRequest
            wrapped.reader
            assertFailsWith<IllegalStateException> { wrapped.inputStream }
        })
    }

    @Test
    fun `internal illegal argument failure returns a safe internal error`() {
        webTestClient.get().uri("/api/v1/test/internal-error")
            .exchange().expectStatus().is5xxServerError
            .expectBody()
            .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
            .jsonPath("$.reason").isEqualTo("request could not be completed")
            .consumeWith { result ->
                result.responseBody?.decodeToString().orEmpty().contains(INTERNAL_SECRET) shouldBeEqualTo false
            }
    }

    private fun inputPost(idempotencyKey: String, body: Map<String, String>) =
        webTestClient.post().uri("/api/v1/test/input")
            .contentType(MediaType.APPLICATION_JSON)
            .header(IDEMPOTENCY_HEADER, idempotencyKey)
            .bodyValue(body)

    private fun directInputBoundary(idempotencyKey: String): Int {
        val request = MockHttpServletRequest("POST", "/api/v1/test/input")
        request.contentType = MediaType.APPLICATION_JSON_VALUE
        request.addHeader(IDEMPOTENCY_HEADER, idempotencyKey)
        val response = MockHttpServletResponse()
        inputBoundaryFilter.doFilter(request, response, FilterChain { _, servletResponse ->
            (servletResponse as MockHttpServletResponse).status = 200
        })
        return response.status
    }

    private fun directPayloadBoundary(size: Int, reportedLength: Boolean): Int {
        val request =
            if (reportedLength) {
                MockHttpServletRequest("POST", "/api/v1/test/input")
            } else {
                object : MockHttpServletRequest("POST", "/api/v1/test/input") {
                    override fun getContentLength(): Int = -1

                    override fun getContentLengthLong(): Long = -1L
                }
            }
        request.contentType = MediaType.APPLICATION_JSON_VALUE
        request.setContent(ByteArray(size) { 'a'.code.toByte() })
        val response = MockHttpServletResponse()
        inputBoundaryFilter.doFilter(request, response, FilterChain { servletRequest, servletResponse ->
            (servletRequest as HttpServletRequest).inputStream.readAllBytes()
            (servletResponse as MockHttpServletResponse).status = 200
        })
        return response.status
    }

    private fun noLengthRequest(method: String, path: String, content: ByteArray): MockHttpServletRequest =
        object : MockHttpServletRequest(method, path) {
            override fun getContentLength(): Int = -1

            override fun getContentLengthLong(): Long = -1L
        }.also {
            it.contentType = MediaType.APPLICATION_JSON_VALUE
            it.setContent(content)
        }

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var inputBoundaryFilter: VoucherPoolInputBoundaryFilter
}

@TestConfiguration(proxyBeanMethods = false)
internal class VoucherPoolInputTestConfiguration {
    @Bean
    fun voucherPoolInputProbeController(): VoucherPoolInputProbeController = VoucherPoolInputProbeController()
}

@RestController
internal class VoucherPoolInputProbeController {
    @PostMapping("/api/v1/test/input")
    fun post(
        @RequestHeader(IDEMPOTENCY_HEADER) idempotencyKey: String,
        @RequestBody request: VoucherPoolInputProbeRequest,
    ): Map<String, String> = mapOf("name" to request.name, "idempotencyKey" to idempotencyKey)

    @GetMapping("/api/v1/test/input")
    fun get(
        @RequestParam scalar: String,
        @RequestParam cursor: String,
    ): Map<String, Int> = mapOf("scalar" to scalar.length, "cursor" to cursor.length)

    @GetMapping("/api/v1/test/internal-error")
    fun internalError(): Nothing = throw IllegalArgumentException(INTERNAL_SECRET)
}

internal data class VoucherPoolInputProbeRequest(val name: String)

private const val MAX_TEST_PAYLOAD_BYTES = 4 * 1024 * 1024
private const val PARTIAL_RESPONSE = "partial-response"
private const val INTERNAL_SECRET = "sensitive-internal-detail"
