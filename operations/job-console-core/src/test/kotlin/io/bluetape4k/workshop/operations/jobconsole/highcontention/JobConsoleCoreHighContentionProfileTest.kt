package io.bluetape4k.workshop.operations.jobconsole.highcontention

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("high-contention")
class JobConsoleCoreHighContentionProfileTest {

    @Test
    fun `selected Job core profile preserves PostgreSQL authority and writes a validated report`() {
        JobConsoleLiveProfileRunner(
            implementation = "job-core",
            adapterFactory = { _, profile ->
                JobConsoleHighContentionAdapter.create(profile)
            },
        ).run()
    }
}
