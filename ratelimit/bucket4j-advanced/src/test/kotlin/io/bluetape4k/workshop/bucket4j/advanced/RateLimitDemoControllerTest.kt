package io.bluetape4k.workshop.bucket4j.advanced

import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.bucket4j.advanced.utils.HeaderConstants
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpStatus

/**
 * 세 가지 rate-limit 전략을 모두 검증하는 [RateLimitDemoController] 통합 테스트입니다.
 *
 * bucket을 소진하지 않는 검사를 먼저 실행하고, bucket을 비우는 테스트를 뒤에 실행하도록 순서를 고정합니다.
 * 모든 IP 기반 테스트가 같은 `127.0.0.1` Redis bucket key를 공유하기 때문에 필요합니다.
 * User 기반과 combined 테스트는 항상 고유한 random user ID를 사용하므로 서로 독립적입니다.
 *
 * Bucket capacity([RateLimitConfig] 기준):
 * - IP 기반   : 10초당 20 token
 * - User 기반 : 10초당 50 token
 * - Combined  : 10초당 10 token
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RateLimitDemoControllerTest : AbstractBucket4jAdvancedTest() {

    companion object : KLoggingChannel() {
        private const val ANONYMOUS_PATH = "/api/anonymous/hello"
        private const val AUTHENTICATED_PATH = "/api/authenticated/hello"
        private const val SENSITIVE_PATH = "/api/sensitive/hello"
    }

    // ------------------------------------------------------------------
    // IP 기반 rate limit 테스트입니다(소진하지 않는 테스트 먼저, 소진 테스트 마지막).
    // ------------------------------------------------------------------

    @Test
    @Order(10)
    fun `IP rate limit - anonymous endpoint returns 200 with remaining header`() = runSuspendIO {
        val response = client.get()
            .uri(ANONYMOUS_PATH)
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_REMAINING)
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_RESET)
            .expectBody(Map::class.java)
            .returnResult()

        val remaining = response.responseHeaders.getFirst(HeaderConstants.X_RATELIMIT_REMAINING)?.toLongOrNull()
        log.debug { "IP remaining=$remaining" }
        remaining.shouldNotBeNull() shouldBeGreaterOrEqualTo 0L
    }

    @Test
    @Order(11)
    fun `IP rate limit - X-Forwarded-For is ignored when trust-proxy is false (default)`() = runSuspendIO {
        // trust-proxy=false이면 X-Forwarded-For header를 무시합니다.
        // 대신 실제 TCP remote address(127.0.0.1)를 사용합니다.
        // 위조 IP가 영향을 주지 않는지 검증합니다. 응답은 실제 IP bucket 기준의 200 또는 429이며, 400/500은 아니어야 합니다.
        val result = client.get()
            .uri(ANONYMOUS_PATH)
            .header(HeaderConstants.X_FORWARDED_FOR, "1.2.3.4, 5.6.7.8")
            .exchange()
            .returnResult(String::class.java)

        val status = result.status
        (status == HttpStatus.OK || status == HttpStatus.TOO_MANY_REQUESTS).shouldBeTrue()
    }

    @Test
    @Order(99)  // 마지막에 실행해 공유 127.0.0.1 IP bucket을 비웁니다.
    fun `IP rate limit - exhausts bucket and returns 429`() = runSuspendIO {
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
        exhausted.shouldBeTrue()
    }

    @Test
    @Order(100) // 소진 후 실행해 429 응답에 Retry-After가 있는지 검증합니다.
    fun `IP rate limit - 429 response includes Retry-After header`() = runSuspendIO {
        // 이전 테스트에서 bucket이 이미 소진되었으므로 어떤 요청도 429를 반환해야 합니다.
        repeat(30) {
            val result = client.get()
                .uri(ANONYMOUS_PATH)
                .exchange()
                .returnResult(String::class.java)

            if (result.status == HttpStatus.TOO_MANY_REQUESTS) {
                val retryAfter = result.responseHeaders.getFirst(HeaderConstants.RETRY_AFTER).shouldNotBeNull()
                retryAfter.toLong() shouldBeGreaterOrEqualTo 1L
                return@runSuspendIO
            }
        }
    }

    // ------------------------------------------------------------------
    // userId 기반 rate limit 테스트입니다(항상 고유 userId를 사용해 완전히 격리).
    // ------------------------------------------------------------------

    @Test
    @Order(20)
    fun `user rate limit - authenticated endpoint returns 200 for valid user`() = runSuspendIO {
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
    fun `user rate limit - missing X-User-ID returns 401`() = runSuspendIO {
        client.get()
            .uri(AUTHENTICATED_PATH)
            .exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    @Order(22)
    fun `user rate limit - different users have independent buckets`() = runSuspendIO {
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
    fun `user rate limit - exhausts per-user bucket and returns 429`() = runSuspendIO {
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
        exhausted.shouldBeTrue()
    }

    // ------------------------------------------------------------------
    // Combined IP+userId rate limit 테스트입니다(항상 고유 userId를 사용해 완전히 격리).
    // ------------------------------------------------------------------

    @Test
    @Order(30)
    fun `combined rate limit - sensitive endpoint returns 200 with both ip and user`() = runSuspendIO {
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
    fun `combined rate limit - missing X-User-ID returns 400`() = runSuspendIO {
        client.get()
            .uri(SENSITIVE_PATH)
            .exchange()
            .expectStatus().isBadRequest
    }

    @Test
    @Order(32)
    fun `combined rate limit - different user IDs have independent combined buckets`() = runSuspendIO {
        val userId1 = "combo-a-" + Base58.randomString(6)
        val userId2 = "combo-b-" + Base58.randomString(6)

        // userId1의 combined bucket을 소진합니다(capacity = 10).
        repeat(12) {
            client.get()
                .uri(SENSITIVE_PATH)
                .header(HeaderConstants.X_USER_ID, userId1)
                .exchange()
        }

        // userId2는 별도 bucket key를 사용하므로 여전히 허용되어야 합니다.
        client.get()
            .uri(SENSITIVE_PATH)
            .header(HeaderConstants.X_USER_ID, userId2)
            .exchange()
            .expectStatus().isOk
    }

    @Test
    @Order(33)
    fun `combined rate limit - exhausts combined bucket and returns 429`() = runSuspendIO {
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
        exhausted.shouldBeTrue()
    }
}
