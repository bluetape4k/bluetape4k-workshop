package io.bluetape4k.workshop.operations.jobconsole.ktor

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionContractLoader
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionMode
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KtorJobConsoleHighContentionSelectionTest {

    @Test
    fun `every declared Job profile has exactly one Ktor live action`() {
        val contractRoot = Path.of(
            System.getProperty("highContentionContractRoot")
                .requireNotNull("highContentionContractRoot"),
        )
        val manifestProfileIds = HighContentionContractLoader().load(
            contractRoot = contractRoot,
            mode = HighContentionMode.CI_CORRECTNESS,
            profileId = "burst",
            implementation = "job-ktor",
        ).suite.entries
            .map { it.profileId }
            .toSet()

        KtorJobConsoleProfileAction.entries
            .map { it.profileId }
            .toSet()
            .shouldBeEqualTo(manifestProfileIds)
    }
}
