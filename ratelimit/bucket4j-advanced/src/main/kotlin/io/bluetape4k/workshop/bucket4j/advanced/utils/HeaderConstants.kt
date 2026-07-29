package io.bluetape4k.workshop.bucket4j.advanced.utils

/**
 * advanced rate-limit module에서 사용하는 HTTP header 이름 상수입니다.
 */
object HeaderConstants {

    // 들어오는 request header입니다.
    const val X_FORWARDED_FOR = "X-Forwarded-For"
    const val X_REAL_IP = "X-Real-IP"
    const val X_USER_ID = "X-User-ID"

    // response header입니다(HTTP 표준 rate-limit field name).
    const val X_RATELIMIT_REMAINING = "X-RateLimit-Remaining"
    const val X_RATELIMIT_RESET = "X-RateLimit-Reset"
    const val RETRY_AFTER = "Retry-After"
}
