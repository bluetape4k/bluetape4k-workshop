package io.bluetape4k.workshop.spring.security.webflux.jwt.controller

import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HelloController {

    @GetMapping("/")
    suspend fun hello(authentication: Authentication): String {
        return "Hello, ${authentication.name}!"  // Hello, user!
    }
}
