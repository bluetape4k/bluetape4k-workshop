package io.bluetape4k.workshop.optimization.warehouseallocation

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class WarehouseAllocationApplication

fun main(args: Array<String>) {
    runApplication<WarehouseAllocationApplication>(*args)
}
