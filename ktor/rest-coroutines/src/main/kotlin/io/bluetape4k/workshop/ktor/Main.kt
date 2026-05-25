package io.bluetape4k.workshop.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private object MainLog : KLogging()

/**
 * Entry point for the `ktor-rest-coroutines` workshop application.
 *
 * Starts a Netty-backed Ktor server on port 8080.
 *
 * ```
 * ./gradlew :ktor-rest-coroutines:run
 * ```
 */
fun main() {
    MainLog.log.info { "Starting ktor-rest-coroutines workshop server on port 8080" }
    embeddedServer(
        factory = Netty,
        port = 8080,
        host = "0.0.0.0",
        module = { module() },
    ).start(wait = true)
}
