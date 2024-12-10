package io.bluetape4k.workshop.spring.security.webflux

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController {

    @GetMapping("/")
    suspend fun index(): String = "index"

    @GetMapping("/user/index")
    suspend fun userIndex(): String = "user/index"

    @GetMapping("/log-in")
    fun login(): String = "login"
}
