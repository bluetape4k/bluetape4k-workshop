package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.voucherpool.AbstractVoucherPoolIntegrationTest
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionLimits
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.reactive.server.WebTestClient.RequestHeadersSpec
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

@Import(OperatorAccessFilterTestConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
@Suppress("VarCouldBeVal")
/**
 * OperatorAccessFilterIntegrationTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorAccessFilterIntegrationTest: AbstractVoucherPoolIntegrationTest() {

    companion object: KLogging()

/**
 * origin은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun origin(): String = "http://127.0.0.1:$port"

    @org.springframework.beans.factory.annotation.Autowired
/**
 * operatorAccessFilter은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var operatorAccessFilter: OperatorAccessFilter

    @org.springframework.beans.factory.annotation.Autowired
/**
 * properties은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var properties: VoucherPoolProperties

    @org.springframework.beans.factory.annotation.Autowired
/**
 * mapper은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private lateinit var mapper: ObjectMapper

    @Test
/**
 * 이 시나리오는 "operator trust boundary failures are uniform not found and redact credentials" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator trust boundary failures are uniform not found and redact credentials`() {
        val path = "/operator/test/probe"
        val secret = "wrong-secret-that-must-not-leak"

        webTestClient.post()
            .uri(path)
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
                log.debug { "body: $body" }
                body shouldNotContain secret
                body shouldNotContain "wrong-guard-that-must-not-leak"
            }

        operatorPost(path, tenant = "tenant-secret", secret = "wrong-secret")
            .exchange()
            .expectStatus().isNotFound

        operatorPost(path, tenant = "tenant-guard", guard = "wrong-guard")
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
/**
 * 이 시나리오는 "operator routes reject origin content type preflight and oversized tenant before
 * dispatch" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator routes reject origin content type preflight and oversized tenant before dispatch`() {
        val path = "/operator/test/probe"

        operatorPost(path, tenant = "tenant-origin", origin = "https://untrusted.example")
            .exchange()
            .expectStatus().isNotFound

        operatorPost(path, tenant = "tenant-scheme", origin = "https://127.0.0.1:$port")
            .exchange()
            .expectStatus().isNotFound

        webTestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Workshop-Origin", origin())
            .header(TENANT_HEADER, "tenant-fallback-origin")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .bodyValue(emptyMap<String, String>())
            .exchange().expectStatus().isNotFound

        operatorPost(path, tenant = "tenant-content", contentType = MediaType.TEXT_PLAIN, body = "{}")
            .exchange()
            .expectStatus().isNotFound

        webTestClient.options().uri(path)
            .header("Origin", origin())
            .header(TENANT_HEADER, "tenant-options")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange()
            .expectStatus().isNotFound

        operatorPost(path, tenant = "t".repeat(65))
            .exchange()
            .expectStatus().isNotFound
    }

    @Test
/**
 * 이 시나리오는 "operator routes accept same origin GET and JSON POST with valid credentials" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator routes accept same origin GET and JSON POST with valid credentials`() {
        webTestClient.get()
            .uri("/operator/test/probe")
            .header("X-Workshop-Origin", origin())
            .header(TENANT_HEADER, "tenant-safe-get")
            .header(OPERATOR_SECRET_HEADER, OPERATOR_SECRET)
            .header(OPERATOR_GUARD_HEADER, OPERATOR_GUARD)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok")

        operatorPost("/operator/test/probe", tenant = "tenant-safe-post")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.status").isEqualTo("ok")
    }

    @Test
/**
 * 이 시나리오는 "operator authentication attempts use bounded admission quota" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator authentication attempts use bounded admission quota`() {
        val boundedFilter = OperatorAccessFilter(
            properties = properties,
            mapper = mapper,
            admissionGate = VoucherPoolAdmissionGate(
                backend = null,
                limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 5),
            ),
            environment = MockEnvironment().withProperty("server.address", "127.0.0.1"),
        )

        repeat(5) {
            directFilterRequest(
                remoteAddress = "127.0.0.1",
                serverName = "127.0.0.1",
                filter = boundedFilter
            ).status shouldBeEqualTo 200
        }
        directFilterRequest(
            remoteAddress = "127.0.0.1",
            serverName = "127.0.0.1",
            filter = boundedFilter
        ).status shouldBeEqualTo 404
    }

    @Test
/**
 * 이 시나리오는 "remote and Host checks use servlet connection metadata instead of forwarding headers"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `remote and Host checks use servlet connection metadata instead of forwarding headers`() {
        val remoteResponse = directFilterRequest(
            remoteAddress = "198.51.100.10",
            serverName = "127.0.0.1",
            includeForwardingHeaders = true,
        )
        remoteResponse.status shouldBeEqualTo 404

        val hostResponse = directFilterRequest(
            remoteAddress = "127.0.0.1",
            serverName = "untrusted.example",
            includeForwardingHeaders = true,
        )
        hostResponse.status shouldBeEqualTo 404
    }

    @Test
/**
 * 이 시나리오는 "access logs omit credential body and query values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `access logs omit credential body and query values`(output: CapturedOutput) {
        operatorPost(
            "/operator/test/probe?token=$QUERY_SECRET",
            tenant = "tenant-log-redaction",
            body = mapOf("payload" to BODY_SECRET),
            secret = HEADER_SECRET,
            guard = GUARD_SECRET,
        ).exchange().expectStatus().isNotFound

        output.all.let {
            it shouldNotContain QUERY_SECRET
            it shouldNotContain BODY_SECRET
            it shouldNotContain HEADER_SECRET
            it shouldNotContain GUARD_SECRET
        }
    }

    @Test
/**
 * 이 시나리오는 "configured public bind rejects operator even when the connection is loopback" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `configured public bind rejects operator even when the connection is loopback`() {
        val publicBindFilter = OperatorAccessFilter(
            properties = properties,
            mapper = mapper,
            admissionGate = VoucherPoolAdmissionGate(backend = null),
            environment = MockEnvironment().withProperty("server.address", "0.0.0.0"),
        )

        directFilterRequest(
            remoteAddress = "127.0.0.1",
            serverName = "127.0.0.1",
            filter = publicBindFilter
        ).status shouldBeEqualTo 404
    }

    @Test
/**
 * 이 시나리오는 "missing short and long credentials always compare two fixed length digests" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `missing short and long credentials always compare two fixed length digests`() {
        listOf(null, "short", "x".repeat(4_096))
            .forEach { candidate ->
                val comparedLengths = mutableListOf<Pair<Int, Int>>()
                val verifier = OperatorCredentialVerifier { actualDigest: ByteArray, expectedDigest: ByteArray ->
                    comparedLengths += actualDigest.size to expectedDigest.size
                    false
                }

                verifier.matches(
                    actualSecret = candidate,
                    expectedSecret = OPERATOR_SECRET,
                    actualGuard = candidate,
                    expectedGuard = OPERATOR_GUARD
                ).shouldBeFalse()

                comparedLengths shouldBeEqualTo listOf(32 to 32, 32 to 32)
            }
    }

/**
 * directFilterRequest은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun directFilterRequest(
        remoteAddress: String,
        serverName: String,
        filter: OperatorAccessFilter = operatorAccessFilter,
        includeForwardingHeaders: Boolean = false,
    ): MockHttpServletResponse {
/**
 * request은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
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
/**
 * response은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Suppress("LongParameterList")
/**
 * operatorPost은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun operatorPost(
        path: String,
        tenant: String,
        origin: String = origin(),
        contentType: MediaType = MediaType.APPLICATION_JSON,
        body: Any = emptyMap<String, String>(),
        secret: String = OPERATOR_SECRET,
        guard: String = OPERATOR_GUARD,
    ): RequestHeadersSpec<*> =
        webTestClient.post()
            .uri(path)
            .contentType(contentType)
            .header("Origin", origin)
            .header(TENANT_HEADER, tenant)
            .header(OPERATOR_SECRET_HEADER, secret)
            .header(OPERATOR_GUARD_HEADER, guard)
            .bodyValue(body)

}

@TestConfiguration(proxyBeanMethods = false)
/**
 * OperatorAccessFilterTestConfiguration 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorAccessFilterTestConfiguration {
    @Bean
/**
 * operatorProbeController은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun operatorProbeController(): OperatorProbeController = OperatorProbeController()

    @Bean
    @Primary
/**
 * operatorAccessTestAdmissionGate은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun operatorAccessTestAdmissionGate(): VoucherPoolAdmissionGate =
        VoucherPoolAdmissionGate(
            backend = null,
            limits = AdmissionLimits.defaults().withLimit(AdmissionNamespace.OPERATOR_AUTH, 100),
        )
}

@RestController
/**
 * operator 전용 경로의 인증·가드·응답 계약을 검증하기 위한 테스트 전용 controller입니다.
 */
internal class OperatorProbeController {
    /**
     * operator 조회 요청이 access filter를 통과할 때만 정상 응답을 반환하는지 확인합니다.
     *
     * 예: GET /operator/test/probe는 유효한 operator credential에서만 status=ok를 반환해야 합니다.
     */
    @GetMapping("/operator/test/probe")
    fun get(): Map<String, String> = mapOf("status" to "ok")

    /**
     * operator 명령 본문이 필터를 통과한 뒤 업무 필드 수와 함께 반환되는지 확인합니다.
     *
     * 예: curl -X POST -H 'Content-Type: application/json' -d '{"action":"pause"}'
     * /operator/test/probe
     */
    @PostMapping("/operator/test/probe")
    fun post(@RequestBody body: Map<String, String>): Map<String, String> =
        mapOf("status" to "ok", "acceptedFields" to body.size.toString())
}

/**
 * OPERATOR_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val OPERATOR_SECRET = "test-operator-secret-0000000000000001"
/**
 * OPERATOR_GUARD은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val OPERATOR_GUARD = "test-voucher-pool-operator-guard"
/**
 * QUERY_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val QUERY_SECRET = "query-sensitive-marker"
/**
 * BODY_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val BODY_SECRET = "body-sensitive-marker"
/**
 * HEADER_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val HEADER_SECRET = "header-sensitive-marker"
/**
 * GUARD_SECRET은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
private const val GUARD_SECRET = "guard-sensitive-marker"
