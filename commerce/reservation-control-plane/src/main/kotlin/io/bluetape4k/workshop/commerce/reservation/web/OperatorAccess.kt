package io.bluetape4k.workshop.commerce.reservation.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.commerce.reservation.application.ReservationCredentialService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Precomputes the configured operator HMAC and compares request credentials in constant time. */
@Component
@ConditionalOnProperty(prefix = "reservation.operator", name = ["enabled"], havingValue = "true")
internal class OperatorPrincipalResolver(
    private val credentials: ReservationCredentialService,
    @Value("\${reservation.operator.key}") configuredKey: String,
) {
    private val expectedDigest =
        credentials.operatorDigest(
            configuredKey.also {
                require(it.length >= 32) { "operator key must contain at least 32 characters" }
            }
        )

    fun authorized(rawKey: String?): Boolean = rawKey?.let { credentials.matchesOperator(it, expectedDigest) } == true

    companion object : KLogging()
}

/** Rejects unauthorized operator routes before request bodies reach command controllers. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "reservation.operator", name = ["enabled"], havingValue = "true")
internal class OperatorAuthorizationFilter(
    private val principals: OperatorPrincipalResolver,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/operator/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        if (!principals.authorized(request.getHeader(OPERATOR_KEY_HEADER))) {
            log.warn { "reservation_operator_rejected reason=INVALID_CREDENTIAL" }
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        chain.doFilter(request, response)
    }

    companion object : KLogging() {
        private const val OPERATOR_KEY_HEADER = "X-Operator-Key"
    }
}
