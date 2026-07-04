package io.bluetape4k.workshop.bucket4j.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.time.Instant

@RestController
class ReactiveController {

    companion object : KLoggingChannel()

    /**
     * Applies rate limiting to `/api/v1/reactive/...`.
     */
    @GetMapping("/api/v1/reactive/hello")
    fun helloV1(): Mono<String> {
        return Mono.just("Hello World V1 at " + Instant.now().toString())
    }

    /**
     * Leaves `/api/v2/reactive/...` outside Bucket4j rate limiting.
     */
    @GetMapping("/api/v2/reactive/hello")
    fun helloV2(): Mono<String> {
        return Mono.just("Hello World V2 at " + Instant.now().toString())
    }
}
