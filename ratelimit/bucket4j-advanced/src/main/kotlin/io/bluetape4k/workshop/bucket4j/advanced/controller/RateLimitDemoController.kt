package io.bluetape4k.workshop.bucket4j.advanced.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 서로 다른 rate-limit 전략으로 보호되는 세 endpoint를 제공하는 demo controller입니다.
 *
 * | Path | Strategy | Required headers |
 * |---|---|---|
 * | `/api/anonymous/hello` | IP-based | none |
 * | `/api/authenticated/hello` | userId-based | `X-User-ID` |
 * | `/api/sensitive/hello` | Combined IP + userId | `X-User-ID` |
 *
 * rate-limit 응답 header(`X-RateLimit-Remaining`, `Retry-After`)는 여기서가 아니라
 * 각 [WebFilter][org.springframework.web.server.WebFilter]에서 설정합니다.
 */
@RestController
class RateLimitDemoController {

    companion object : KLoggingChannel()

    /**
     * 익명 endpoint입니다. IP 기반 rate limiting만 적용합니다.
     *
     * 인증은 필요하지 않습니다. client IP(`ratelimit.trust-proxy` 참고)가 quota를 결정합니다.
     */
    @GetMapping("/api/anonymous/hello")
    suspend fun anonymousHello(): Map<String, String> {
        log.debug { "Handling /api/anonymous/hello" }
        return mapOf(
            "message" to "Hello from anonymous endpoint",
            "strategy" to "IP-based rate limit",
            "timestamp" to Instant.now().toString()
        )
    }

    /**
     * 인증 endpoint입니다. userId 기반 rate limiting을 적용합니다.
     *
     * `X-User-ID` request header가 필요합니다. user ID마다 독립 quota를 가집니다.
     */
    @GetMapping("/api/authenticated/hello")
    suspend fun authenticatedHello(): Map<String, String> {
        log.debug { "Handling /api/authenticated/hello" }
        return mapOf(
            "message" to "Hello from authenticated endpoint",
            "strategy" to "userId-based rate limit",
            "timestamp" to Instant.now().toString()
        )
    }

    /**
     * 민감 endpoint입니다. combined IP+userId rate limiting을 적용합니다.
     *
     * `X-User-ID` request header가 필요합니다. bucket key는 `"combined:$ip:$userId"`이므로
     * 각 (IP, user) 쌍이 독립 quota를 가집니다.
     */
    @GetMapping("/api/sensitive/hello")
    suspend fun sensitiveHello(): Map<String, String> {
        log.debug { "Handling /api/sensitive/hello" }
        return mapOf(
            "message" to "Hello from sensitive endpoint",
            "strategy" to "Combined IP+userId rate limit",
            "timestamp" to Instant.now().toString()
        )
    }
}
