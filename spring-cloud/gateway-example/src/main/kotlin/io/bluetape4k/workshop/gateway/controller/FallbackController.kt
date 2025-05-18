package io.bluetape4k.workshop.gateway.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/circuitbreaker")
class FallbackController {

    companion object: KLoggingChannel()

    @GetMapping("/fallback")
    suspend fun fallback(): String {
        return "Fallback for circuit breaker"
    }
}
