package io.bluetape4k.workshop.optimization.planning.persistence

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.exposed.tests.withTables
import io.bluetape4k.workshop.optimization.planning.domain.PlanningStatus
import io.bluetape4k.workshop.optimization.planning.domain.PlanningProvider
import org.junit.jupiter.api.Test
import java.util.UUID

class PlanningRequestRepositoryTest {

    private val repository = PlanningRequestRepository()

    @Test
    fun `request repository reuses inherited CRUD against PostgreSQL`() {
        val request = PlanningRequestRecord(
            id = UUID.fromString("019c6b9e-4dc0-7e73-9cf8-84ecfda3fd8b"),
            aggregateId = "schedule-42",
            aggregateVersion = 7,
            datasetId = "dataset-2026-07-18",
            parentRevision = 3,
            status = PlanningStatus.QUEUED,
            provider = PlanningProvider.FAKE,
        )

        withTables(TestDB.POSTGRESQL, PlanningRequestTable) {
            val saved = repository.save(request)

            repository.findByIdOrNull(request.id) shouldBeEqualTo saved
            repository.count() shouldBeEqualTo 1L
            repository.existsById(request.id).shouldBeTrue()
        }
    }
}
