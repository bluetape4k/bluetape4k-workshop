package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading.coroutines

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

internal class HeavyDeferredValueTest {

    companion object: KLogging()

    private val heavyDeferredValue = HeavyDeferredValue()

    @Test
    @Timeout(3)
    fun `HeavyDeferredValue 를 사용하여 HeavyDeferred 생성하기`() = runSuspendIO {
        repeat(5) {
            log.debug { "Getting heavy deferred ... $it" }
            heavyDeferredValue.getHeavyDeferred().shouldNotBeNull()
        }
    }
}
