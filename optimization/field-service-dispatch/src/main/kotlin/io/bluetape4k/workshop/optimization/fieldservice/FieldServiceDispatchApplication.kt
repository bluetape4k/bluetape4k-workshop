package io.bluetape4k.workshop.optimization.fieldservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FieldServiceDispatchApplication

fun main(args: Array<String>) {
    runApplication<FieldServiceDispatchApplication>(*args)
}
