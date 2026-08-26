package io.bluetape4k.workshop.optimization.shiftcoverage

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ShiftCoverageApplication

fun main(args: Array<String>) {
    runApplication<ShiftCoverageApplication>(*args)
}
