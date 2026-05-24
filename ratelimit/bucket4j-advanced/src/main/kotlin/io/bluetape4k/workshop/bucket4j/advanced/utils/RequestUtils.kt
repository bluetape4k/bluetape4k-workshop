package io.bluetape4k.workshop.bucket4j.advanced.utils

import org.springframework.web.server.ServerWebExchange

/**
 * Utilities for extracting identity keys from HTTP requests.
 *
 * ## Behavior / Contract
 * - IP extraction respects the `X-Forwarded-For` header only when proxy trust is enabled.
 *   When enabled, the leftmost (client) IP is taken; otherwise the raw remote address is used.
 * - User ID is extracted from the `X-User-ID` header.
 * - All header names are defined as constants in [HeaderConstants].
 */
object RequestUtils {

    /**
     * Extracts the client IP address from [exchange].
     *
     * When [trustProxy] is true, the leftmost address in `X-Forwarded-For` is returned.
     * Falls back to `X-Real-IP` then the TCP remote address.
     *
     * **Security note**: trusting `X-Forwarded-For` without verifying it comes from a
     * trusted proxy allows clients to spoof their IP. Enable [trustProxy] only when the
     * application sits behind a known, controlled reverse-proxy.
     */
    fun extractIp(exchange: ServerWebExchange, trustProxy: Boolean = false): String? {
        val request = exchange.request
        if (trustProxy) {
            val forwardedFor = request.headers.getFirst(HeaderConstants.X_FORWARDED_FOR)
            if (!forwardedFor.isNullOrBlank()) {
                return forwardedFor.split(",").first().trim()
            }
            val realIp = request.headers.getFirst(HeaderConstants.X_REAL_IP)
            if (!realIp.isNullOrBlank()) {
                return realIp.trim()
            }
        }
        return request.remoteAddress?.address?.hostAddress
    }

    /**
     * Extracts the user ID from the `X-User-ID` request header.
     *
     * Returns `null` when the header is absent or blank.
     */
    fun extractUserId(exchange: ServerWebExchange): String? {
        return exchange.request.headers.getFirst(HeaderConstants.X_USER_ID)
            ?.takeIf { it.isNotBlank() }
    }
}
