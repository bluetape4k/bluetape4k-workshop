package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

internal class EventSourcedOperatorAccessFilterTest {
    private val filter =
        EventSourcedOperatorAccessFilter(
            EventSourcedOperatorProperties(SECRET, GUARD),
            Jackson.defaultJsonMapper,
        )

    @Test
    fun `valid same origin mutation forwards only the actor surrogate`() {
        val request = validRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        response.status shouldBeEqualTo 200
        chain.request.shouldNotBeNull()
        val surrogate = request.getAttribute(OPERATOR_ACTOR_SURROGATE_ATTRIBUTE).shouldNotBeNull() as String
        surrogate.length shouldBeEqualTo SHA_256_HEX_LENGTH
        surrogate shouldNotBeEqualTo PRINCIPAL
        surrogate.all { character -> character.isDigit() || character in 'a'..'f' }.shouldBeTrue()
    }

    @Test
    fun `operator trust boundary rejects each unsafe request before dispatch without reflecting credentials`() {
        val unsafeMutations: List<(MockHttpServletRequest) -> Unit> =
            listOf(
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

            response.status shouldBeEqualTo 403
            response.contentAsString shouldNotContain SECRET
            response.contentAsString shouldNotContain GUARD
            response.contentAsString shouldNotContain PRINCIPAL
        }
    }

    @Test
    fun `safe operator GET accepts the explicit workshop origin fallback`() {
        val request =
            validRequest(method = "GET").apply {
                removeHeader("Origin")
                addHeader("X-Workshop-Origin", ORIGIN)
                contentType = null
            }
        val chain = MockFilterChain()

        filter.doFilter(request, MockHttpServletResponse(), chain)

        chain.request.shouldNotBeNull()
    }

    private fun validRequest(method: String = "POST"): MockHttpServletRequest =
        MockHttpServletRequest(method, "/operator/api/v1/projections/voucher-lifecycle/rebuilds").apply {
            remoteAddr = "127.0.0.1"
            serverName = "127.0.0.1"
            serverPort = PORT
            contentType = MediaType.APPLICATION_JSON_VALUE
            addHeader("Origin", ORIGIN)
            addHeader(TENANT_HEADER, "tenant-a")
            addHeader(PRINCIPAL_HEADER, PRINCIPAL)
            addHeader(OPERATOR_SECRET_HEADER, SECRET)
            addHeader(OPERATOR_GUARD_HEADER, GUARD)
            addHeader(OPERATOR_ROLE_HEADER, "OPERATOR")
        }

    private fun MockHttpServletRequest.replaceHeader(
        name: String,
        value: String,
    ) {
        removeHeader(name)
        addHeader(name, value)
    }

    private companion object {
        const val PORT = 8080
        const val SHA_256_HEX_LENGTH = 64
        const val SECRET = "operator-secret"
        const val GUARD = "operator-guard"
        const val PRINCIPAL = "operator-principal"
        const val ORIGIN = "http://127.0.0.1:$PORT"
    }
}
