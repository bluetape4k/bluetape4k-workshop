package io.bluetape4k.workshop.spring.modulith.boundaries

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Boot entrypoint for the Spring Modulith boundary verification example.
 */
@SpringBootApplication
class ModuleBoundariesApplication

fun main(args: Array<String>) {
    runApplication<ModuleBoundariesApplication>(*args)
}
