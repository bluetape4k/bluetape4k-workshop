package io.bluetape4k.workshop.aws.sqssns

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point for the local-first SQS/SNS coroutine messaging workshop.
 */
@SpringBootApplication
@EnableConfigurationProperties(SqsSnsMessagingProperties::class)
class SqsSnsMessagingApplication

fun main(args: Array<String>) {
    runApplication<SqsSnsMessagingApplication>(*args)
}
