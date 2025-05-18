package io.bluetape4k.workshop.bucket4j.hazelcast.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.bucket4j.hazelcast.AbstractRateLimitTest
import org.junit.jupiter.api.Test

class CoroutinesRateLimitTest: AbstractRateLimitTest() {

    companion object: KLoggingChannel()

    @Test
    fun `call hello with rate limit`() {
        val url = "/coroutines/hello"
        val limit = 5
        repeat(limit) {
            successfulWebRequest(url, limit - 1 - it)
        }

        blockedWebRequestDueToRateLimit(url)
    }

    @Test
    fun `call world with rate limit`() {
        val url = "/coroutines/world"
        val limit = 10
        repeat(limit) {
            successfulWebRequest(url, limit - 1 - it)
        }

        blockedWebRequestDueToRateLimit(url)
    }
}
