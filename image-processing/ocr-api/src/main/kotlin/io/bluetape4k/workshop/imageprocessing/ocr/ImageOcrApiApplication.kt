package io.bluetape4k.workshop.imageprocessing.ocr

import io.bluetape4k.workshop.imageprocessing.ocr.config.ImageOcrProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * 이미지 OCR API 워크숍 모듈의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = [ImageOcrProperties::class])
class ImageOcrApiApplication

fun main(args: Array<String>) {
    runApplication<ImageOcrApiApplication>(*args)
}
