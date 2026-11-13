package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.javatimes.millis
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.micrometer.model.Todo
import io.bluetape4k.workshop.micrometer.service.ReactorService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/reactor")
class ReactorController(
    private val reactorService: ReactorService,
) {
    companion object: KLoggingChannel()

    @GetMapping("/name")
    fun getName(): Mono<String> {
        return Mono
            .delay(100.millis())
            .flatMap { reactorService.getName() }
    }

    @GetMapping("/todos/{id}")
    fun getTodo(@PathVariable(required = true) id: Int): Mono<Todo> {
        log.debug { "Get todo[$id] in reactive" }

        return Mono.delay(100.millis())
            .flatMap { reactorService.getTodoById(id) }
            .doOnEach { log.debug { "Get todo by id[$id] in reactive" } }
            .delayElement(100.millis())
    }
}
