package io.bluetape4k.workshop.micrometer

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZipkinServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TracingApplication {

    companion object: KLogging() {
        @JvmStatic
        val zipkinServer = ZipkinServer.Launcher.zipkin

        @JvmStatic
        val zipkinUrl: String get() = zipkinServer.url
    }

}

fun main(vararg args: String) {
    runApplication<TracingApplication>(*args) 
}
