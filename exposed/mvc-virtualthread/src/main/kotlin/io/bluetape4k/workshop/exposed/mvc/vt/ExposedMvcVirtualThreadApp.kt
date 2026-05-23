package io.bluetape4k.workshop.exposed.mvc.vt

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExposedMvcVirtualThreadApp

fun main(args: Array<String>) {
    runApplication<ExposedMvcVirtualThreadApp>(*args)
}
