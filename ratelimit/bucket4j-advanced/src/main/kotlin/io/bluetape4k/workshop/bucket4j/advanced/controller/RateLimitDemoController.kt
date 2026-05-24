package io.bluetape4k.workshop.bucket4j.advanced.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * Demo controller with three endpoints, each protected by a distinct rate-limit strategy.
 *
 * | Path | Strategy | Required headers |
 * |---|---|---|
 * | `/api/anonymous/hello` | IP-based | none |
 * | `/api/authenticated/hello` | userId-based | `X-User-ID` |
 * | `/api/sensitive/hello` | Combined IP + userId | `X-User-ID` |
 *
 * Rate-limit response headers (`X-RateLimit-Remaining`, `Retry-After`) are set by
 * the corresponding [WebFilter][org.springframework.web.server.WebFilter], not here.
 */
@RestController
class RateLimitDemoController {

    companion object : KLoggingChannel()

    /**
     * Anonymous endpoint — protected by IP-based rate limiting only.
     *
     * No authentication is required. The client IP (see `ratelimit.trust-proxy`) determines quota.
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
     * Authenticated endpoint — protected by userId-based rate limiting.
     *
     * Requires the `X-User-ID` request header. Each user ID has its own independent quota.
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
     * Sensitive endpoint — protected by combined IP+userId rate limiting.
     *
     * Requires the `X-User-ID` request header. The bucket key is `"combined:$ip:$userId"`,
     * so each (IP, user) pair has its own independent quota.
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
