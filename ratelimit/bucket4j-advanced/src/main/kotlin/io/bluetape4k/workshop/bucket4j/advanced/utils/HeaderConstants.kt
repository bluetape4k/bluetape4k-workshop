package io.bluetape4k.workshop.bucket4j.advanced.utils

/**
 * HTTP header name constants used by the advanced rate-limit module.
 */
object HeaderConstants {

    // Incoming request headers
    const val X_FORWARDED_FOR = "X-Forwarded-For"
    const val X_REAL_IP = "X-Real-IP"
    const val X_USER_ID = "X-User-ID"

    // Response headers (HTTP-standard rate-limit field names)
    const val X_RATELIMIT_REMAINING = "X-RateLimit-Remaining"
    const val X_RATELIMIT_RESET = "X-RateLimit-Reset"
    const val RETRY_AFTER = "Retry-After"
}
