package io.bluetape4k.workshop.gateway

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.client.discovery.EnableDiscoveryClient

@EnableDiscoveryClient
@SpringBootApplication
class ApiGatewayDemoApplication {

    companion object: KLoggingChannel() {
        init {
            log.info { "Starting GatewayApplication ..." }
        }
    }
}

fun main(vararg args: String) {
    runApplication<ApiGatewayDemoApplication>(*args) 
}
