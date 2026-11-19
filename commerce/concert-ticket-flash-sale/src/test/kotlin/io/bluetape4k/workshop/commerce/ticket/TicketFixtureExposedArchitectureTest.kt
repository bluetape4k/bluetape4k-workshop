package io.bluetape4k.workshop.commerce.ticket

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 일반 ticket fixture assertion이 범용 RAW query helper로 되돌아가지 않도록 고정합니다. */
internal class TicketFixtureExposedArchitectureTest {

    @Test
    fun `integration fixture assertions use typed Exposed queries`() {
        val files = listOf(
            "io/bluetape4k/workshop/commerce/ticket/purchase/PurchaseServiceIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/AbstractTicketIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/ticketing/TicketEffectIntegrationTest.kt",
            "io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileApplication.kt",
            "io/bluetape4k/workshop/commerce/ticket/highcontention/TicketHighContentionProfileTest.kt",
        ).map { locateTestSourceRoot().resolve(it) }

        files.forEach { file ->
            val source = Files.readString(file)
            source.shouldNotContain("queryInt(")
            source.shouldNotContain("queryString(")
            source.shouldNotContain("queryUuid(")
        }

        Files.readString(files.first()).shouldContain("TicketInventories")
    }

    private fun locateTestSourceRoot(): Path {
        val current = Path.of("").toAbsolutePath()
        val moduleLocal = current.resolve("src/test/kotlin")
        if (Files.isDirectory(moduleLocal)) return moduleLocal
        return current.resolve("commerce/concert-ticket-flash-sale/src/test/kotlin")
    }
}
