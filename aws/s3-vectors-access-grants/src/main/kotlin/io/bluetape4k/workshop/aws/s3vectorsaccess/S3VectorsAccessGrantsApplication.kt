package io.bluetape4k.workshop.aws.s3vectorsaccess

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * S3 Vectors와 Access Grants 워크숍 예제의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class S3VectorsAccessGrantsApplication

fun main(args: Array<String>) {
    runApplication<S3VectorsAccessGrantsApplication>(*args)
}
