package io.bluetape4k.workshop.bucket4j.advanced

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.bucket4j.ratelimit.RateLimitResult
import io.bluetape4k.workshop.bucket4j.advanced.filter.OrRateLimitDecision
import io.bluetape4k.workshop.bucket4j.advanced.filter.decideOrRateLimit
import org.junit.jupiter.api.Test

/**
 * OR filter의 두 limiter 결과 조합 정책을 검증합니다.
 *
 * 두 consume 호출이 원자적이지 않으므로, 혼합 결과의 우선순위를 HTTP filter와
 * 동일한 순수 결정 함수에서 고정합니다.
 */
class OrRateLimitDecisionTest {

    private val consumed = RateLimitResult.consumed(consumedTokens = 1, availableTokens = 10)
    private val rejected = RateLimitResult.rejected(availableTokens = 0)
    private val error = RateLimitResult.error()

    @Test
    fun `rejection takes precedence over an error`() {
        decideOrRateLimit(rejected, error) shouldBeEqualTo OrRateLimitDecision.REJECT
        decideOrRateLimit(error, rejected) shouldBeEqualTo OrRateLimitDecision.REJECT
    }

    @Test
    fun `error without rejection fails open`() {
        decideOrRateLimit(consumed, error) shouldBeEqualTo OrRateLimitDecision.FAIL_OPEN
        decideOrRateLimit(error, consumed) shouldBeEqualTo OrRateLimitDecision.FAIL_OPEN
    }

    @Test
    fun `two consumed results allow the request`() {
        decideOrRateLimit(consumed, consumed) shouldBeEqualTo OrRateLimitDecision.ALLOW
    }
}
