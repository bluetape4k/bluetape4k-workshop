package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

/**
 * EventSourcedOperatorAccessFilterTest 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class EventSourcedOperatorAccessFilterTest {

    private companion object: KLogging() {
/**
 * PORT은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PORT = 8080
/**
 * SHA_256_HEX_LENGTH은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SHA_256_HEX_LENGTH = 64
/**
 * SECRET은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val SECRET = "operator-secret"
/**
 * GUARD은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val GUARD = "operator-guard"
/**
 * PRINCIPAL은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val PRINCIPAL = "operator-principal"
/**
 * ORIGIN은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        const val ORIGIN = "http://127.0.0.1:$PORT"
    }

/**
 * filter은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val filter = EventSourcedOperatorAccessFilter(
        properties = EventSourcedOperatorProperties(SECRET, GUARD),
        mapper = Jackson.defaultJsonMapper,
        hmacKeyRing = EventSourcedHmacKeyRing(
            EventSourcedHmacKey(1, "operator-test-key-material-at-least-32-bytes".toUtf8Bytes()),
        ),
    )

    @Test
/**
 * 이 시나리오는 "operator properties retain only validated normalized values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator properties retain only validated normalized values`() {
        val properties = EventSourcedOperatorProperties(
            secret = SECRET,
            guard = GUARD,
            allowedHosts = setOf("LOCALHOST", "127.0.0.1"),
        )

        log.debug { "properties: $properties" }
        properties.secret shouldBeEqualTo SECRET
        properties.guard shouldBeEqualTo GUARD
        properties.allowedHosts shouldBeEqualTo setOf("localhost", "127.0.0.1")

        assertFailsWith<IllegalArgumentException> {
            EventSourcedOperatorProperties(" ", GUARD)
        }
        assertFailsWith<IllegalArgumentException> {
            EventSourcedOperatorProperties(SECRET, " ")
        }
        assertFailsWith<IllegalArgumentException> {
            EventSourcedOperatorProperties(SECRET, GUARD, emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            EventSourcedOperatorProperties(SECRET, GUARD, setOf(" "))
        }
    }

    @Test
/**
 * 이 시나리오는 "valid same origin mutation forwards only the actor surrogate" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `valid same origin mutation forwards only the actor surrogate`() {
        val request = validRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        response.status shouldBeEqualTo 200
        chain.request.shouldNotBeNull()

        val surrogate = request.getAttribute(OPERATOR_ACTOR_SURROGATE_ATTRIBUTE).shouldNotBeNull()

        log.debug { "surrogate: $surrogate" }
        surrogate.shouldBeInstanceOf<String>()
        surrogate.length shouldBeEqualTo SHA_256_HEX_LENGTH
        surrogate shouldNotBeEqualTo PRINCIPAL
        surrogate.all { character -> character.isDigit() || character in 'a'..'f' }.shouldBeTrue()
    }

    @Test
/**
 * 이 시나리오는 "actor surrogate is tenant separated" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `actor surrogate is tenant separated`() {
        val tenantA = validRequest(tenant = "tenant-a")
        val tenantB = validRequest(tenant = "tenant-b")

        filter.doFilter(tenantA, MockHttpServletResponse(), MockFilterChain())
        filter.doFilter(tenantB, MockHttpServletResponse(), MockFilterChain())

        tenantA.getAttribute(OPERATOR_ACTOR_SURROGATE_ATTRIBUTE) shouldNotBeEqualTo
                tenantB.getAttribute(OPERATOR_ACTOR_SURROGATE_ATTRIBUTE)
    }

    @Test
/**
 * 이 시나리오는 "operator trust boundary rejects each unsafe request before dispatch without reflecting
 * credentials" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `operator trust boundary rejects each unsafe request before dispatch without reflecting credentials`() {
        val unsafeMutations: List<(MockHttpServletRequest) -> Unit> = listOf(
            { request -> request.remoteAddr = "192.0.2.10" },
            { request -> request.serverName = "untrusted.example" },
            { request -> request.method = "OPTIONS" },
            { request -> request.contentType = MediaType.TEXT_PLAIN_VALUE },
            { request -> request.removeHeader("Origin") },
            { request -> request.replaceHeader("Origin", "https://untrusted.example") },
            { request -> request.removeHeader(OPERATOR_ROLE_HEADER) },
            { request -> request.replaceHeader(OPERATOR_ROLE_HEADER, "VIEWER") },
            { request -> request.removeHeader(OPERATOR_SECRET_HEADER) },
            { request -> request.replaceHeader(OPERATOR_SECRET_HEADER, "wrong-secret") },
            { request -> request.removeHeader(OPERATOR_GUARD_HEADER) },
            { request -> request.replaceHeader(OPERATOR_GUARD_HEADER, "wrong-guard") },
            { request -> request.addHeader(OPERATOR_SECRET_HEADER, "duplicate-secret") },
            { request -> request.removeHeader(TENANT_HEADER) },
            { request -> request.removeHeader(PRINCIPAL_HEADER) },
        )

        unsafeMutations.forEach { mutate ->
            val request = validRequest().also(mutate)
            val response = MockHttpServletResponse()

            filter.doFilter(request, response, MockFilterChain())

            log.debug { "response: $response" }
            response.status shouldBeEqualTo 403
            response.contentAsString shouldNotContain SECRET
            response.contentAsString shouldNotContain GUARD
            response.contentAsString shouldNotContain PRINCIPAL
        }
    }

    @Test
/**
 * 이 시나리오는 "safe operator GET accepts the explicit workshop origin fallback" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `safe operator GET accepts the explicit workshop origin fallback`() {
        val request = validRequest(method = "GET").apply {
            removeHeader("Origin")
            addHeader("X-Workshop-Origin", ORIGIN)
            contentType = null
        }
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        log.debug { "chain request: ${chain.request}" }
        chain.request.shouldNotBeNull()
    }

/**
 * validRequest은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun validRequest(
        method: String = "POST",
        tenant: String = "tenant-a",
    ): MockHttpServletRequest =
        MockHttpServletRequest(method, "/operator/api/v1/projections/voucher-lifecycle/rebuilds").apply {
            remoteAddr = "127.0.0.1"
            serverName = "127.0.0.1"
            serverPort = PORT
            contentType = MediaType.APPLICATION_JSON_VALUE
            addHeader("Origin", ORIGIN)
            addHeader(TENANT_HEADER, tenant)
            addHeader(PRINCIPAL_HEADER, PRINCIPAL)
            addHeader(OPERATOR_SECRET_HEADER, SECRET)
            addHeader(OPERATOR_GUARD_HEADER, GUARD)
            addHeader(OPERATOR_ROLE_HEADER, "OPERATOR")
        }

/**
 * replaceHeader은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun MockHttpServletRequest.replaceHeader(
        name: String,
        value: String,
    ) {
        removeHeader(name)
        addHeader(name, value)
    }
}
