package io.bluetape4k.workshop.gateway.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/hello")
class IndexController {

    @GetMapping
    suspend fun sayHello(
        @RequestParam("name", required = false, defaultValue = "Bluetape4k") name: String,
    ): String {
        return "Hello $name from API Gateway"
    }
}