package io.bluetape4k.workshop.cbor

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CborApplication {

    companion object: KLogging()

}

fun main(args: Array<String>) {
    runApplication<CborApplication>(*args)
}
