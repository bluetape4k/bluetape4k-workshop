package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("high-contention")
class KtorJobConsoleHighContentionProfileTest {

    @Test
    fun `selected Ktor profile crosses HTTP Redis and server lifecycle boundaries`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-ktor",
            adapterFactory = KtorJobConsoleLiveAdapter::create,
        ).run()
    }
}
