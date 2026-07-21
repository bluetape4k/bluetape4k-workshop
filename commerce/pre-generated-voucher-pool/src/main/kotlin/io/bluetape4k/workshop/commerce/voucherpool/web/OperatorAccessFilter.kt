package io.bluetape4k.workshop.commerce.voucherpool.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionDecision
import io.bluetape4k.workshop.commerce.voucherpool.admission.AdmissionNamespace
import io.bluetape4k.workshop.commerce.voucherpool.admission.VoucherPoolAdmissionGate
import io.bluetape4k.workshop.commerce.voucherpool.config.VoucherPoolProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Applies the local operator trust boundary before any operator route is dispatched. */
@Component
@Order(OPERATOR_FILTER_ORDER)
internal class OperatorAccessFilter(
    private val properties: VoucherPoolProperties,
    private val mapper: ObjectMapper,
    private val admissionGate: VoucherPoolAdmissionGate,
    private val environment: Environment,
) : OncePerRequestFilter() {
    private val credentialVerifier = OperatorCredentialVerifier()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/operator/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!allowed(request)) {
            log.warn { "voucher_pool_operator_access_denied requestId=${request.requestId()}" }
            mapper.writeApiError(response, resourceNotFound(), request.requestId())
            return
        }
        filterChain.doFilter(request, response)
    }

    @Suppress("ReturnCount")
    private fun allowed(request: HttpServletRequest): Boolean {
        val http = properties.http
        val tenantCandidate = request.getHeader(TENANT_HEADER)
        if (!admitted(request.remoteAddr)) return false
        if (request.method.equals("OPTIONS", ignoreCase = true)) return false
        if (!environment.getProperty("server.address", "127.0.0.1").isLoopbackHost()) return false
        if (!request.remoteAddr.isLoopbackHost()) return false
        if (request.serverName.lowercase() !in http.allowedHosts.map(String::lowercase)) return false

        if (!credentialVerifier.matches(
                request.getHeader(OPERATOR_SECRET_HEADER),
                http.operatorSecret,
                request.getHeader(OPERATOR_GUARD_HEADER),
                http.operatorGuard,
            )
        ) {
            return false
        }
        if (runCatching { requireBoundedAscii(tenantCandidate, 1, http.maxTenantLength) }.isFailure) return false
        if (!sameOrigin(request)) return false
        val hasJsonContentType = request.contentType?.startsWith(MediaType.APPLICATION_JSON_VALUE) == true
        if (request.method !in SAFE_METHODS && !hasJsonContentType) {
            return false
        }
        return true
    }

    private fun admitted(remoteAddress: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(remoteAddress.toByteArray(UTF_8))
        return admissionGate.admit(AdmissionNamespace.OPERATOR_AUTH, digest) in ADMITTED_DECISIONS
    }

    @Suppress("ReturnCount")
    private fun sameOrigin(request: HttpServletRequest): Boolean {
        request.getHeader("Origin")?.let { return matchesServerOrigin(it, request) }
        if (request.method !in SAFE_METHODS) return false
        return request.getHeader("X-Workshop-Origin")?.let { matchesServerOrigin(it, request) } ?: false
    }

    @Suppress("ComplexCondition", "ReturnCount")
    private fun matchesServerOrigin(value: String, request: HttpServletRequest): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val containsNonOriginParts =
            uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.path !in ORIGIN_PATHS
        if (containsNonOriginParts) return false
        val originPort = if (uri.port >= 0) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
        return uri.scheme.equals(request.scheme, ignoreCase = true) &&
            uri.host.equals(request.serverName, ignoreCase = true) &&
            originPort == request.serverPort
    }

    companion object : KLogging() {
        private val SAFE_METHODS = setOf("GET", "HEAD")
        private val ORIGIN_PATHS = setOf("", "/")
        private val ADMITTED_DECISIONS = setOf(AdmissionDecision.ALLOW, AdmissionDecision.DEGRADED_ALLOW)
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
    }
}

private const val OPERATOR_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10

internal fun interface FixedLengthDigestComparator {
    fun matches(actualDigest: ByteArray, expectedDigest: ByteArray): Boolean
}

/** Hashes both credentials before comparison so missing and variable-length inputs share a fixed comparison shape. */
internal class OperatorCredentialVerifier(
    private val comparator: FixedLengthDigestComparator = FixedLengthDigestComparator(MessageDigest::isEqual),
) {
    fun matches(
        actualSecret: String?,
        expectedSecret: String,
        actualGuard: String?,
        expectedGuard: String,
    ): Boolean {
        val secretMatches = comparator.matches(digest(actualSecret), digest(expectedSecret))
        val guardMatches = comparator.matches(digest(actualGuard), digest(expectedGuard))
        return secretMatches && guardMatches
    }

    private fun digest(value: String?): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.orEmpty().toByteArray(UTF_8))
}
