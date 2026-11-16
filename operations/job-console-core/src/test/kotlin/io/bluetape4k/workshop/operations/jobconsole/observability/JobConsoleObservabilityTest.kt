package io.bluetape4k.workshop.operations.jobconsole.observability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test

class JobConsoleObservabilityTest {

    companion object: KLogging()

    @Test
    fun `identity and payload cannot become metric labels`() {
        val tags = JobConsoleObservability.safeTags(
            mapOf(
                "adapter" to "spring",
                "state" to "queued",
                "tenant" to "tenant-a",
                "jobId" to "secret-id",
                "payload" to "raw-body",
            ),
        )

        log.debug { "tags=$tags" }
        tags shouldBeEqualTo mapOf("adapter" to "spring", "state" to "queued")
    }
}
