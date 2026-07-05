package io.bluetape4k.workshop.gateway.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/hello")
class IndexController {

    companion object: KLoggingChannel()

    @GetMapping
    suspend fun sayHello(
        @RequestParam("name", required = false, defaultValue = "Bluetape4k") name: String,
    ): String {
        val displayName = name.trim().ifBlank { "Bluetape4k" }
        return "Hello $displayName from API Gateway"
    }
}
