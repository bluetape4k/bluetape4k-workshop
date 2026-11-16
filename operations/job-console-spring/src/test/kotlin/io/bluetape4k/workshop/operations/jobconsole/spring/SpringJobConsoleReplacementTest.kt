package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.javatimes.millis
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class SpringJobConsoleReplacementTest {

    companion object: KLogging()

    @Test
    fun `scheduled worker completion wins without requiring a second reclaim`() {
        val completionChecks = AtomicInteger()

        val result = awaitSpringReplacement(
            timeout = 1.seconds(),
            pollInterval = 1.millis(),
            reclaim = { null },
            isCompleted = { completionChecks.incrementAndGet() >= 2 },
        )

        log.debug { "result=$result" }
        result shouldBeEqualTo SpringReplacementOutcome.Completed
    }
}
