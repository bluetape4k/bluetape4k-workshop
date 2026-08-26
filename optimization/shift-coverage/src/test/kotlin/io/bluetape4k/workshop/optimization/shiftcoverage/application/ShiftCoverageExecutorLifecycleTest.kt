package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class ShiftCoverageExecutorLifecycleTest {
    @Test
    fun `close rejects new admission and is idempotent`() {
        val lifecycle = ShiftCoverageExecutorLifecycle(plannerWorkers = 1, plannerQueue = 1)
        lifecycle.isReady().shouldBeTrue()
        lifecycle.close()
        lifecycle.isReady().shouldBeFalse()
        lifecycle.close()
    }

    @Test
    fun `callable admission returns a bounded result before shutdown`() {
        val lifecycle = ShiftCoverageExecutorLifecycle(plannerWorkers = 1, plannerQueue = 1)
        lifecycle.submitCallable(Callable { "planned" })
            .shouldNotBeNull()
            .get(1, TimeUnit.SECONDS) shouldBeEqualTo "planned"
        lifecycle.close()
        lifecycle.submitCallable(Callable { "rejected" }).shouldBeNull()
    }
}
