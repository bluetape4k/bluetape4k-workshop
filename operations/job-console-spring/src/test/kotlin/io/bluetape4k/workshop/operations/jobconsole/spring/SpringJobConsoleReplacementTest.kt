package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class SpringJobConsoleReplacementTest {

    @Test
    fun `scheduled worker completion wins without requiring a second reclaim`() {
        val completionChecks = AtomicInteger()

        val result = awaitSpringReplacement(
            timeout = Duration.ofSeconds(1),
            pollInterval = Duration.ofMillis(1),
            reclaim = { null },
            isCompleted = { completionChecks.incrementAndGet() >= 2 },
        )

        result shouldBeEqualTo SpringReplacementOutcome.Completed
    }
}
