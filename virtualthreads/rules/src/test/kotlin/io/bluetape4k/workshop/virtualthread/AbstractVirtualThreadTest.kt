package io.bluetape4k.workshop.virtualThreads

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

abstract class AbstractVirtualThreadTest {

    companion object: KLogging() {

        protected fun sleep(millis: Int) {
            TimeUnit.MILLISECONDS.sleep(millis.toLong())
        }

        @JvmStatic
        protected fun <T> sleepAndGet(millis: Int, value: T): T {
            log.info { "$value started" }
            sleep(millis)
            log.info { "$value finished" }
            return value
        }

        @JvmStatic
        protected suspend fun <T> sleepAndGetAwait(millis: Int, value: T): T {
            log.info { "$value started" }
            delay(millis.toLong())
            log.info { "$value finished" }
            return value
        }
    }
}
