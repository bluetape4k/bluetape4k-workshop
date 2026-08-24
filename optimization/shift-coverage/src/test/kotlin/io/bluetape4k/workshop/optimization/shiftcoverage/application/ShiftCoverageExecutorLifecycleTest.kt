package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
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
}
