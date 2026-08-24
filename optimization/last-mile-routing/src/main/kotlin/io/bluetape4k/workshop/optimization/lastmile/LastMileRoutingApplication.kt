package io.bluetape4k.workshop.optimization.lastmile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LastMileRoutingApplication

fun main(args: Array<String>) {
    runApplication<LastMileRoutingApplication>(*args)
}
