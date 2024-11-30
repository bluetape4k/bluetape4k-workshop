package io.bluetape4k.workshop.gateway

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient

@EnableDiscoveryClient
@SpringBootApplication
class ApiGatewayDemoApplication {

    companion object: KLogging() {
        init {
            log.info { "Starting GatewayApplication ..." }
        }
    }
}

fun main(vararg args: String) {
    runApplication<ApiGatewayDemoApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
