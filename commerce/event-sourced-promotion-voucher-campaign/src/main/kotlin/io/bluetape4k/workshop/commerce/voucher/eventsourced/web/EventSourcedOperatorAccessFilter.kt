package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireNotNull
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

internal const val OPERATOR_SECRET_HEADER = "X-Workshop-Operator-Secret"
internal const val OPERATOR_GUARD_HEADER = "X-Workshop-Operator-Guard"
internal const val OPERATOR_ROLE_HEADER = "X-Workshop-Operator-Role"
internal const val OPERATOR_ACTOR_SURROGATE_ATTRIBUTE = "eventSourcedOperatorActorSurrogate"
private const val FILTER_ORDER_OFFSET = 10

@ConfigurationProperties("voucher.operator")
internal data class EventSourcedOperatorProperties(
    val secret: String,
    val guard: String,
    val allowedHosts: Set<String> = setOf("127.0.0.1", "localhost"),
) {
    init {
        secret.requireNotBlank("voucher.operator.secret")
        guard.requireNotBlank("voucher.operator.guard")
        allowedHosts.requireNotEmpty("voucher.operator.allowed-hosts").requireNotNull("voucher.operator.allowed-hosts")
        allowedHosts.forEach { host -> host.requireNotBlank("voucher.operator.allowed-hosts") }
    }
}

/** Fail-closed workshop trust boundary for mutation-capable operator routes. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + FILTER_ORDER_OFFSET)
internal class EventSourcedOperatorAccessFilter(
    private val properties: EventSourcedOperatorProperties,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/operator/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!isAllowed(request)) {
            log.warn { "event_sourced_operator_access_denied reason=TRUST_BOUNDARY" }
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(
                response.outputStream,
                EventSourcedApiError("OPERATOR_ACCESS_DENIED", "operator access denied"),
            )
            return
        }
        request.setAttribute(
            OPERATOR_ACTOR_SURROGATE_ATTRIBUTE,
            digest(request.singleHeader(PRINCIPAL_HEADER).requireNotNull(PRINCIPAL_HEADER)),
        )
        filterChain.doFilter(request, response)
    }

    private fun isAllowed(request: HttpServletRequest): Boolean =
        hasSingleValuedHeaders(request) &&
            request.method in ALLOWED_METHODS &&
            request.remoteAddr in LOOPBACK_ADDRESSES &&
            request.serverName.lowercase() in properties.allowedHosts.map(String::lowercase) &&
            constantTimeEquals(request.singleHeader(OPERATOR_SECRET_HEADER), properties.secret) &&
            constantTimeEquals(request.singleHeader(OPERATOR_GUARD_HEADER), properties.guard) &&
            request.singleHeader(OPERATOR_ROLE_HEADER) == "OPERATOR" &&
            !request.singleHeader(TENANT_HEADER).isNullOrBlank() &&
            !request.singleHeader(PRINCIPAL_HEADER).isNullOrBlank() &&
            sameOrigin(request) &&
            hasSafeContentType(request)

    private fun hasSingleValuedHeaders(request: HttpServletRequest): Boolean =
        TRUST_HEADERS.all { header -> request.getHeaders(header).toList().size <= 1 }

    private fun hasSafeContentType(request: HttpServletRequest): Boolean =
        request.method != "POST" ||
            request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true

    private fun sameOrigin(request: HttpServletRequest): Boolean {
        val origin =
            request.singleHeader("Origin")
                ?: request.singleHeader("X-Workshop-Origin")
        val uri = origin?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
        return uri.scheme in ALLOWED_SCHEMES &&
            uri.host.equals(request.serverName, ignoreCase = true) &&
            port == request.serverPort
    }

    private fun constantTimeEquals(
        actual: String?,
        expected: String,
    ): Boolean =
        MessageDigest.isEqual(
            actual?.toByteArray(UTF_8) ?: ByteArray(0),
            expected.toByteArray(UTF_8),
        )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("voucher-operator-v1\u0000$value".toByteArray(UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object : KLogging() {
        const val HTTP_PORT = 80
        const val HTTPS_PORT = 443
        val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        val ALLOWED_METHODS = setOf("GET", "HEAD", "POST")
        val ALLOWED_SCHEMES = setOf("http", "https")
        val TRUST_HEADERS =
            setOf(
                OPERATOR_SECRET_HEADER,
                OPERATOR_GUARD_HEADER,
                OPERATOR_ROLE_HEADER,
                TENANT_HEADER,
                PRINCIPAL_HEADER,
                "Origin",
                "X-Workshop-Origin",
            )
    }
}

private fun HttpServletRequest.singleHeader(name: String): String? =
    getHeaders(name).toList().singleOrNull()
