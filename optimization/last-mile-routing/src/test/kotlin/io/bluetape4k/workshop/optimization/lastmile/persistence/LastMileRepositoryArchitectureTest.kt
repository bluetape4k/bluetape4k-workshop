package io.bluetape4k.workshop.optimization.lastmile.persistence

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.exposed.jdbc.repository.JdbcRepository
import org.junit.jupiter.api.Test

class LastMileRepositoryArchitectureTest {
    @Test
    fun `durable last mile adapters use bluetape4k exposed jdbc repositories`() {
        listOf(
            LastMileJobRepository(),
            LastMileVehicleRepository(),
            LastMileMatrixRevisionRepository(),
            LastMileMatrixEdgeRepository(),
            LastMilePlanRepository(),
            LastMilePlanCarrierRepository(),
            LastMilePlanStopRepository(),
            LastMileUnassignedRepository(),
            LastMileCommittedStopRepository(),
            LastMileEventRepository(),
            LastMileCallbackInboxRepository(),
            LastMileOutboxRepository(),
            LastMileAuditRepository(),
        ).forEach { repository ->
            repository.shouldBeInstanceOf<JdbcRepository<*, *>>()
        }
    }
}
