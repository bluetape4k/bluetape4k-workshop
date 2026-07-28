package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * EventBridge Scheduler 워크숍 모듈의 Spring Boot 진입점입니다.
 */
@SpringBootApplication
@EnableConfigurationProperties(OrderWorkflowProperties::class)
class EventBridgeSchedulerApplication

fun main(args: Array<String>) {
    runApplication<EventBridgeSchedulerApplication>(*args)
}
