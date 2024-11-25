package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading.coroutines

import io.bluetape4k.coroutines.DeferredValue
import io.bluetape4k.logging.KLogging

class HeavyDeferredValue {

    companion object: KLogging()

    private val _heavy = DeferredValue { HeavyDeferred().getHeavy() }

    suspend fun getHeavyDeferred(): HeavyDeferred = _heavy.await()
}