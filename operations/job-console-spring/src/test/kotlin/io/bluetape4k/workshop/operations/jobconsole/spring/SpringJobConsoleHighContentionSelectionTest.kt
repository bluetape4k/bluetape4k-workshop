package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionContractLoader
import io.bluetape4k.workshop.operations.jobconsole.highcontention.HighContentionMode
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SpringJobConsoleHighContentionSelectionTest {

    @Test
    fun `every declared Job profile has exactly one Spring live action`() {
        val contractRoot = Path.of(
            System.getProperty("highContentionContractRoot")
                .requireNotNull("highContentionContractRoot"),
        )
        val manifestProfileIds = HighContentionContractLoader().load(
            contractRoot = contractRoot,
            mode = HighContentionMode.CI_CORRECTNESS,
            profileId = "burst",
            implementation = "job-spring",
        ).suite.entries
            .map { it.profileId }
            .toSet()

        SpringJobConsoleProfileAction.entries
            .map { it.profileId }
            .toSet()
            .shouldBeEqualTo(manifestProfileIds)
    }
}
