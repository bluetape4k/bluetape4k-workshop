package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import kotlinx.atomicfu.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thread-safe 하게 지연 생성을 수행
 */
internal class HolderThreadSafe {

    companion object: KLogging()

    init {
        log.info { "HolderThreadSafe created." }
    }

    private lateinit var heavy: Heavy
    private val lock = ReentrantLock()

    fun getHeavy(): Heavy {
        if (!this::heavy.isInitialized) {
            lock.withLock {
                if (!this::heavy.isInitialized) {
                    heavy = Heavy()
                }
            }
        }
        return heavy
    }
}
