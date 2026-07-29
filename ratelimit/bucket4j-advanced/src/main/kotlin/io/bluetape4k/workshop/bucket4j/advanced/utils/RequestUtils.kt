package io.bluetape4k.workshop.bucket4j.advanced.utils

import org.springframework.web.server.ServerWebExchange

/**
 * HTTP request에서 identity key를 추출하는 유틸리티입니다.
 *
 * ## 동작 계약
 * - proxy trust가 켜져 있을 때만 IP 추출에서 `X-Forwarded-For` header를 신뢰합니다.
 *   켜져 있으면 가장 왼쪽(client) IP를 사용하고, 아니면 raw remote address를 사용합니다.
 * - user ID는 `X-User-ID` header에서 추출합니다.
 * - 모든 header 이름은 [HeaderConstants] 상수로 정의합니다.
 */
object RequestUtils {

    /**
     * [exchange]에서 client IP 주소를 추출합니다.
     *
     * [trustProxy]가 `true`이면 `X-Forwarded-For`에서 가장 왼쪽 주소를 반환합니다.
     * 없으면 `X-Real-IP`, TCP remote address 순서로 fallback합니다.
     *
     * **보안 주의**: 신뢰된 proxy에서 온 값인지 검증하지 않고 `X-Forwarded-For`를 신뢰하면
     * client가 IP를 위조할 수 있습니다. application이 알고 있고 통제 가능한 reverse-proxy 뒤에 있을 때만
     * [trustProxy]를 켜세요.
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
     * `X-User-ID` request header에서 user ID를 추출합니다.
     *
     * header가 없거나 blank이면 `null`을 반환합니다.
     */
    fun extractUserId(exchange: ServerWebExchange): String? {
        return exchange.request.headers.getFirst(HeaderConstants.X_USER_ID)
            ?.takeIf { it.isNotBlank() }
    }
}
