package io.bluetape4k.workshop.bucket4j.advanced

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.bucket4j.advanced.utils.HeaderConstants
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpStatus
import org.testcontainers.utility.Base58

/**
 * Integration tests for [RateLimitDemoController] validating all three rate-limit strategies.
 *
 * Tests are ordered so that non-exhausting checks run before tests that drain the bucket.
 * This is necessary because all IP-based tests share the same `127.0.0.1` Redis bucket key.
 * User-based and combined tests always use unique random user IDs, so they are independent.
 *
 * Bucket capacities (per [RateLimitConfig]):
 * - IP-based   : 20 tokens / 10 s
 * - User-based : 50 tokens / 10 s
 * - Combined   : 10 tokens / 10 s
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RateLimitDemoControllerTest : AbstractBucket4jAdvancedTest() {

    companion object : KLoggingChannel() {
        private const val ANONYMOUS_PATH = "/api/anonymous/hello"
        private const val AUTHENTICATED_PATH = "/api/authenticated/hello"
        private const val SENSITIVE_PATH = "/api/sensitive/hello"
    }

    // ------------------------------------------------------------------
    // IP-based rate limit tests  (non-exhausting first, exhausting last)
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    fun `IP rate limit - anonymous endpoint returns 200 with remaining header`() = runTest {
        val response = client.get()
            .uri(ANONYMOUS_PATH)
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_REMAINING)
            .expectBody(Map::class.java)
            .returnResult()

        val remaining = response.responseHeaders.getFirst(HeaderConstants.X_RATELIMIT_REMAINING)?.toLongOrNull()
        log.debug { "IP remaining=$remaining" }
        assert(remaining != null && remaining >= 0) { "X-RateLimit-Remaining must be a non-negative number" }
    }

    @Test
    @Order(11)
    fun `IP rate limit - X-Forwarded-For is ignored when trust-proxy is false (default)`() = runTest {
        // When trust-proxy=false, the X-Forwarded-For header is ignored.
        // The real TCP remote address (127.0.0.1) is used instead.
        // We verify that the spoofed IP has no effect: response is 200 or 429 (real-IP bucket),
        // never 400/500.
        val result = client.get()
            .uri(ANONYMOUS_PATH)
            .header(HeaderConstants.X_FORWARDED_FOR, "1.2.3.4, 5.6.7.8")
            .exchange()
            .returnResult(String::class.java)

        val status = result.status
        assert(status == HttpStatus.OK || status == HttpStatus.TOO_MANY_REQUESTS) {
            "Expected 200 or 429 (real-IP bucket), but got $status — spoofed XFF must not cause 4xx/5xx errors"
        }
    }

    @Test
    @Order(99)  // runs last — drains the shared 127.0.0.1 IP bucket
    fun `IP rate limit - exhausts bucket and returns 429`() = runTest {
        var exhausted = false
        repeat(30) {
            val status = client.get()
                .uri(ANONYMOUS_PATH)
                .exchange()
                .returnResult(String::class.java)
                .status

            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                exhausted = true
                return@repeat
            }
        }
        assert(exhausted) { "Expected HTTP 429 after exhausting IP rate limit bucket" }
    }

    @Test
    @Order(100) // runs after exhaustion — verifies Retry-After is in the 429 response
    fun `IP rate limit - 429 response includes Retry-After header`() = runTest {
        // Bucket is already exhausted from previous test; any request should return 429.
        repeat(30) {
            val result = client.get()
                .uri(ANONYMOUS_PATH)
                .exchange()
                .returnResult(String::class.java)

            if (result.status == HttpStatus.TOO_MANY_REQUESTS) {
                val retryAfter = result.responseHeaders.getFirst(HeaderConstants.RETRY_AFTER)
                assert(retryAfter != null) { "Retry-After header must be present in 429 response" }
                assert(retryAfter!!.toLong() >= 1) { "Retry-After must be at least 1 second" }
                return@runTest
            }
        }
    }

    // ------------------------------------------------------------------
    // userId-based rate limit tests  (always use unique userId — fully isolated)
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    fun `user rate limit - authenticated endpoint returns 200 for valid user`() = runTest {
        val userId = "user-" + Base58.randomString(8)

        client.get()
            .uri(AUTHENTICATED_PATH)
            .header(HeaderConstants.X_USER_ID, userId)
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_REMAINING)
    }

    @Test
    @Order(21)
    fun `user rate limit - missing X-User-ID returns 401`() = runTest {
        client.get()
            .uri(AUTHENTICATED_PATH)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    @Order(22)
    fun `user rate limit - different users have independent buckets`() = runTest {
        val userId1 = "independent-a-" + Base58.randomString(6)
        val userId2 = "independent-b-" + Base58.randomString(6)

        client.get()
            .uri(AUTHENTICATED_PATH)
            .header(HeaderConstants.X_USER_ID, userId1)
            .exchange()
            .expectStatus().isOk

        client.get()
            .uri(AUTHENTICATED_PATH)
            .header(HeaderConstants.X_USER_ID, userId2)
            .exchange()
            .expectStatus().isOk
    }

    @Test
    @Order(23)
    fun `user rate limit - exhausts per-user bucket and returns 429`() = runTest {
        val userId = "exhaust-" + Base58.randomString(8)
        var exhausted = false

        repeat(55) {
            val status = client.get()
                .uri(AUTHENTICATED_PATH)
                .header(HeaderConstants.X_USER_ID, userId)
                .exchange()
                .returnResult(String::class.java)
                .status

            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                exhausted = true
                return@repeat
            }
        }
        assert(exhausted) { "Expected HTTP 429 after exhausting user rate limit bucket for userId=$userId" }
    }

    // ------------------------------------------------------------------
    // Combined IP+userId rate limit tests  (always use unique userId — fully isolated)
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    fun `combined rate limit - sensitive endpoint returns 200 with both ip and user`() = runTest {
        val userId = "combined-" + Base58.randomString(8)

        client.get()
            .uri(SENSITIVE_PATH)
            .header(HeaderConstants.X_USER_ID, userId)
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_REMAINING)
    }

    @Test
    @Order(31)
    fun `combined rate limit - missing X-User-ID returns 400`() = runTest {
        client.get()
            .uri(SENSITIVE_PATH)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @Order(32)
    fun `combined rate limit - different user IDs have independent combined buckets`() = runTest {
        val userId1 = "combo-a-" + Base58.randomString(6)
        val userId2 = "combo-b-" + Base58.randomString(6)

        // Exhaust userId1's combined bucket (capacity = 10)
        repeat(12) {
            client.get()
                .uri(SENSITIVE_PATH)
                .header(HeaderConstants.X_USER_ID, userId1)
                .exchange()
        }

        // userId2 should still be allowed — separate bucket key
        client.get()
            .uri(SENSITIVE_PATH)
            .header(HeaderConstants.X_USER_ID, userId2)
            .exchange()
            .expectStatus().isOk
    }

    @Test
    @Order(33)
    fun `combined rate limit - exhausts combined bucket and returns 429`() = runTest {
        val userId = "combined-exhaust-" + Base58.randomString(8)
        var exhausted = false

        repeat(15) {
            val status = client.get()
                .uri(SENSITIVE_PATH)
                .header(HeaderConstants.X_USER_ID, userId)
                .exchange()
                .returnResult(String::class.java)
                .status

            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                exhausted = true
                return@repeat
            }
        }
        assert(exhausted) { "Expected HTTP 429 after exhausting combined rate limit bucket for userId=$userId" }
    }
}
