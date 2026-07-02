package io.bluetape4k.workshop.aws.eventbridge

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

/**
 * Spring Boot entry point for the EventBridge Scheduler workshop module.
 */
@SpringBootApplication
@EnableConfigurationProperties(OrderWorkflowProperties::class)
class EventBridgeSchedulerApplication

fun main(args: Array<String>) {
    runApplication<EventBridgeSchedulerApplication>(*args)
}
