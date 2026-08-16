package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleV1LiveContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI

@Tag("integration")
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringJobConsoleHttpTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `live REST submit is completed by the owned worker lifecycle`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyOwnedWorkerLifecycle("spring-key")
    }

    @Test
    fun `live problem request IDs use UUID version seven`() {
        JobConsoleV1LiveContract(URI("http://127.0.0.1:$port")).verifyProblemRequestIdUsesUuidV7()
    }

    companion object {
        private val fixture = JobConsoleContainerFixture.shared().also { it.createSchema() }

        @JvmStatic
        @AfterAll
        fun dropSchema() {
            fixture.dropSchema()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { fixture.jdbcUrl }
            registry.add("spring.datasource.username") { fixture.databaseUsername }
            registry.add("spring.datasource.password") { fixture.databasePassword }
            registry.add("spring.datasource.hikari.schema") { fixture.schema }
            registry.add("job-console.bounded-wait.enabled") { "true" }
        }
    }
}
