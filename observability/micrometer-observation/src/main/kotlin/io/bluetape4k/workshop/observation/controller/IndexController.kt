package io.bluetape4k.workshop.observation.controller

import io.bluetape4k.logging.KLogging
import io.micrometer.observation.annotation.Observed
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class IndexController {

    companion object: KLogging()

    @GetMapping("/ping")
    @Observed(name = "index.ping")
    fun ping(): String {
        return "PONG!"
    }

}
