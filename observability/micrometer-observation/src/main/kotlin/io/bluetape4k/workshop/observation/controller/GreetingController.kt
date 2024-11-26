package io.bluetape4k.workshop.observation.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.observation.service.GreetingService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/greeting")
class GreetingController(private val greetingService: GreetingService) {

    companion object: KLogging()

    @GetMapping
    fun greet(): String {
        return greetingService.sayHello()
    }

    @GetMapping("/for")
    fun greetWithName(@RequestParam(name = "name") name: String): String {
        return greetingService.sayHelloWithName(name)
    }
}
