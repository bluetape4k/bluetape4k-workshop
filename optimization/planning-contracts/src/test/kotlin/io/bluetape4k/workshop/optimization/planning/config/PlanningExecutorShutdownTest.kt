package io.bluetape4k.workshop.optimization.planning.config

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import org.junit.jupiter.api.Test
import java.time.Duration

class PlanningExecutorShutdownTest {

    @Test
    fun `shutdown drains submitted virtual thread tasks`() {
        val executor = VirtualThreads.executorService()
        val completed = executor.submit<Boolean> { Thread.currentThread().isVirtual }

        PlanningExecutorShutdown(executor, Duration.ofSeconds(1)).close()

        completed.get().shouldBeTrue()
        executor.isTerminated.shouldBeTrue()
    }
}
