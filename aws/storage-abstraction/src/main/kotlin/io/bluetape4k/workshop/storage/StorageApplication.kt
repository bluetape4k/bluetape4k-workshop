package io.bluetape4k.workshop.storage

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

fun main(vararg args: String) {
    runApplication<StorageApplication>(*args)
}

@SpringBootApplication(proxyBeanMethods = false)
class StorageApplication {
    companion object : KLogging()
}
