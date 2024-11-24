package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading.coroutines

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import kotlinx.coroutines.delay

class HeavyDeferred {

    companion object: KLogging()

    suspend fun getHeavy(): HeavyDeferred {
        log.info { "Getting heavy deferred ..." }

        delay(1000)
        val heavy = HeavyDeferred()

        log.info { "... Got heavy deferred" }
        return heavy
    }
}
