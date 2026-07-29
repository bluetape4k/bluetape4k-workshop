package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.env.MockEnvironment
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@Import(OperatorAccessFilterTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("VarCouldBeVal")
internal class OperatorAccessFilterIntegrationTest : AbstractVoucherPoolIntegrationTest() {
    @Test
    fun `operator trust boundary failures are uniform not found and redact credentials`() {
        val path = "/operator/test/probe"
        val secret = "wrong-secret-that-must-not-leak"

        webTestClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Origin", "https://untrusted.example")
            .header(TENANT_HEADER, "tenant-denied")
            .header(OPERATOR_SECRET_HEADER, secret)
            .header(OPERATOR_GUARD_HEADER, "wrong-guard-that-must-not-leak")
            .bodyValue(emptyMap<String, String>())
            .exchange()
            .expectStatus().isNotFound
            .expectHeader().exists(REQUEST_ID_HEADER)
            .expectBody()
            .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
            .jsonPath("$.reason").isEqualTo("resource was not found")
            .jsonPath("$.requestId").isNotEmpty
            .jsonPath("$.retryAfterSeconds").doesNotExist()
            .consumeWith { result ->
                val body = result.responseBody?.decodeToString().orEmpty()
                body shouldNotContain secret
                body shouldNotContain "wrong-guard-that-must-not-leak"
            }

        operatorPost(path, tenant = "tenant-secret", secret = "wrong-secret")
            .exchange().expectStatus().isNotFound
        operatorPost(path, tenant = "tenant-guard", guard = "wrong-guard")
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `operator routes reject origin content type preflight and oversized tenant before dispatch`() {
        val path = "/operator/test/probe"

        operatorPost(path, tenant = "tenant-origin", origin = "https://untrusted.example")
            .exchange().expectStatus().isNotFound
        operatorPost(path, tenant = "tenant-scheme", origin = "https://127.0.0.1:$port")
            .exchange().expectStatus().isNotFound
        webTestClient.post().uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Workshop-Origin", origin())
            .header(TENANT_HEADER, "tenant-fallback-origin")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isNotFound
        operatorPost(path, tenant = "tenant-content", contentType = MediaType.TEXT_PLAIN, body = "{}")
            .exchange().expectStatus().isNotFound
        webTestClient.options().uri(path)
            .header("Origin", origin())
            .header(TENANT_HEADER, "tenant-options")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange().expectStatus().isNotFound
        operatorPost(path, tenant = "t".repeat(65))
            .exchange().expectStatus().isNotFound
    }

    @Test
    fun `operator routes accept same origin GET and JSON POST with valid credentials`() {
        webTestClient.get().uri("/operator/test/probe")
            .header("X-Workshop-Origin", origin())
            .header(TENANT_HEADER, "tenant-safe-get")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("ok")

        operatorPost("/operator/test/probe", tenant = "tenant-safe-post")
            .exchange().expectStatus().isOk
            .expectBody().jsonPath("$.status").isEqualTo("ok")
    }

    @Test
    fun `operator authentication attempts use bounded admission quota`() {
        val boundedFilter =
            OperatorAccessFilter(
                properties,
                mapper,
                VoucherPoolAdmissionGate(
                    backend = null,
                    limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 5),
                ),
                org.springframework.mock.env.MockEnvironment().withProperty("server.address", "127.0.0.1"),
            )

        repeat(5) {
            directFilterRequest("127.0.0.1", "127.0.0.1", boundedFilter).status shouldBeEqualTo 200
        }
        directFilterRequest("127.0.0.1", "127.0.0.1", boundedFilter).status shouldBeEqualTo 404
    }

    @Test
    fun `remote and Host checks use servlet connection metadata instead of forwarding headers`() {
        val remoteResponse =
            directFilterRequest(
                remoteAddress = "198.51.100.10",
                serverName = "127.0.0.1",
                includeForwardingHeaders = true,
            )
        remoteResponse.status shouldBeEqualTo 404

        val hostResponse =
            directFilterRequest(
                remoteAddress = "127.0.0.1",
                serverName = "untrusted.example",
                includeForwardingHeaders = true,
            )
        hostResponse.status shouldBeEqualTo 404
    }

    @Test
    fun `access logs omit credential body and query values`(output: CapturedOutput) {
        operatorPost(
            "/operator/test/probe?token=$QUERY_SECRET",
            tenant = "tenant-log-redaction",
            body = mapOf("payload" to BODY_SECRET),
            secret = HEADER_SECRET,
            guard = GUARD_SECRET,
        ).exchange().expectStatus().isNotFound

        output.all shouldNotContain QUERY_SECRET
        output.all shouldNotContain BODY_SECRET
        output.all shouldNotContain HEADER_SECRET
        output.all shouldNotContain GUARD_SECRET
    }

    @Test
    fun `configured public bind rejects operator even when the connection is loopback`() {
        val publicBindFilter =
            OperatorAccessFilter(
                properties,
                mapper,
                VoucherPoolAdmissionGate(backend = null),
                MockEnvironment().withProperty("server.address", "0.0.0.0"),
            )

        directFilterRequest("127.0.0.1", "127.0.0.1", publicBindFilter).status shouldBeEqualTo 404
    }

    @Test
    fun `missing short and long credentials always compare two fixed length digests`() {
        listOf<String?>(null, "short", "x".repeat(4_096)).forEach { candidate ->
            val comparedLengths = mutableListOf<Pair<Int, Int>>()
            val verifier =
                OperatorCredentialVerifier { actualDigest: ByteArray, expectedDigest: ByteArray ->
                    comparedLengths += actualDigest.size to expectedDigest.size
                    false
                }

            verifier.matches(candidate, OPERATOR_SECRET, candidate, OPERATOR_GUARD).shouldBeFalse()
            comparedLengths shouldBeEqualTo listOf(32 to 32, 32 to 32)
        }
    }

    private fun directFilterRequest(
        remoteAddress: String,
        serverName: String,
        filter: OperatorAccessFilter = operatorAccessFilter,
        includeForwardingHeaders: Boolean = false,
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/operator/test/probe")
        request.remoteAddr = remoteAddress
        request.serverName = serverName
        request.serverPort = port
        request.scheme = "http"
        request.contentType = MediaType.APPLICATION_JSON_VALUE
        request.addHeader("Origin", "http://$serverName:$port")
        request.addHeader(TENANT_HEADER, "tenant-direct-$serverName")
        request.addHeader(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
        request.addHeader(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
        if (includeForwardingHeaders) {
            request.addHeader("Host", "$serverName:$port")
            request.addHeader("X-Forwarded-For", "127.0.0.1")
            request.addHeader("X-Forwarded-Host", "127.0.0.1:$port")
            request.addHeader("X-Forwarded-Proto", "http")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Suppress("LongParameterList")
    private fun operatorPost(
        path: String,
        tenant: String,
        origin: String = origin(),
        contentType: MediaType = MediaType.APPLICATION_JSON,
        body: Any = emptyMap<String, String>(),
        secret: String = OPERATOR_SECRET,
        guard: String = OPERATOR_GUARD,
    ) =
        webTestClient.post().uri(path)
            .contentType(contentType)
            .header("Origin", origin)
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, secret)
            .header(OPERATOR_GUARD_HEADER, guard)
            .bodyValue(body)

    private fun origin(): String = "http://127.0.0.1:$port"

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var operatorAccessFilter: OperatorAccessFilter

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var properties: VoucherPoolProperties

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var mapper: ObjectMapper
}

@TestConfiguration(proxyBeanMethods = false)
internal class OperatorAccessFilterTestConfiguration {
    @Bean
    fun operatorProbeController(): OperatorProbeController = OperatorProbeController()

    @Bean
    @Primary
    fun operatorAccessTestAdmissionGate(): VoucherPoolAdmissionGate =
        VoucherPoolAdmissionGate(
            backend = null,
            limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 100),
        )
}

@RestController
internal class OperatorProbeController {
    @GetMapping("/operator/test/probe")
    fun get(): Map<String, String> = mapOf("status" to "ok")

    @PostMapping("/operator/test/probe")
    fun post(@RequestBody body: Map<String, String>): Map<String, String> =
        mapOf("status" to "ok", "acceptedFields" to body.size.toString())
}

private const val OPERATOR_SECRET = "test-operator-secret-0000000000000001"
private const val OPERATOR_GUARD = "test-voucher-pool-operator-guard"
private const val QUERY_SECRET = "query-sensitive-marker"
private const val BODY_SECRET = "body-sensitive-marker"
private const val HEADER_SECRET = "header-sensitive-marker"
private const val GUARD_SECRET = "guard-sensitive-marker"
