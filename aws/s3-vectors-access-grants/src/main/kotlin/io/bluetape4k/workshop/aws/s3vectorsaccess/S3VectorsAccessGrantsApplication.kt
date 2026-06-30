package io.bluetape4k.workshop.aws.s3vectorsaccess

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class S3VectorsAccessGrantsApplication

fun main(args: Array<String>) {
    runApplication<S3VectorsAccessGrantsApplication>(*args)
}
