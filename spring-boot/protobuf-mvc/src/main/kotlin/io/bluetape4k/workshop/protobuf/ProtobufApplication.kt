package io.bluetape4k.workshop.protobuf

import io.bluetape4k.logging.KLogging
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ProtobufApplication {

    companion object: KLogging()
}

fun main(args: Array<String>) {
    runApplication<ProtobufApplication>(*args)
}
