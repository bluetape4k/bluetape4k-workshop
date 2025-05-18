package io.bluetape4k.workshop.virtualthread.undertow

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UndertowVirtualThreadMvcApp {

    companion object: KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<UndertowVirtualThreadMvcApp>(*args) {
        webApplicationType = WebApplicationType.SERVLET
    }
}
