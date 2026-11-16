package io.bluetape4k.workshop.optimization.shiftcoverage.application

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.concurrent.get
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class ShiftCoverageExecutorLifecycleTest {

    companion object: KLogging()

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
        lifecycle
            .submitCallable { "planned" }
            .shouldNotBeNull()
            .get(1.seconds) shouldBeEqualTo "planned"

        lifecycle.close()
        lifecycle.submitCallable { "rejected" }.shouldBeNull()
    }
}
