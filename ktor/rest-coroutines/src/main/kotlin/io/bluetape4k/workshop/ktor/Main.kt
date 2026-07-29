package io.bluetape4k.workshop.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

private object MainLog : KLogging()

/**
 * `ktor-rest-coroutines` workshop application 의 entry point 입니다.
 *
 * port 8080 에서 Netty 기반 Ktor server 를 시작합니다.
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
