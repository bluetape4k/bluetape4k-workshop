package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucher.config.VoucherProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** operator controller가 호출되기 전에 명시적인 loopback demo trust boundary를 강제합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
internal class OperatorAccessFilter(
    private val properties: VoucherProperties,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/operator/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!allowed(request)) {
            log.warn { "voucher_operator_access_denied reason=TRUST_BOUNDARY requestId=${request.requestId()}" }
            response.status = 403
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            mapper.writeValue(
                response.outputStream,
                ApiError("OPERATOR_ACCESS_DENIED", "operator access denied", request.requestId()),
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun allowed(request: HttpServletRequest): Boolean {
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        val http = properties.http
        if (request.remoteAddr !in LOOPBACK_ADDRESSES) return false
        if (request.serverName.lowercase() !in http.allowedHosts.map(String::lowercase)) return false
        if (!constantTimeEquals(request.getHeader(OPERATOR_SECRET_HEADER), http.operatorSecret)) return false
        if (!constantTimeEquals(request.getHeader(OPERATOR_GUARD_HEADER), http.operatorGuard)) return false
        if (runCatching { requireAsciiIdentifier(request.getHeader(TENANT_HEADER), TENANT_HEADER) }.isFailure) return false
        if (!sameOrigin(request)) return false
        if (request.method !in SAFE_METHODS && request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) != true) {
            return false
        }
        return true
    }

    private fun sameOrigin(request: HttpServletRequest): Boolean {
        request.getHeader("Origin")?.let { return matchesServerOrigin(it, request) }
        if (request.method !in SAFE_METHODS) return false
        val explicitOrigin = request.getHeader("X-Workshop-Origin") ?: return false
        return matchesServerOrigin(explicitOrigin, request)
    }

    private fun matchesServerOrigin(
        value: String,
        request: HttpServletRequest,
    ): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val originPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
        return uri.scheme in setOf("http", "https") &&
            uri.host.equals(request.serverName, ignoreCase = true) &&
            originPort == request.serverPort
    }

    private fun constantTimeEquals(
        actual: String?,
        expected: String,
    ): Boolean {
        val actualBytes = actual?.toByteArray(UTF_8) ?: ByteArray(0)
        return MessageDigest.isEqual(actualBytes, expected.toByteArray(UTF_8))
    }

    private fun HttpServletRequest.requestId(): String =
        getAttribute(REQUEST_ID_ATTRIBUTE) as? String ?: "unavailable"

    companion object : KLogging() {
        private val LOOPBACK_ADDRESSES = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
        private val SAFE_METHODS = setOf("GET", "HEAD")
    }
}
