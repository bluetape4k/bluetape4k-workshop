package io.bluetape4k.workshop.optimization.lastmile.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class LastMileLifecycleTest {
    @Test
    fun `virtual thread lifecycle fences admission before executor shutdown`() {
        val executor = VirtualThreads.executorService()
        val lifecycle = LastMileLifecycle(executor, shutdownTimeout = 5)

        lifecycle.accepting.shouldBeTrue()
        lifecycle.submit { Thread.currentThread().isVirtual }.get(5, TimeUnit.SECONDS).shouldBeTrue()
        lifecycle.close()
        lifecycle.accepting.shouldBeFalse()
        assertFailsWith<IllegalStateException> { lifecycle.submit { Unit } }
    }
}
