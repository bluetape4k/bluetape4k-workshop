package io.bluetape4k.workshop.commerce.reservation

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

internal class ReservationFixtureExposedArchitectureTest {

    @Test
    fun `integration fixture reset uses Exposed schema lifecycle`() {
        val source = Files.readString(locateSource())
        source.shouldContain("SchemaUtils.drop")
        source.shouldContain("SchemaUtils.create")
        source.shouldNotContain("JdbcTemplate")
        source.shouldNotContain("TRUNCATE TABLE")
    }

    private fun locateSource(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = directory?.resolve(
                "commerce/reservation-control-plane/src/test/kotlin/io/bluetape4k/workshop/commerce/reservation/AbstractReservationIntegrationTest.kt",
            )
            if (candidate != null && Files.isRegularFile(candidate)) return candidate
            directory = directory?.parent
        }
        error("AbstractReservationIntegrationTest.kt를 찾을 수 없습니다")
    }
}
