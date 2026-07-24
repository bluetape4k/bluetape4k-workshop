package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.highcontention.JobConsoleLiveProfileRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
@Tag("high-contention")
class SpringJobConsoleHighContentionProfileTest {

    @Test
    fun `selected Spring profile crosses HTTP Redis and application lifecycle boundaries`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-spring",
            adapterFactory = SpringJobConsoleLiveAdapter::create,
        ).run()
    }
}
