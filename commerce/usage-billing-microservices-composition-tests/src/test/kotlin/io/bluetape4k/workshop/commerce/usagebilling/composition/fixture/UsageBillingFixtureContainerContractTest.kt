package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UsageBillingFixtureContainerContractTest {

    private companion object: KLogging() {
        const val FIXTURE_PATH =
            "commerce/usage-billing-microservices-composition-tests/src/test/kotlin/" +
                    "io/bluetape4k/workshop/commerce/usagebilling/composition/fixture/UsageBillingMicroserviceFixture.kt"
    }


    @Test
    fun `fixture uses Bluetape wrappers while retaining the custom Kafka topology`() {
        val source = repositoryRoot()
            .resolve(FIXTURE_PATH)
            .toFile()
            .readText()

        source shouldContain "io.bluetape4k.testcontainers.database.PostgreSQLServer"
        source shouldContain "io.bluetape4k.testcontainers.infra.ToxiproxyServer"
        source shouldContain "org.testcontainers.kafka.KafkaContainer"
        source shouldNotContain "org.testcontainers.postgresql.PostgreSQLContainer"
        source shouldNotContain "org.testcontainers.toxiproxy.ToxiproxyContainer"
    }

    private fun repositoryRoot(): Path =
        Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().parent.parent

}
