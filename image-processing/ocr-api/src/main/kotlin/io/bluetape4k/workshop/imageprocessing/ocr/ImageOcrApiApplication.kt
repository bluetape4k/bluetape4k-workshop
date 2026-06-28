package io.bluetape4k.workshop.imageprocessing.ocr

import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Spring Boot entrypoint for the image OCR API workshop module.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [ImageOcrProperties::class])
class ImageOcrApiApplication

fun main(args: Array<String>) {
    runApplication<ImageOcrApiApplication>(*args)
}
