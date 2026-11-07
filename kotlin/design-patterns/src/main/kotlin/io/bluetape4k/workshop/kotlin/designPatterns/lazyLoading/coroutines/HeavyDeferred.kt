package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading.coroutines

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

class HeavyDeferred {

    companion object: KLoggingChannel()

    suspend fun getHeavy(): HeavyDeferred {
        log.info { "Getting heavy deferred ..." }

        delay(1.seconds)
        val heavy = HeavyDeferred()

        log.info { "... Got heavy deferred" }
        return heavy
    }
}
