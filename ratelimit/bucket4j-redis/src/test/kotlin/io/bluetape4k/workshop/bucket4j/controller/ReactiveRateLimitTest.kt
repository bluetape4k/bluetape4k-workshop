package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.bucket4j.AbstractRateLimitTest
import org.junit.jupiter.api.Test

class ReactiveRateLimitTest : AbstractRateLimitTest() {

    companion object : KLoggingChannel() {
        private const val HELLO_PATH = "/reactive/hello"
        private const val WORLD_PATH = "/reactive/world"
    }

    @Test
    fun `call reactive hello with rate limit`() {
        val limit = 5
        repeat(limit) {
            successfulWebRequest(HELLO_PATH, limit - 1 - it)
        }

        blockedWebRequestDueToRateLimit(HELLO_PATH)
    }

    @Test
    fun `call reactive world with rate limit`() {
        val limit = 10
        repeat(limit) {
            successfulWebRequest(WORLD_PATH, limit - 1 - it)
        }

        blockedWebRequestDueToRateLimit(WORLD_PATH)
    }
}
