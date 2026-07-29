package io.bluetape4k.workshop.spring.modulith.boundaries

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Spring Modulith boundary verification 예제의 Spring Boot entrypoint 입니다.
 */
@SpringBootApplication
class ModuleBoundariesApplication

fun main(args: Array<String>) {
    runApplication<ModuleBoundariesApplication>(*args)
}
