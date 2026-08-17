package io.bluetape4k.workshop.aws.kinesis

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/** local-first Kinesis 코루틴 워크숍의 Spring Boot 진입점입니다. */
@SpringBootApplication
@EnableConfigurationProperties(KinesisWorkshopProperties::class)
class KinesisCoroutinesApplication

fun main(args: Array<String>) {
    runApplication<KinesisCoroutinesApplication>(*args)
}
