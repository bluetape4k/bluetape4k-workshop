package io.bluetape4k.workshop.optimization.shiftcoverage.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
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

        service.replan(IdempotencyKey("postgres-replan"), "manager-demo", "postgres-request")
        service.approve(1L, IdempotencyKey("postgres-approve"), "manager-demo").shouldBeTrue()
        assignmentStore.listAssignments().size shouldBeEqualTo 1
    }

    companion object {
        private val postgres = PostgreSQLServer.Launcher.postgres

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { requireNotNull(postgres.username) }
            registry.add("spring.datasource.password") { requireNotNull(postgres.password) }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }
}
