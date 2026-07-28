package io.bluetape4k.workshop.aws.sqssns

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * 로컬 우선 SQS/SNS 코루틴 메시징 워크숍의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
@EnableConfigurationProperties(SqsSnsMessagingProperties::class)
class SqsSnsMessagingApplication

fun main(args: Array<String>) {
    runApplication<SqsSnsMessagingApplication>(*args)
}
