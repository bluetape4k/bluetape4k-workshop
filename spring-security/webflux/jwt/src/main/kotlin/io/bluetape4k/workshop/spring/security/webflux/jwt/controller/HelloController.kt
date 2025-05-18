package io.bluetape4k.workshop.spring.security.webflux.jwt.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HelloController {

    companion object: KLoggingChannel()

    @GetMapping("/")
    suspend fun hello(authentication: Authentication): String {
        return "Hello, ${authentication.name}!"  // Hello, user!
    }
}
