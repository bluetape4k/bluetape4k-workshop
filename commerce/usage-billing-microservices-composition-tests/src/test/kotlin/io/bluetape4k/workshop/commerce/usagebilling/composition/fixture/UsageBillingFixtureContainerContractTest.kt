package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UsageBillingFixtureContainerContractTest {

    @Test
    fun `fixture uses Bluetape wrappers while retaining the custom Kafka topology`() {
        val source = repositoryRoot()
            .resolve(FIXTURE_PATH)
            .toFile()
            .readText()

        source.contains("io.bluetape4k.testcontainers.database.PostgreSQLServer").shouldBeTrue()
        source.contains("io.bluetape4k.testcontainers.infra.ToxiproxyServer").shouldBeTrue()
        source.contains("org.testcontainers.kafka.KafkaContainer").shouldBeTrue()
        source.contains("org.testcontainers.postgresql.PostgreSQLContainer").shouldBeFalse()
        source.contains("org.testcontainers.toxiproxy.ToxiproxyContainer").shouldBeFalse()
    }

    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

    private companion object {
        const val FIXTURE_PATH =
            "commerce/usage-billing-microservices-composition-tests/src/test/kotlin/" +
                "io/bluetape4k/workshop/commerce/usagebilling/composition/fixture/UsageBillingMicroserviceFixture.kt"
    }
}
