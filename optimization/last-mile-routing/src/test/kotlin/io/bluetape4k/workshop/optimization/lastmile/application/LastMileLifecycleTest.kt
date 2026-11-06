package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class LastMileLifecycleTest {

    companion object: KLogging()

    @Test
    fun `virtual thread lifecycle fences admission before executor shutdown`() {
        val executor = VirtualThreads.executorService()
        val lifecycle = LastMileLifecycle(executor, shutdownTimeout = 5)

        lifecycle.accepting.shouldBeTrue()
        lifecycle.submit { Thread.currentThread().isVirtual }.get(5.seconds).shouldBeTrue()
        lifecycle.close()
        lifecycle.accepting.shouldBeFalse()

        assertFailsWith<IllegalStateException> {
            lifecycle.submit { Unit }
        }
    }
}
