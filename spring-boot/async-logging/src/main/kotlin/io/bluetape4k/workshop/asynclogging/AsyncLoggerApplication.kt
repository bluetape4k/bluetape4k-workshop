package io.bluetape4k.workshop.asynclogging

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AsyncLoggerApplication {

    companion object: KLoggingChannel() {
        init {
            log.info { "AsyncLoggerApplication is starting..." }

            Runtimex.addShutdownHook {
                log.info { "AsyncLoggerApplication is stopping..." }
            }
        }
    }
}

fun main(vararg args: String) {
    runApplication<AsyncLoggerApplication>(*args) {
        webApplicationType = WebApplicationType.REACTIVE
    }
}
