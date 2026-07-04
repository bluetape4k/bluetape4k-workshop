package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class CoroutineController {

    companion object : KLoggingChannel()

    /**
     * Applies rate limiting to `/api/v1/coroutines/...`.
     */
    @GetMapping("/api/v1/coroutines/hello")
    suspend fun helloV1(): String {
        return "Hello World V1 at " + Instant.now().toString()
    }

    /**
     * Leaves `/api/v2/coroutines/...` outside Bucket4j rate limiting.
     */
    @GetMapping("/api/v2/coroutines/hello")
    suspend fun helloV2(): String {
        return "Hello World V2 at " + Instant.now().toString()
    }
}
