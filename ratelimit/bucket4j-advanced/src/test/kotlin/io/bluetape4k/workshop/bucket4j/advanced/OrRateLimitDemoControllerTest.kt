package io.bluetape4k.workshop.bucket4j.advanced

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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.returnResult

/**
 * IP와 user ID를 각각 소비하는 OR rate-limit filter의 HTTP 계약을 검증합니다.
 *
 * 이 테스트는 trusted proxy 모드에서 서로 다른 forwarded IP를 사용해 두 축의
 * bucket을 독립적으로 소진시킵니다.
 */
@ActiveProfiles("test")
@TestPropertySource(properties = ["ratelimit.trust-proxy=true"])
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OrRateLimitDemoControllerTest : AbstractBucket4jAdvancedTest() {

    companion object : KLoggingChannel() {
        private const val OR_PATH = "/api/sensitive-or/hello"
    }

    @Test
    @Order(10)
    fun `or rate limit allows request when both buckets have capacity`() = runSuspendIO {
        client.get()
            .uri(OR_PATH)
            .header(HeaderConstants.X_FORWARDED_FOR, "198.51.100.2")
            .header(HeaderConstants.X_USER_ID, "or-allowed-${Base58.randomString(8)}")
            .exchange()
            .expectStatus().isOk
            .expectHeader().exists(HeaderConstants.X_RATELIMIT_REMAINING)
    }

    @Test
    @Order(15)
    fun `or rate limit does not consume the combined bucket`() = runSuspendIO {
        repeat(11) {
            client.get()
                .uri(OR_PATH)
                .header(HeaderConstants.X_FORWARDED_FOR, "198.51.100.10")
                .header(HeaderConstants.X_USER_ID, "or-combined-isolation")
                .exchange()
                .expectStatus().isOk
        }
    }

    @Test
    @Order(20)
    fun `or rate limit rejects when the IP bucket is exhausted`() = runSuspendIO {
        val ip = "198.51.100.3"
        var exhausted = false

        repeat(25) { index ->
            val result = client.get()
                .uri(OR_PATH)
                .header(HeaderConstants.X_FORWARDED_FOR, ip)
                .header(HeaderConstants.X_USER_ID, "or-ip-${index}-${Base58.randomString(6)}")
                .exchange()
                .returnResult<String>()
            val status = result.status

            log.debug { "OR IP rate limit - call=$index status=$status" }
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                result.responseHeaders.getFirst(HeaderConstants.RETRY_AFTER).shouldNotBeNull()
                exhausted = true
                return@repeat
            }
        }

        exhausted.shouldBeTrue()
    }

    @Test
    @Order(30)
    fun `or rate limit rejects when the user bucket is exhausted`() = runSuspendIO {
        val userId = "or-user-${Base58.randomString(8)}"
        var exhausted = false

        repeat(55) { index ->
            val status = client.get()
                .uri(OR_PATH)
                .header(HeaderConstants.X_FORWARDED_FOR, "198.51.${index / 250}.${index % 250 + 1}")
                .header(HeaderConstants.X_USER_ID, userId)
                .exchange()
                .returnResult<String>()
                .status

            log.debug { "OR user rate limit - call=$index status=$status" }
            if (status == HttpStatus.TOO_MANY_REQUESTS) {
                exhausted = true
                return@repeat
            }
        }

        exhausted.shouldBeTrue()
    }

    @Test
    @Order(40)
    fun `or rate limit rejects missing user id`() = runSuspendIO {
        client.get()
            .uri(OR_PATH)
            .header(HeaderConstants.X_FORWARDED_FOR, "198.51.100.250")
            .exchange()
            .expectStatus().isBadRequest
    }
}
