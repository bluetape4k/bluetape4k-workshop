package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController {

    companion object: KLoggingChannel()

    @GetMapping("/")
    suspend fun index(): String = "index"

    @GetMapping("/user/index")
    suspend fun userIndex(): String = "user/index"

    @GetMapping("/log-in")
    fun login(): String = "login"
}
