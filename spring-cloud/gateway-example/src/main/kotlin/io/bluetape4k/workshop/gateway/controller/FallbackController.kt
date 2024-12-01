package io.bluetape4k.workshop.gateway.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/circuitbreaker")
class FallbackController {

    @GetMapping("/fallback")
    suspend fun fallback(): String {
        return "Fallback for circuit breaker"
    }
}
