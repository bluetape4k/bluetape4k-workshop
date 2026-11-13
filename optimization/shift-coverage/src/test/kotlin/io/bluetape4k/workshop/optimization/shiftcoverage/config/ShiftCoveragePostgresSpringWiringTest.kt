package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.workshop.optimization.shiftcoverage.ShiftCoverageApplication
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageDemoService
import io.bluetape4k.workshop.optimization.shiftcoverage.application.ShiftCoverageIdempotencyPort
import io.bluetape4k.workshop.optimization.shiftcoverage.domain.IdempotencyKey
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoverageAssignmentStore
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoveragePostgresAssignmentRepository
import io.bluetape4k.workshop.optimization.shiftcoverage.persistence.ShiftCoveragePostgresIdempotencyRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest(classes = [ShiftCoverageApplication::class], webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
class ShiftCoveragePostgresSpringWiringTest {

    companion object: KLogging() {
        private val postgres by lazy { PostgreSQLServer.Launcher.postgres }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username.shouldNotBeNull() }
            registry.add("spring.datasource.password") { postgres.password.shouldNotBeNull() }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }

    @Autowired
    private lateinit var assignmentStore: ShiftCoverageAssignmentStore

    @Autowired
    private lateinit var idempotencyStore: ShiftCoverageIdempotencyPort

    @Autowired
    private lateinit var service: ShiftCoverageDemoService

    @Test
    fun `postgres profile routes replan approval and idempotency through database beans`() {
        assignmentStore.shouldBeInstanceOf<ShiftCoveragePostgresAssignmentRepository>()
        idempotencyStore.shouldBeInstanceOf<ShiftCoveragePostgresIdempotencyRepository>()

        service.replan(
            idempotencyKey = IdempotencyKey("postgres-replan"),
            principal = "manager-demo",
            requestId = "postgres-request"
        )
        service.approve(
            1L,
            IdempotencyKey("postgres-approve"),
            "manager-demo"
        ).shouldBeTrue()

        assignmentStore.listAssignments() shouldHaveSize 1
    }
}
