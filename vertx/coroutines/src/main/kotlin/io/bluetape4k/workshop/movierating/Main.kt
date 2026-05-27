package io.bluetape4k.workshop.movierating

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait

private object Main: KLogging()

suspend fun main() {
    val vertx = Vertx.vertx()

    try {
        val result = vertx.deployVerticle(MovieRatingVerticle()).coAwait()
        Main.log.info { "Application started. $result" }
    } catch (e: Throwable) {
        Main.log.warn(e) { "Could not start application." }
    }
}
