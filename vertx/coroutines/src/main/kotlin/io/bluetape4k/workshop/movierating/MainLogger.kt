package io.bluetape4k.workshop.movierating

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import java.util.concurrent.CancellationException

private object MainLogger: KLogging()

suspend fun main() {
    val vertx = Vertx.vertx()

    try {
        val result = vertx.deployVerticle(MovieRatingVerticle()).coAwait()
        MainLogger.log.info { "Application started. $result" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        MainLogger.log.warn(e) { "Could not start application." }
    }
}
