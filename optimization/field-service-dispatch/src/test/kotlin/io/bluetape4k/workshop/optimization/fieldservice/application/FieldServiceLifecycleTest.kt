package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class FieldServiceLifecycleTest {
    @Test
    fun `close rejects admission then drains executor and is idempotent`() {
        val executor = Executors.newFixedThreadPool(1)
        val lifecycle = FieldServiceExecutorLifecycle(executor)

        lifecycle.accepting.shouldBeTrue()
        lifecycle.close()
        lifecycle.accepting.shouldBeFalse()
        lifecycle.executorTerminated.shouldBeTrue()
        lifecycle.close()
        lifecycle.shutdownTimedOut.shouldBeFalse()
    }
}
