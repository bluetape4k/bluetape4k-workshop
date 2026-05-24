package io.bluetape4k.workshop.imageprocessing.advanced

import io.bluetape4k.workshop.imageprocessing.advanced.config.ImageProcessingAdvancedProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [ImageProcessingAdvancedProperties::class])
class ImageProcessingAdvancedApplication

fun main(args: Array<String>) {
    runApplication<ImageProcessingAdvancedApplication>(*args)
}
