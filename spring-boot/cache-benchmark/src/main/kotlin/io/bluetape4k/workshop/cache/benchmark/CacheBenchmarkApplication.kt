package io.bluetape4k.workshop.cache.benchmark

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication(proxyBeanMethods = false)
@EnableCaching
class CacheBenchmarkApplication {
    companion object : KLoggingChannel()
}

fun main(vararg args: String) {
    runApplication<CacheBenchmarkApplication>(*args)
}
