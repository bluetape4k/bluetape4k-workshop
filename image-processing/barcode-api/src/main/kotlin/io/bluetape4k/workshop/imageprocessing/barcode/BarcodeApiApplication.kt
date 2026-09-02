package io.bluetape4k.workshop.imageprocessing.barcode

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * `bluetape4k-dependencies` `2.0.0`으로 관리되는 bluetape4k-images barcode API를
 * 사용하는 Spring Boot 워크숍 애플리케이션입니다.
 */
@SpringBootApplication
class BarcodeApiApplication

fun main(args: Array<String>) {
    runApplication<BarcodeApiApplication>(*args)
}
