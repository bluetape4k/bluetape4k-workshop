package io.bluetape4k.workshop.optimization.planning.config

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class PlanningExecutorShutdownTest {

    companion object: KLogging()

    @Test
    fun `shutdown drains submitted virtual thread tasks`() {
        val executor = VirtualThreads.executorService()
        val completed = executor.submit<Boolean> { Thread.currentThread().isVirtual }

        PlanningExecutorShutdown(executor, 1.seconds()).close()

        completed.get().shouldBeTrue()
        executor.isTerminated.shouldBeTrue()
    }
}
