package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

internal class HolderNative {

    companion object: KLogging()

    init {
        log.info { "HolderNative created." }
    }

    private lateinit var heavy: Heavy

    fun getHeavy(): Heavy {
        if (!this::heavy.isInitialized) {
            heavy = Heavy()
        }
        return heavy
    }
}
